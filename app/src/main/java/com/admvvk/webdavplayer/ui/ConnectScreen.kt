package com.admvvk.webdavplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.admvvk.webdavplayer.ConnectionState

@Composable
fun ConnectScreen(
    connectionState: ConnectionState,
    initialServerUrl: String = "https://",
    initialUsername: String = "",
    initialPassword: String = "",
    onConnect: (serverUrl: String, username: String, password: String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("WebDAV Player", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("WebDAV server URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onConnect(serverUrl.trim(), username.trim(), password) },
            enabled = connectionState !is ConnectionState.Connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (connectionState is ConnectionState.Connecting) "Connecting..." else "Connect")
        }

        if (connectionState is ConnectionState.Error) {
            Spacer(Modifier.height(12.dp))
            Text(
                connectionState.message,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
