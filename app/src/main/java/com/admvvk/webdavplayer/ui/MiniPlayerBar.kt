package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.admvvk.webdavplayer.AppViewModel

/**
 * Compact playback bar shown at the bottom of the file list while a track
 * is loaded, so "Exit to list" doesn't have to stop playback. Tapping the
 * small artwork/placeholder image re-opens the full-size player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayerBar(viewModel: AppViewModel, onExpand: () -> Unit) {
    val current = viewModel.currentTrack ?: return
    val title = viewModel.tagTitle?.takeIf { it.isNotBlank() } ?: current.displayName
    val label = listOfNotNull(viewModel.tagAlbum?.takeIf { it.isNotBlank() }, title)
        .joinToString("  —  ")
    val artwork = viewModel.artworkBitmap

    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, velocity = 20.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onExpand),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artwork != null) {
                        Image(
                            bitmap = artwork.asImageBitmap(),
                            contentDescription = "Open player",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = "Open player",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = viewModel::prevTrack, enabled = viewModel.canGoPrevTrack) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Prev")
                }
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                    )
                }
                IconButton(onClick = viewModel::stopToBeginning) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
                IconButton(onClick = viewModel::nextTrack, enabled = viewModel.canGoNextTrack) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}
