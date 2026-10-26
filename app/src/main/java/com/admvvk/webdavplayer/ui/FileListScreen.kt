package com.admvvk.webdavplayer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.admvvk.webdavplayer.AppViewModel
import com.admvvk.webdavplayer.webdav.WebDavResource
import com.admvvk.webdavplayer.webdav.isPlayable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    currentPath: String,
    canGoUp: Boolean,
    entries: List<WebDavResource>,
    isLoading: Boolean,
    errorMessage: String?,
    infoMessage: String?,
    onErrorShown: () -> Unit,
    onInfoShown: () -> Unit,
    onGoUp: () -> Unit,
    onOpenDirectory: (WebDavResource) -> Unit,
    onPlayFile: (WebDavResource) -> Unit,
    onPlayFolder: (WebDavResource) -> Unit,
    onDownloadFile: (WebDavResource, Uri) -> Unit,
    onDownloadFolder: (WebDavResource, Uri) -> Unit,
    onOpenSettings: () -> Unit,
    playerViewModel: AppViewModel,
    onExpandPlayer: () -> Unit,
) {
    // Which folder's long-press context menu ("Воспроизвести папку") is open.
    var menuForHref by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // One shared SAF launcher per download kind; the pending resource is
    // stashed here because the launcher callback doesn't get to choose
    // its own argument -- it only gets the picked Uri (or null).
    var pendingFileDownload by remember { mutableStateOf<WebDavResource?>(null) }
    var pendingFolderDownload by remember { mutableStateOf<WebDavResource?>(null) }

    val createFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val resource = pendingFileDownload
        pendingFileDownload = null
        if (uri != null && resource != null) onDownloadFile(resource, uri)
    }
    val openTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val resource = pendingFolderDownload
        pendingFolderDownload = null
        if (uri != null && resource != null) onDownloadFolder(resource, uri)
    }

    // Messages are shown as transient Snackbars instead of a permanent
    // banner: a banner painted *under* the list is invisible (the bug that
    // made failed folder taps look like "nothing happened"), and one
    // painted *over* the list permanently hides whatever row is underneath
    // it. A Snackbar floats above everything and auto-dismisses.
    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onErrorShown()
    }
    LaunchedEffect(infoMessage) {
        val message = infoMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onInfoShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                navigationIcon = {
                    if (canGoUp) {
                        IconButton(onClick = onGoUp) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one level")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (playerViewModel.hasActiveTrack) {
                MiniPlayerBar(playerViewModel, onExpand = onExpandPlayer)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
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
                            trailingContent = {
                                IconButton(onClick = {
                                    if (resource.isDirectory) {
                                        pendingFolderDownload = resource
                                        openTreeLauncher.launch(null)
                                    } else {
                                        pendingFileDownload = resource
                                        createFileLauncher.launch(resource.displayName)
                                    }
                                }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download")
                                }
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
