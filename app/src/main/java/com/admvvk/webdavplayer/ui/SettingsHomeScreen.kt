package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable

/** Hub reached via the gear icon: pick which settings to edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onBack: () -> Unit,
    onServerSettings: () -> Unit,
    onConnectionSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text("Server Connection Settings") },
                    supportingContent = { Text("WebDAV server URL, username, password") },
                    leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onServerSettings),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Connection Settings") },
                    supportingContent = { Text("Next-track pre-caching for folder playback") },
                    leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onConnectionSettings),
                )
            }
        }
    }
}
