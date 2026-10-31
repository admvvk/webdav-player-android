package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings screen #2: reachable via the gear from every screen except
 * Home (file list, player). Everything EXCEPT the server connection
 * fields, which live on Home's own [ServerSettingsScreen] instead.
 * Opening this never touches playback -- the caller's active ExoPlayer
 * instance (if any) is left completely alone, and the back arrow simply
 * pops back to whichever screen opened it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsOtherScreen(
    onBack: () -> Unit,
    onConnectionSettings: () -> Unit,
    onSoundSettings: () -> Unit,
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
                    headlineContent = { Text("Connection Settings") },
                    supportingContent = { Text("Next-track pre-caching for folder playback") },
                    leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onConnectionSettings),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Sound Settings") },
                    supportingContent = { Text("Album ReplayGain + manual preamp (dB)") },
                    leadingContent = { Icon(Icons.Filled.VolumeUp, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onSoundSettings),
                )
            }
        }
    }
}
