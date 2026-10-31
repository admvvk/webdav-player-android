package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.admvvk.webdavplayer.ConnectionState

/**
 * Settings screen #1: reachable ONLY via the gear on the Home screen.
 * Holds just the WebDAV server connection fields (URL/user/password).
 * Everything else lives on [SettingsOtherScreen] instead, which is the
 * only settings screen reachable once you're past Home (file list,
 * player) -- changing the server mid-session is out of scope there by
 * design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    connectionState: ConnectionState,
    initialServerUrl: String,
    initialUsername: String,
    initialPassword: String,
    onBack: () -> Unit,
    onConnect: (serverUrl: String, username: String, password: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Connection Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            ConnectScreen(
                connectionState = connectionState,
                initialServerUrl = initialServerUrl,
                initialUsername = initialUsername,
                initialPassword = initialPassword,
                onConnect = onConnect,
            )
        }
    }
}
