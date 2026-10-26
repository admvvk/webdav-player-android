package com.admvvk.webdavplayer

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
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

/**
 * Remembers the last successfully used WebDAV server settings in plain
 * SharedPreferences, so the user does not have to retype them every launch.
 * NOTE: the password is stored in plaintext in the app's private storage
 * (not encrypted) -- fine for a personal/test client, not for a hardened app.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    var currentPath by mutableStateOf("/")
    var entries by mutableStateOf<List<WebDavResource>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    val savedServerUrl: String get() = prefs.getString(KEY_URL, "") ?: ""
    val savedUsername: String get() = prefs.getString(KEY_USER, "") ?: ""
    val savedPassword: String get() = prefs.getString(KEY_PASS, "") ?: ""

    fun connect(serverUrl: String, username: String, password: String) {
        connectionState = ConnectionState.Connecting
        val client = WebDavClient(serverUrl, username, password)
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.listDirectory("/") }
                connectionState = ConnectionState.Connected(client)
                currentPath = "/"
                entries = list
                saveServerSettings(serverUrl, username, password)
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

    private fun saveServerSettings(serverUrl: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_URL, serverUrl)
            .putString(KEY_USER, username)
            .putString(KEY_PASS, password)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "webdav_settings"
        private const val KEY_URL = "server_url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
    }
}
