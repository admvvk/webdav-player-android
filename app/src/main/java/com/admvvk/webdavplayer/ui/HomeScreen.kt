package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Landing screen: a bit of decoration, one big "Open player" action, and a
 * gear icon in the corner for server connection settings (kept off the
 * first screen on purpose, per the "settings aren't the first thing you
 * see" request -- more settings can land next to it in future releases).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isConnecting: Boolean,
    errorMessage: String?,
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV Player") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Server Connection Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onOpenPlayer,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(0.8f),
            ) {
                Text(if (isConnecting) "Подключение..." else "Открыть плеер")
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
