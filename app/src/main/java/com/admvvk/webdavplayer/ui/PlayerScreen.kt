package com.admvvk.webdavplayer.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.admvvk.webdavplayer.R
import com.admvvk.webdavplayer.webdav.WebDavResource

/**
 * Plays a queue of media resources (one file, or a whole folder's worth)
 * from the WebDAV server using Media3/ExoPlayer, with Prev/Play-Pause/
 * Stop/Next/Exit controls. Shows tag data (title/album) instead of the
 * raw filename, and technical format info below the artwork.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun PlayerScreen(
    playlist: List<WebDavResource>,
    startIndex: Int,
    resolveUrl: (String) -> String,
    authHeader: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current

    var index by remember { mutableStateOf(startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))) }
    var isPlaying by remember { mutableStateOf(true) }
    // Surfaced instead of letting the player sit silently "stuck" on a
    // network/decoder error with no feedback to the user.
    var playerError by remember { mutableStateOf<String?>(null) }

    // Tag data read from the file itself (ID3/FLAC/OGG comments etc, parsed
    // by Media3), falling back to the filename when a track has no title
    // tag. Technical info (codec/sample rate/bitrate) comes from the
    // selected audio track's Format once the player has started decoding.
    var tagTitle by remember { mutableStateOf<String?>(null) }
    var tagAlbum by remember { mutableStateOf<String?>(null) }
    var techInfo by remember { mutableStateOf<String?>(null) }

    val player = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to authHeader))
            // Be generous with slow/large files (e.g. big FLAC/WAV over a
            // slow link) instead of timing out and looking "stuck".
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playerError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Auto-advance when playing a folder queue.
                        if (playbackState == Player.STATE_ENDED && index < playlist.lastIndex) {
                            index += 1
                        }
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        tagTitle = mediaMetadata.title?.toString()
                        tagAlbum = mediaMetadata.albumTitle?.toString()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        val format = tracks.groups
                            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                            ?.let { group ->
                                (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                                    ?.let { group.getTrackFormat(it) }
                            }
                        techInfo = format?.let { fmt ->
                            val codec = fmt.sampleMimeType?.removePrefix("audio/")?.uppercase()
                            val sampleRate = if (fmt.sampleRate > 0) "${fmt.sampleRate} Hz" else null
                            val bitrate = if (fmt.bitrate > 0) "${fmt.bitrate / 1000} kbps" else null
                            listOfNotNull(codec, sampleRate, bitrate)
                                .joinToString(" · ")
                                .ifBlank { null }
                        }
                    }
                })
            }
    }

    // (Re)load the media item whenever the queue position changes, instead
    // of recreating the player (keeps the control bar/listener stable).
    LaunchedEffect(index) {
        playerError = null
        tagTitle = null
        tagAlbum = null
        techInfo = null
        val resource = playlist.getOrNull(index) ?: return@LaunchedEffect
        val url = resolveUrl(resource.href)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val current = playlist.getOrNull(index)
    val displayTitle = tagTitle?.takeIf { it.isNotBlank() } ?: current?.displayName.orEmpty()

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val album = tagAlbum
                if (!album.isNullOrBlank()) {
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        PlayerView(context).apply {
                            this.player = player
                            this.useController = false
                            this.defaultArtwork = ContextCompat.getDrawable(context, R.drawable.ic_music_placeholder)
                        }
                    },
                )
                val error = playerError
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                }
            }

            val tech = techInfo
            if (!tech.isNullOrBlank()) {
                Text(
                    text = tech,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Surface {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    IconButton(onClick = { if (index > 0) index -= 1 }, enabled = index > 0) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Prev")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        player.pause()
                        player.seekTo(0)
                    }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { if (index < playlist.lastIndex) index += 1 },
                        enabled = index < playlist.lastIndex,
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onExit) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = "Exit to list")
                    }
                }
            }
        }
    }
}
