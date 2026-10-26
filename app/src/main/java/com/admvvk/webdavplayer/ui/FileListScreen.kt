package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.admvvk.webdavplayer.webdav.WebDavResource

private fun isPlayable(resource: WebDavResource): Boolean {
    val type = resource.contentType?.lowercase().orEmpty()
    val name = resource.displayName.lowercase()
    val videoExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".m3u8")
    val audioExt = listOf(".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a")
    return type.startsWith("video/") || type.startsWith("audio/") ||
        videoExt.any { name.endsWith(it) } || audioExt.any { name.endsWith(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    currentPath: String,
    entries: List<WebDavResource>,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenDirectory: (WebDavResource) -> Unit,
    onPlayFile: (WebDavResource) -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPath) },
                actions = {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // IMPORTANT: draw the list first so the loading indicator and
            // (especially) the error banner below are painted *on top* of
            // it. They used to be declared before the LazyColumn, which in
            // Compose means the LazyColumn painted over them -- an error
            // from a failed openDirectory()/tap was silently hidden behind
            // the still-visible old file list, looking like "nothing
            // happened" when a folder tap actually failed.
            LazyColumn {
                items(entries) { resource ->
                    ListItem(
                        headlineContent = { Text(resource.displayName) },
                        supportingContent = {
                            if (!resource.isDirectory) {
                                Text(formatSize(resource.contentLength))
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = if (resource.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            if (resource.isDirectory) {
                                onOpenDirectory(resource)
                            } else if (isPlayable(resource)) {
                                onPlayFile(resource)
                            }
                        },
                    )
                    Divider()
                }
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
            }
            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopCenter)
                        .fillMaxWidth(),
                ) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}
