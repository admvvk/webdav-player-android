package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val MIN_DB = -15f
private const val MAX_DB = 15f

/**
 * "Sound Settings": album-level ReplayGain is applied automatically from
 * the file's own tag (no tag = no change); this screen only controls an
 * extra manual trim (preamp) on top of that, in dB, centered on 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsScreen(
    currentPreampDb: Float,
    onBack: () -> Unit,
    onSave: (Float) -> Unit,
) {
    var value by remember { mutableStateOf(currentPreampDb) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sound Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text(
                "ReplayGain по альбому применяется автоматически из тега файла " +
                    "(если тега нет — громкость не меняется). Регулятор ниже — " +
                    "дополнительная подстройка (preamp) сверху этого значения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            Text(
                text = "%+.1f дБ".format(value),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Slider(
                value = value,
                onValueChange = { value = it },
                valueRange = MIN_DB..MAX_DB,
                steps = ((MAX_DB - MIN_DB) * 2 - 1).toInt(), // 0.5 dB increments
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${MIN_DB.toInt()} дБ", style = MaterialTheme.typography.labelSmall)
                Text("0", style = MaterialTheme.typography.labelSmall)
                Text("+${MAX_DB.toInt()} дБ", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(32.dp))
            Button(onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить")
            }
        }
    }
}
