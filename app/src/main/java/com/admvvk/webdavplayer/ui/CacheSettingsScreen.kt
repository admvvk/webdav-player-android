package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * "Connection Settings": controls how much local storage is used to
 * pre-cache the *next* track while playing a folder queue, so a dip into a
 * weak-signal spot doesn't stall playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    currentCacheSizeMb: Int,
    onBack: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(currentCacheSizeMb.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                "При воспроизведении из папки следующий трек заранее " +
                    "кешируется на устройстве, чтобы временное падение скорости " +
                    "связи не прерывало музыку.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { input -> if (input.all { it.isDigit() }) text = input },
                label = { Text("Объём кеша (МБ)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { text.toIntOrNull()?.let(onSave) },
                enabled = text.toIntOrNull() != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сохранить")
            }
        }
    }
}
