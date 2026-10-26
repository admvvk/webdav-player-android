package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.admvvk.webdavplayer.webdav.WebDavResource
import com.admvvk.webdavplayer.webdav.isPlayable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    currentPath: String,
    entries: List<WebDavResource>,
    isLoading: Boolean,
    errorMessage: String?,
    infoMessage: String?,
    onOpenDirectory: (WebDavResource) -> Unit,
    onPlayFile: (WebDavResource) -> Unit,
    onPlayFolder: (WebDavResource) -> Unit,
    onDisconnect: () -> Unit,
) {
    // Which folder's long-press context menu ("Воспроизвести папку") is open.
    var menuForHref by remember { mutableStateOf<String?>(null) }

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
            // (especially) the error/info banners below are painted *on
            // top* of it. They used to be declared before the LazyColumn,
            // which in Compose means the LazyColumn painted over them -- an
            // error from a failed openDirectory()/tap was silently hidden
            // behind the still-visible old file list, looking like
            // "nothing happened" when a folder tap actually failed.
            LazyColumn {
                items(entries) { resource ->
                    Box {
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
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (resource.isDirectory) {
                                        onOpenDirectory(resource)
                                    } else if (resource.isPlayable()) {
                                        onPlayFile(resource)
                                    }
                                },
                                onLongClick = {
                                    if (resource.isDirectory) {
                                        menuForHref = resource.href
                                    }
                                },
                            ),
                        )
                        if (resource.isDirectory && menuForHref == resource.href) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { menuForHref = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Воспроизвести папку") },
                                    onClick = {
                                        menuForHref = null
                                        onPlayFolder(resource)
                                    },
                                )
                            }
                        }
                    }
                    Divider()
                }
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (infoMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Text(
                        infoMessage,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
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
