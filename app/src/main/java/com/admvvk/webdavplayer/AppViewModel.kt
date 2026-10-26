package com.admvvk.webdavplayer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.admvvk.webdavplayer.webdav.WebDavClient
import com.admvvk.webdavplayer.webdav.WebDavResource
import com.admvvk.webdavplayer.webdav.isPlayable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // --- Next-track pre-caching (Connection Settings) -----------------

    var cacheSizeMb by mutableStateOf(prefs.getInt(KEY_CACHE_MB, DEFAULT_CACHE_MB))
    private var mediaCache: SimpleCache? = null

    /** The shared on-disk cache used by PlayerScreen's CacheDataSource. */
    fun getOrCreateCache(): SimpleCache {
        mediaCache?.let { return it }
        val dir = File(getApplication<Application>().cacheDir, "media_cache")
        val cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(cacheSizeMb * 1024L * 1024L))
        mediaCache = cache
        return cache
    }

    fun updateCacheSizeMb(mb: Int) {
        val clamped = mb.coerceIn(16, 8192)
        cacheSizeMb = clamped
        prefs.edit().putInt(KEY_CACHE_MB, clamped).apply()
        // The cache size (evictor limit) is fixed at construction time, so
        // changing it means recreating the cache. Only safe to do when no
        // player currently holds a DataSource backed by the old instance,
        // which in practice means "not while the player screen is open".
        mediaCache?.release()
        mediaCache = null
    }

    // --- Directory listing / navigation --------------------------------

    private fun mediaOnly(list: List<WebDavResource>): List<WebDavResource> =
        list.filter { it.isDirectory || it.isPlayable() }

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
                entries = mediaOnly(list)
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
                entries = mediaOnly(list)
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
                entries = mediaOnly(list)
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

    // --- Downloads (SAF: user picks the destination) -------------------

    /** Downloads a single file to [destUri] (from ACTION_CREATE_DOCUMENT). */
    fun downloadFile(resource: WebDavResource, destUri: Uri) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    state.client.openDownloadStream(resource.href).use { input ->
                        val output = getApplication<Application>().contentResolver.openOutputStream(destUri)
                            ?: throw IllegalStateException("Cannot open destination")
                        output.use { input.copyTo(it) }
                    }
                }
                infoMessage = "Скачано: ${resource.displayName}"
            } catch (e: Exception) {
                errorMessage = "Ошибка скачивания «${resource.displayName}»: ${e.message}"
            }
        }
    }

    /**
     * Downloads every directly-contained playable file of [resource] (a
     * folder) into [treeUri] (from ACTION_OPEN_DOCUMENT_TREE). Nested
     * sub-folders are not descended into, matching "play folder".
     */
    fun downloadFolder(resource: WebDavResource, treeUri: Uri) {
        val state = connectionState
        if (state !is ConnectionState.Connected) return
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    state.client.listDirectory(resource.href).filter { !it.isDirectory && it.isPlayable() }
                }
                if (files.isEmpty()) {
                    infoMessage = "Отсутствуют медиафайлы"
                    return@launch
                }
                val app = getApplication<Application>()
                val treeDoc = DocumentFile.fromTreeUri(app, treeUri)
                    ?: throw IllegalStateException("Cannot open destination folder")
                var okCount = 0
                withContext(Dispatchers.IO) {
                    for (file in files) {
                        val downloaded = try {
                            val mime = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(file.displayName.substringAfterLast('.', ""))
                                ?: "application/octet-stream"
                            val target = treeDoc.createFile(mime, file.displayName)
                            if (target != null) {
                                state.client.openDownloadStream(file.href).use { input ->
                                    val output = app.contentResolver.openOutputStream(target.uri)
                                    if (output != null) {
                                        output.use { input.copyTo(it) }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                false
                            }
                        } catch (e: Exception) {
                            // Best-effort: skip this file, keep going with the rest.
                            false
                        }
                        if (downloaded) okCount++
                    }
                }
                infoMessage = "Скачано файлов: $okCount из ${files.size}"
            } catch (e: Exception) {
                errorMessage = "Ошибка скачивания папки: ${e.message}"
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
        private const val KEY_CACHE_MB = "cache_size_mb"
        const val DEFAULT_CACHE_MB = 256
    }
}
