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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.admvvk.webdavplayer.AppViewModel
import com.admvvk.webdavplayer.R

/**
 * Full-size player UI. Playback itself (the ExoPlayer instance and its
 * state) lives in [viewModel] so it survives "Exit to list" and back --
 * this screen is just a view onto that state plus Prev/Play-Pause/Stop/
 * Next/Exit controls.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun PlayerScreen(
    viewModel: AppViewModel,
    onExit: () -> Unit,
) {
    val context = LocalContext.current

    // If somehow reached with nothing playing (e.g. process restored into
    // a stale back stack), just bail out instead of showing an empty shell.
    LaunchedEffect(viewModel.hasActiveTrack) {
        if (!viewModel.hasActiveTrack) onExit()
    }

    val current = viewModel.currentTrack
    val displayTitle = viewModel.tagTitle?.takeIf { it.isNotBlank() } ?: current?.displayName.orEmpty()
    val album = viewModel.tagAlbum
    val tech = viewModel.techInfoText()
    val error = viewModel.playerError
    val playerView = remember { PlayerView(context) }

    DisposableEffect(Unit) {
        playerView.useController = false
        playerView.defaultArtwork = ContextCompat.getDrawable(context, R.drawable.ic_music_placeholder)
        playerView.player = viewModel.getPlayerOrNull()
        onDispose {
            // The ExoPlayer instance is owned by the ViewModel and keeps
            // playing after we leave -- only detach this View from it so
            // the View isn't kept alive by the player's listener list.
            playerView.player = null
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!album.isNullOrBlank()) {
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { playerView })
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                }
            }

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
                    IconButton(onClick = viewModel::prevTrack, enabled = viewModel.canGoPrevTrack) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Prev")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = viewModel::togglePlayPause) {
                        Icon(
                            if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = viewModel::stopToBeginning) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = viewModel::nextTrack, enabled = viewModel.canGoNextTrack) {
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
