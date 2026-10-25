package com.admvvk.webdavplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.admvvk.webdavplayer.webdav.WebDavClient
import com.admvvk.webdavplayer.webdav.WebDavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val client: WebDavClient) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class AppViewModel : ViewModel() {
    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    var currentPath by mutableStateOf("/")
    var entries by mutableStateOf<List<WebDavResource>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    fun connect(serverUrl: String, username: String, password: String) {
        connectionState = ConnectionState.Connecting
        val client = WebDavClient(serverUrl, username, password)
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.listDirectory("/") }
                connectionState = ConnectionState.Connected(client)
                currentPath = "/"
                entries = list
            } catch (e: Exception) {
                connectionState = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun openDirectory(resource: WebDavResource) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { state.client.listDirectory(resource.href) }
                currentPath = resource.href
                entries = list
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            } finally {
                isLoading = false
            }
        }
    }

    fun disconnect() {
        connectionState = ConnectionState.Disconnected
        entries = emptyList()
        currentPath = "/"
    }
}
