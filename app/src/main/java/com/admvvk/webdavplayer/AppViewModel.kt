package com.admvvk.webdavplayer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.admvvk.webdavplayer.webdav.WebDavClient
import com.admvvk.webdavplayer.webdav.WebDavResource
import com.admvvk.webdavplayer.webdav.isPlayable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val client: WebDavClient) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Remembers the last successfully used WebDAV server settings in plain
 * SharedPreferences, so the user does not have to retype them every launch.
 * NOTE: the password is stored in plaintext in the app's private storage
 * (not encrypted) -- fine for a personal/test client, not for a hardened app.
 *
 * Playback (the ExoPlayer instance and its state) also lives here rather
 * than in PlayerScreen, so navigating "Exit to list" and back doesn't tear
 * the player down -- the file list shows a persistent mini-player instead.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    var currentPath by mutableStateOf("/")
    var entries by mutableStateOf<List<WebDavResource>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    /** Transient, non-error info banner (e.g. "no media files in this folder"). */
    var infoMessage by mutableStateOf<String?>(null)

    // The configured root (whatever URL the user entered in settings, which
    // may itself point at a sub-folder like ".../music"). `currentHref` is
    // null while browsing that root; once the user opens a sub-folder it
    // holds the server-absolute href of the folder being shown.
    private var rootUrl: String = ""
    private var currentHref: String? = null

    /** Ancestor hrefs (null = root) to support "go up one level". */
    var pathStack by mutableStateOf<List<String?>>(emptyList())
    val canGoUp: Boolean get() = pathStack.isNotEmpty()

    val savedServerUrl: String get() = prefs.getString(KEY_URL, "") ?: ""
    val savedUsername: String get() = prefs.getString(KEY_USER, "") ?: ""
    val savedPassword: String get() = prefs.getString(KEY_PASS, "") ?: ""

    private fun client(): WebDavClient? = (connectionState as? ConnectionState.Connected)?.client

    // --- Next-track pre-caching (Connection Settings) -----------------

    var cacheSizeMb by mutableStateOf(prefs.getInt(KEY_CACHE_MB, DEFAULT_CACHE_MB))
    private var mediaCache: SimpleCache? = null

    private fun getOrCreateCache(): SimpleCache {
        mediaCache?.let { return it }
        val dir = File(getApplication<Application>().cacheDir, "media_cache")
        val cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(cacheSizeMb * 1024L * 1024L))
        mediaCache = cache
        return cache
    }

    fun updateCacheSizeMb(mb: Int) {
        val clamped = mb.coerceIn(16, 8192)
        cacheSizeMb = clamped
        prefs.edit().putInt(KEY_CACHE_MB, clamped).apply()
        // The cache size (evictor limit) is fixed at construction time, so
        // changing it means recreating the cache. Only safe to do when no
        // player currently holds a DataSource backed by the old instance --
        // Settings screen #2 (where this is reachable) deliberately does
        // NOT stop playback to open, so defer the swap: it takes effect
        // next time the cache is (re)built, i.e. once stopAndClearPlayback()
        // / onCleared() has released the old player and its cache.
        if (exoPlayer == null) {
            mediaCache?.release()
            mediaCache = null
        }
    }

    // --- Sound Settings: ReplayGain (album-level, from tags) + manual preamp ---

    /** User-adjustable trim applied on top of the album ReplayGain, in dB (0 = no change). */
    var preampDb by mutableStateOf(prefs.getFloat(KEY_PREAMP_DB, 0f))

    fun updatePreampDb(db: Float) {
        val clamped = db.coerceIn(-15f, 15f)
        preampDb = clamped
        prefs.edit().putFloat(KEY_PREAMP_DB, clamped).apply()
        applyGain()
    }

    /** Album ReplayGain of the current track (dB), or null if untagged. */
    private var albumReplayGainDb: Double? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    /**
     * Applies album ReplayGain (0 dB if the track has no such tag) plus the
     * user's preamp. Attenuation (<=0 dB total) is done via the player's
     * own volume control; boosting beyond unity gain needs amplification
     * that plain volume can't provide (its range is capped at 1.0 / 0 dB),
     * so positive totals use a [LoudnessEnhancer] attached to the player's
     * audio session instead -- the standard Android mechanism for this.
     */
    private fun applyGain() {
        val player = exoPlayer ?: return
        val totalDb = (albumReplayGainDb ?: 0.0) + preampDb
        if (totalDb <= 0.0) {
            loudnessEnhancer?.enabled = false
            player.volume = 10.0.pow(totalDb / 20.0).toFloat().coerceIn(0f, 1f)
        } else {
            player.volume = 1f
            val milliBels = (totalDb * 100).toInt().coerceIn(0, 2000) // cap boost at +20 dB
            ensureLoudnessEnhancer(player)?.apply {
                setTargetGain(milliBels)
                enabled = true
            }
        }
    }

    private fun ensureLoudnessEnhancer(player: ExoPlayer): LoudnessEnhancer? {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return null
        if (sessionId != loudnessEnhancerSessionId || loudnessEnhancer == null) {
            loudnessEnhancer?.release()
            loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
            loudnessEnhancerSessionId = sessionId
        }
        return loudnessEnhancer
    }

    /** Reads the album ReplayGain tag (ID3 TXXX or Vorbis comment) if present. */
    private fun extractAlbumReplayGainDb(format: Format): Double? {
        val metadata = format.metadata ?: return null
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            val raw = when {
                entry is TextInformationFrame && entry.id == "TXXX" &&
                    entry.description?.equals("REPLAYGAIN_ALBUM_GAIN", ignoreCase = true) == true ->
                    entry.values.firstOrNull()
                entry is VorbisComment && entry.key.equals("REPLAYGAIN_ALBUM_GAIN", ignoreCase = true) ->
                    entry.value
                else -> null
            }
            if (raw != null) {
                return raw.trim().removeSuffix("dB").removeSuffix("DB").trim().toDoubleOrNull()
            }
        }
        return null
    }

    // --- Playback (survives navigation; drives the mini-player + full screen) ---

    var playbackPlaylist by mutableStateOf<List<WebDavResource>>(emptyList())
    var playbackIndex by mutableStateOf(0)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var playerError by mutableStateOf<String?>(null)
    var tagTitle by mutableStateOf<String?>(null)
    var tagAlbum by mutableStateOf<String?>(null)
    var artworkBitmap by mutableStateOf<Bitmap?>(null)
    private var sampleRateHz by mutableStateOf<Int?>(null)
    private var bitrateKbps by mutableStateOf<Int?>(null)

    /** Current playback position / track duration, polled for the seek bar. */
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    private val positionTicker = Handler(Looper.getMainLooper())
    private var positionTickerRunning = false
    private val positionTickerRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                positionMs = player.currentPosition.coerceAtLeast(0)
                val d = player.duration
                durationMs = if (d != C.TIME_UNSET && d > 0) d else 0L
            }
            if (positionTickerRunning) positionTicker.postDelayed(this, 500)
        }
    }

    private fun startPositionTicker() {
        if (positionTickerRunning) return
        positionTickerRunning = true
        positionTicker.post(positionTickerRunnable)
    }

    private fun stopPositionTicker() {
        positionTickerRunning = false
        positionTicker.removeCallbacks(positionTickerRunnable)
    }

    /** User-initiated seek (drag on the progress bar). */
    fun seekTo(ms: Long) {
        exoPlayer?.seekTo(ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: ms))
        positionMs = ms
    }

    val hasActiveTrack: Boolean get() = playbackPlaylist.isNotEmpty()
    val currentTrack: WebDavResource? get() = playbackPlaylist.getOrNull(playbackIndex)
    val canGoPrevTrack: Boolean get() = playbackIndex > 0
    val canGoNextTrack: Boolean get() = playbackIndex < playbackPlaylist.lastIndex

    /** "ext · sampleRate · bitrate", e.g. "mp3 · 44100 Hz · 128 kbps". */
    fun techInfoText(): String? {
        val ext = currentTrack?.displayName?.substringAfterLast('.', "")
            ?.lowercase()?.takeIf { it.isNotBlank() }
        val sr = sampleRateHz?.let { "$it Hz" }
        val br = bitrateKbps?.let { "$it kbps" }
        return listOfNotNull(ext, sr, br).joinToString(" · ").ifBlank { null }
    }

    private var exoPlayer: ExoPlayer? = null
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null
    private var prefetchWriter: CacheWriter? = null

    // --- Resilience: survive brief dropouts (metro tunnels, lifts, ...) ---

    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retryAttempt = 0
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerConnectivityCallback()
    }

    // --- Background playback: hand the ExoPlayer instance to PlaybackService
    // (a MediaSessionService) so Android treats this as an active media
    // session and doesn't throttle/kill the app once it's backgrounded. ---

    private var playbackService: PlaybackService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? PlaybackService.LocalBinder)?.getService() ?: return
            playbackService = service
            exoPlayer?.let { service.setPlayer(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    private fun bindPlaybackService() {
        val context = getApplication<Application>()
        val intent = Intent(context, PlaybackService::class.java)
        if (!serviceBound) {
            context.startService(intent)
            serviceBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindPlaybackService() {
        playbackService?.clearPlayer()
        if (serviceBound) {
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
            serviceBound = false
        }
        playbackService = null
        getApplication<Application>().stopService(Intent(getApplication(), PlaybackService::class.java))
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallback != null) return
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Fires on a background ConnectivityThread; Compose state
                // must only be read/written on the main thread, so hop
                // over before touching playerError/resumeAfterError().
                retryHandler.post {
                    if (playerError != null) {
                        resumeAfterError()
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        connectivityCallback = callback
    }

    private fun unregisterConnectivityCallback() {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        connectivityCallback = null
    }

    /** True for transient network errors worth retrying (timeouts, DNS, 5xx, ...). */
    private fun isRetryableNetworkError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode >= 500
            }
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    private fun scheduleRetry() {
        retryAttempt += 1
        val delayMs = min(1000L shl min(retryAttempt, 4), 15_000L)
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        val runnable = Runnable { resumeAfterError() }
        retryRunnable = runnable
        retryHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduledRetry() {
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun resumeAfterError() {
        cancelScheduledRetry()
        val player = exoPlayer ?: return
        if (playerError == null) return // already recovered (e.g. by the other trigger)
        playerError = null
        player.prepare()
        player.play()
    }

    /** The live player instance, if playback has started (for PlayerView binding). */
    fun getPlayerOrNull(): ExoPlayer? = exoPlayer

    private fun ensurePlayer(): ExoPlayer? {
        exoPlayer?.let { return it }
        val c = client() ?: return null
        val context = getApplication<Application>()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to c.authHeader()))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val cdsFactory = CacheDataSource.Factory()
            .setCache(getOrCreateCache())
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        cacheDataSourceFactory = cdsFactory

        // Buffer generously ahead so a few seconds without signal (a metro
        // tunnel, a lift) is absorbed by the buffer and never even reaches
        // the listener as a stall.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 120_000, 2_500, 5_000)
            .build()

        // Keep retrying transient network errors (timeouts, connection
        // resets, 5xx) with a capped backoff instead of giving up after a
        // couple of attempts; genuine client errors (401/403/404) are not
        // retried so a bad URL/credentials doesn't spin forever.
        val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val exception = loadErrorInfo.exception
                if (exception is HttpDataSource.InvalidResponseCodeException &&
                    exception.responseCode in 400..499
                ) {
                    return C.TIME_UNSET // permanent error -- don't hammer it
                }
                return min(1000L shl min(loadErrorInfo.errorCount, 4), 8_000L)
            }

            override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cdsFactory)
        mediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        // Hold a CPU wake lock + WiFi lock for as long as playback is
        // active, so a Doze-triggered CPU sleep (screen off, stationary)
        // can't stall decoding/network I/O between buffer refills.
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
                if (isRetryableNetworkError(error)) {
                    scheduleRetry()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    retryAttempt = 0
                }
                if (playbackState == Player.STATE_ENDED && canGoNextTrack) {
                    nextTrack()
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                tagTitle = mediaMetadata.title?.toString()
                tagAlbum = mediaMetadata.albumTitle?.toString()
                val data = mediaMetadata.artworkData
                artworkBitmap = if (data != null) {
                    runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
                } else {
                    null
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val format = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                    ?.let { group ->
                        (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                            ?.let { group.getTrackFormat(it) }
                    }
                sampleRateHz = format?.sampleRate?.takeIf { it > 0 }
                bitrateKbps = format?.bitrate?.takeIf { it > 0 }?.let { it / 1000 }
                albumReplayGainDb = format?.let { extractAlbumReplayGainDb(it) }
                applyGain()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyGain()
            }
        })
        exoPlayer = player
        bindPlaybackService()
        playbackService?.setPlayer(player)
        startPositionTicker()
        return player
    }

    private fun loadCurrentTrack() {
        val c = client() ?: return
        val resource = currentTrack ?: return
        val player = ensurePlayer() ?: return

        cancelScheduledRetry()
        retryAttempt = 0
        playerError = null
        tagTitle = null
        tagAlbum = null
        artworkBitmap = null
        sampleRateHz = null
        bitrateKbps = null
        albumReplayGainDb = null
        positionMs = 0L
        durationMs = 0L
        prefetchWriter?.cancel()
        prefetchWriter = null

        player.setMediaItem(MediaItem.fromUri(c.resolve(resource.href)))
        player.prepare()
        player.playWhenReady = true
        applyGain()

        if (playbackPlaylist.size > 1) {
            val next = playbackPlaylist.getOrNull(playbackIndex + 1)
            val factory = cacheDataSourceFactory
            if (next != null && factory != null) {
                val dataSource = factory.createDataSource()
                val dataSpec = DataSpec(Uri.parse(c.resolve(next.href)))
                val writer = CacheWriter(dataSource, dataSpec, null, null)
                prefetchWriter = writer
                Thread {
                    try {
                        writer.cache()
                    } catch (e: Exception) {
                        // Best-effort pre-fetch; normal playback fetches if this
                        // didn't finish in time.
                    }
                }.start()
            }
        }
    }

    private fun startPlayback(queue: List<WebDavResource>, index: Int) {
        if (queue.isEmpty()) return
        playbackPlaylist = queue
        playbackIndex = index.coerceIn(0, queue.lastIndex)
        loadCurrentTrack()
    }

    /**
     * Starts playback at [resource] and continues sequentially through the
     * rest of the currently displayed folder (in the same order shown),
     * stopping after the last file -- no looping back to the start. If
     * [resource] happens to be the last file, only it plays.
     */
    fun playFromFile(resource: WebDavResource) {
        val files = entries.filter { !it.isDirectory }
        val index = files.indexOf(resource)
        if (index < 0) return
        startPlayback(files, index)
    }

    fun togglePlayPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun stopToBeginning() {
        exoPlayer?.pause()
        exoPlayer?.seekTo(0)
    }

    fun nextTrack() {
        if (canGoNextTrack) {
            playbackIndex += 1
            loadCurrentTrack()
        }
    }

    fun prevTrack() {
        if (canGoPrevTrack) {
            playbackIndex -= 1
            loadCurrentTrack()
        }
    }

    /** Fully stops and tears down playback (used when entering Settings). */
    fun stopAndClearPlayback() {
        cancelScheduledRetry()
        stopPositionTicker()
        prefetchWriter?.cancel()
        prefetchWriter = null
        unbindPlaybackService()
        exoPlayer?.run {
            stop()
            release()
        }
        exoPlayer = null
        cacheDataSourceFactory = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        loudnessEnhancerSessionId = C.AUDIO_SESSION_ID_UNSET
        playbackPlaylist = emptyList()
        playbackIndex = 0
        isPlaying = false
        isBuffering = false
        playerError = null
        tagTitle = null
        tagAlbum = null
        artworkBitmap = null
        sampleRateHz = null
        bitrateKbps = null
        albumReplayGainDb = null
    }

    override fun onCleared() {
        super.onCleared()
        unregisterConnectivityCallback()
        cancelScheduledRetry()
        stopPositionTicker()
        unbindPlaybackService()
        exoPlayer?.release()
        mediaCache?.release()
        loudnessEnhancer?.release()
    }

    // --- Directory listing / navigation --------------------------------

    private fun mediaOnly(list: List<WebDavResource>): List<WebDavResource> =
        list.filter { it.isDirectory || it.isPlayable() }

    /**
     * WebDAV hrefs are percent-encoded (e.g. Cyrillic folder names come
     * back as "%D0%9C%D1%83..."). [currentPath] is display-only (actual
     * requests use the raw href), so decode it for a readable title.
     * '+' is escaped first because URLDecoder treats it as a literal
     * space (form-encoding behavior), which a URL path does not want.
     */
    private fun decodeForDisplay(path: String): String =
        runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

    fun connect(serverUrl: String, username: String, password: String) {
        connectionState = ConnectionState.Connecting
        val client = WebDavClient(serverUrl, username, password)
        viewModelScope.launch {
            try {
                // List the user-configured URL itself (which may include a
                // sub-path like ".../music"), not a hardcoded "/" -- the
                // configured sub-path IS the root of this session.
                val list = withContext(Dispatchers.IO) { client.listDirectory(serverUrl) }
                rootUrl = serverUrl
                connectionState = ConnectionState.Connected(client)
                pathStack = emptyList()
                currentHref = null
                currentPath = decodeForDisplay(client.rootPath())
                entries = mediaOnly(list)
                saveServerSettings(serverUrl, username, password)
            } catch (e: Exception) {
                connectionState = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun openDirectory(resource: WebDavResource) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        isLoading = true
        errorMessage = null
        infoMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { state.client.listDirectory(resource.href) }
                pathStack = pathStack + currentHref
                currentHref = resource.href
                currentPath = decodeForDisplay(resource.href)
                entries = mediaOnly(list)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            } finally {
                isLoading = false
            }
        }
    }

    /** Goes back to the parent of the currently displayed folder. */
    fun goUp() {
        val state = connectionState
        if (state !is ConnectionState.Connected || pathStack.isEmpty()) return
        val parentHref = pathStack.last()
        val newStack = pathStack.dropLast(1)
        isLoading = true
        errorMessage = null
        infoMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    state.client.listDirectory(parentHref ?: rootUrl)
                }
                pathStack = newStack
                currentHref = parentHref
                currentPath = decodeForDisplay(parentHref ?: state.client.rootPath())
                entries = mediaOnly(list)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            } finally {
                isLoading = false
            }
        }
    }

    // --- Downloads (SAF: user picks the destination) -------------------

    /** Downloads a single file to [destUri] (from ACTION_CREATE_DOCUMENT). */
    fun downloadFile(resource: WebDavResource, destUri: Uri) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    state.client.openDownloadStream(resource.href).use { input ->
                        val output = getApplication<Application>().contentResolver.openOutputStream(destUri)
                            ?: throw IllegalStateException("Cannot open destination")
                        output.use { input.copyTo(it) }
                    }
                }
                infoMessage = "Скачано: ${resource.displayName}"
            } catch (e: Exception) {
                errorMessage = "Ошибка скачивания «${resource.displayName}»: ${e.message}"
            }
        }
    }

    /**
     * Downloads every directly-contained playable file of [resource] (a
     * folder) into [treeUri] (from ACTION_OPEN_DOCUMENT_TREE). Nested
     * sub-folders are not descended into.
     */
    fun downloadFolder(resource: WebDavResource, treeUri: Uri) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    state.client.listDirectory(resource.href).filter { !it.isDirectory && it.isPlayable() }
                }
                if (files.isEmpty()) {
                    infoMessage = "Отсутствуют медиафайлы"
                    return@launch
                }
                val app = getApplication<Application>()
                val treeDoc = DocumentFile.fromTreeUri(app, treeUri)
                    ?: throw IllegalStateException("Cannot open destination folder")
                var okCount = 0
                withContext(Dispatchers.IO) {
                    for (file in files) {
                        val downloaded = try {
                            val mime = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(file.displayName.substringAfterLast('.', ""))
                                ?: "application/octet-stream"
                            val target = treeDoc.createFile(mime, file.displayName)
                            if (target != null) {
                                state.client.openDownloadStream(file.href).use { input ->
                                    val output = app.contentResolver.openOutputStream(target.uri)
                                    if (output != null) {
                                        output.use { input.copyTo(it) }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                false
                            }
                        } catch (e: Exception) {
                            // Best-effort: skip this file, keep going with the rest.
                            false
                        }
                        if (downloaded) okCount++
                    }
                }
                infoMessage = "Скачано файлов: $okCount из ${files.size}"
            } catch (e: Exception) {
                errorMessage = "Ошибка скачивания папки: ${e.message}"
            }
        }
    }

    fun disconnect() {
        stopAndClearPlayback()
        connectionState = ConnectionState.Disconnected
        entries = emptyList()
        currentPath = "/"
        currentHref = null
        pathStack = emptyList()
    }

    private fun saveServerSettings(serverUrl: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_URL, serverUrl)
            .putString(KEY_USER, username)
            .putString(KEY_PASS, password)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "webdav_settings"
        private const val KEY_URL = "server_url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_CACHE_MB = "cache_size_mb"
        private const val KEY_PREAMP_DB = "preamp_db"
        const val DEFAULT_CACHE_MB = 256
    }
}
