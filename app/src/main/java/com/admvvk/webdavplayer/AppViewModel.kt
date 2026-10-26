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
import com.admvvk.webdavplayer.webdav.isPlayable
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

    /** Transient, non-error info banner (e.g. "no media files in this folder"). */
    var infoMessage by mutableStateOf<String?>(null)

    /** Current playback queue and position within it, consumed by PlayerScreen. */
    var playlist by mutableStateOf<List<WebDavResource>>(emptyList())
    var playlistIndex by mutableStateOf(0)

    // The configured root (whatever URL the user entered in settings, which
    // may itself point at a sub-folder like ".../music"). `currentHref` is
    // null while browsing that root; once the user opens a sub-folder it
    // holds the server-absolute href of the folder being shown.
    private var rootUrl: String = ""
    private var currentHref: String? = null

    /** Ancestor hrefs (null = root) to support "go up one level". */
    var pathStack by mutableStateOf<List<String?>>(emptyList())
    val canGoUp: Boolean get() = pathStack.isNotEmpty()

    val savedServerUrl: String get() = prefs.getString(KEY_URL, "") ?: ""
    val savedUsername: String get() = prefs.getString(KEY_USER, "") ?: ""
    val savedPassword: String get() = prefs.getString(KEY_PASS, "") ?: ""

    fun connect(serverUrl: String, username: String, password: String) {
        connectionState = ConnectionState.Connecting
        val client = WebDavClient(serverUrl, username, password)
        viewModelScope.launch {
            try {
                // List the user-configured URL itself (which may include a
                // sub-path like ".../music"), not a hardcoded "/" -- the
                // configured sub-path IS the root of this session.
                val list = withContext(Dispatchers.IO) { client.listDirectory(serverUrl) }
                rootUrl = serverUrl
                connectionState = ConnectionState.Connected(client)
                pathStack = emptyList()
                currentHref = null
                currentPath = client.rootPath()
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
        infoMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { state.client.listDirectory(resource.href) }
                pathStack = pathStack + currentHref
                currentHref = resource.href
                currentPath = resource.href
                entries = list
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            } finally {
                isLoading = false
            }
        }
    }

    /** Goes back to the parent of the currently displayed folder. */
    fun goUp() {
        val state = connectionState
        if (state !is ConnectionState.Connected || pathStack.isEmpty()) return
        val parentHref = pathStack.last()
        val newStack = pathStack.dropLast(1)
        isLoading = true
        errorMessage = null
        infoMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    state.client.listDirectory(parentHref ?: rootUrl)
                }
                pathStack = newStack
                currentHref = parentHref
                currentPath = parentHref ?: state.client.rootPath()
                entries = list
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            } finally {
                isLoading = false
            }
        }
    }

    /** Starts a single-file playback queue immediately (no network needed). */
    fun playSingleFile(resource: WebDavResource) {
        playlist = listOf(resource)
        playlistIndex = 0
    }

    /**
     * Lists [resource] (a folder) non-recursively, keeps only directly
     * playable media files (nested sub-folders are NOT included), and
     * invokes [onResult] with the queue once loaded -- it is only called
     * when at least one playable file was found; otherwise [infoMessage]
     * ("Отсутствуют медиафайлы") or [errorMessage] is set instead.
     */
    fun playFolder(resource: WebDavResource, onResult: (List<WebDavResource>) -> Unit) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        isLoading = true
        errorMessage = null
        infoMessage = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { state.client.listDirectory(resource.href) }
                val playableFiles = list.filter { !it.isDirectory && it.isPlayable() }
                if (playableFiles.isEmpty()) {
                    infoMessage = "Отсутствуют медиафайлы"
                } else {
                    playlist = playableFiles
                    playlistIndex = 0
                    onResult(playableFiles)
                }
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
        currentHref = null
        pathStack = emptyList()
        playlist = emptyList()
        playlistIndex = 0
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
