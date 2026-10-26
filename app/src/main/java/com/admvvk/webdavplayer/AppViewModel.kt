package com.admvvk.webdavplayer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.admvvk.webdavplayer.webdav.WebDavClient
import com.admvvk.webdavplayer.webdav.WebDavResource
import com.admvvk.webdavplayer.webdav.isPlayable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        // player currently holds a DataSource backed by the old instance.
        mediaCache?.release()
        mediaCache = null
    }

    // --- Playback (survives navigation; drives the mini-player + full screen) ---

    var playbackPlaylist by mutableStateOf<List<WebDavResource>>(emptyList())
    var playbackIndex by mutableStateOf(0)
    var isPlaying by mutableStateOf(false)
    var playerError by mutableStateOf<String?>(null)
    var tagTitle by mutableStateOf<String?>(null)
    var tagAlbum by mutableStateOf<String?>(null)
    var artworkBitmap by mutableStateOf<Bitmap?>(null)
    private var sampleRateHz by mutableStateOf<Int?>(null)
    private var bitrateKbps by mutableStateOf<Int?>(null)

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

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(cdsFactory))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
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
            }
        })
        exoPlayer = player
        return player
    }

    private fun loadCurrentTrack() {
        val c = client() ?: return
        val resource = currentTrack ?: return
        val player = ensurePlayer() ?: return

        playerError = null
        tagTitle = null
        tagAlbum = null
        artworkBitmap = null
        sampleRateHz = null
        bitrateKbps = null
        prefetchWriter?.cancel()
        prefetchWriter = null

        player.setMediaItem(MediaItem.fromUri(c.resolve(resource.href)))
        player.prepare()
        player.playWhenReady = true

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

    /** Starts a single-file playback queue immediately (no network needed). */
    fun playSingleFile(resource: WebDavResource) {
        startPlayback(listOf(resource), 0)
    }

    /**
     * Lists [resource] (a folder) non-recursively, keeps only directly
     * playable media files (nested sub-folders are NOT included), starts
     * playback, and invokes [onStarted] once that happens -- it is only
     * called when at least one playable file was found; otherwise
     * [infoMessage] ("Отсутствуют медиафайлы") or [errorMessage] is set.
     */
    fun playFolder(resource: WebDavResource, onStarted: () -> Unit) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        isLoading = true
        errorMessage = null
        infoMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { state.client.listDirectory(resource.href) }
                val playableFiles = list.filter { !it.isDirectory && it.isPlayable() }
                if (playableFiles.isEmpty()) {
                    infoMessage = "Отсутствуют медиафайлы"
                } else {
                    startPlayback(playableFiles, 0)
                    onStarted()
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            } finally {
                isLoading = false
            }
        }
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
        prefetchWriter?.cancel()
        prefetchWriter = null
        exoPlayer?.run {
            stop()
            release()
        }
        exoPlayer = null
        cacheDataSourceFactory = null
        playbackPlaylist = emptyList()
        playbackIndex = 0
        isPlaying = false
        playerError = null
        tagTitle = null
        tagAlbum = null
        artworkBitmap = null
        sampleRateHz = null
        bitrateKbps = null
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        mediaCache?.release()
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
     * sub-folders are not descended into, matching "play folder".
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
        const val DEFAULT_CACHE_MB = 256
    }
}
