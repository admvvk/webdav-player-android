package com.admvvk.webdavplayer

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.admvvk.webdavplayer.ui.CacheSettingsScreen
import com.admvvk.webdavplayer.ui.FileListScreen
import com.admvvk.webdavplayer.ui.HomeScreen
import com.admvvk.webdavplayer.ui.PlayerScreen
import com.admvvk.webdavplayer.ui.ServerSettingsScreen
import com.admvvk.webdavplayer.ui.SettingsOtherScreen
import com.admvvk.webdavplayer.ui.SoundSettingsScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Needed on Android 13+ for the PlaybackService's media notification
        // to actually be visible; playback itself works either way, this
        // just controls whether the user sees transport controls while the
        // app is backgrounded.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Ask to be exempted from Doze/App Standby battery optimizations.
        // Needed in addition to the foreground service on some OEM ROMs
        // (MIUI, ColorOS/RealmeUI, ...) that still throttle a backgrounded
        // app's network access otherwise. Standalone from any vendor-
        // specific "background app management" toggle, which has no
        // public API and must be enabled by the user manually.
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(viewModel: AppViewModel) {
    val navController: NavHostController = rememberNavController()

    // Settings screen #2 ("settings_other": connection cache + sound) is
    // reachable from every screen except Home, and deliberately never
    // touches playback -- just navigate there and let popBackStack()
    // return to whichever screen (files/player) opened it.
    val openOtherSettings: () -> Unit = { navController.navigate("settings_other") }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            // `attemptingConnect` scopes the auto-navigate-once-connected
            // effect to a connection *started from this screen instance*.
            // Without it, simply being Connected (e.g. after pressing Back
            // from "files") would immediately bounce the user straight
            // back into "files", making Back from the player/file list
            // feel broken.
            var attemptingConnect by remember { mutableStateOf(false) }
            val state = viewModel.connectionState
            LaunchedEffect(state) {
                if (attemptingConnect && state is ConnectionState.Connected) {
                    attemptingConnect = false
                    navController.navigate("files") { popUpTo("home") { inclusive = false } }
                }
            }
            HomeScreen(
                isConnecting = state is ConnectionState.Connecting,
                errorMessage = (state as? ConnectionState.Error)?.message,
                // Settings screen #1 ("settings_server") -- server
                // connection fields only, reachable only from here.
                onOpenSettings = { navController.navigate("settings_server") },
                onOpenPlayer = {
                    when {
                        state is ConnectionState.Connected -> navController.navigate("files")
                        viewModel.savedServerUrl.isBlank() -> navController.navigate("settings_server")
                        else -> {
                            attemptingConnect = true
                            viewModel.connect(viewModel.savedServerUrl, viewModel.savedUsername, viewModel.savedPassword)
                        }
                    }
                },
            )
        }

        composable("settings_server") {
            var attemptingConnect by remember { mutableStateOf(false) }
            val state = viewModel.connectionState
            LaunchedEffect(state) {
                if (attemptingConnect && state is ConnectionState.Connected) {
                    attemptingConnect = false
                    navController.navigate("files") { popUpTo("home") { inclusive = false } }
                }
            }
            ServerSettingsScreen(
                connectionState = state,
                initialServerUrl = viewModel.savedServerUrl.ifEmpty { "https://" },
                initialUsername = viewModel.savedUsername,
                initialPassword = viewModel.savedPassword,
                onBack = { navController.popBackStack() },
                onConnect = { url, user, pass ->
                    attemptingConnect = true
                    viewModel.connect(url, user, pass)
                },
            )
        }

        composable("settings_other") {
            SettingsOtherScreen(
                onBack = { navController.popBackStack() },
                onConnectionSettings = { navController.navigate("settings/cache") },
                onSoundSettings = { navController.navigate("settings/sound") },
            )
        }

        composable("settings/cache") {
            CacheSettingsScreen(
                currentCacheSizeMb = viewModel.cacheSizeMb,
                onBack = { navController.popBackStack() },
                onSave = { mb ->
                    viewModel.updateCacheSizeMb(mb)
                    navController.popBackStack()
                },
            )
        }

        composable("settings/sound") {
            SoundSettingsScreen(
                currentPreampDb = viewModel.preampDb,
                onBack = { navController.popBackStack() },
                onSave = { db ->
                    viewModel.updatePreampDb(db)
                    navController.popBackStack()
                },
            )
        }

        composable("files") {
            FileListScreen(
                currentPath = viewModel.currentPath,
                canGoUp = viewModel.canGoUp,
                entries = viewModel.entries,
                isLoading = viewModel.isLoading,
                errorMessage = viewModel.errorMessage,
                infoMessage = viewModel.infoMessage,
                onErrorShown = { viewModel.errorMessage = null },
                onInfoShown = { viewModel.infoMessage = null },
                onGoUp = { viewModel.goUp() },
                onOpenDirectory = { viewModel.openDirectory(it) },
                onPlayFile = { resource ->
                    viewModel.playFromFile(resource)
                    navController.navigate("player")
                },
                onDownloadFile = { resource, uri -> viewModel.downloadFile(resource, uri) },
                onOpenSettings = openOtherSettings,
                playerViewModel = viewModel,
                onExpandPlayer = { navController.navigate("player") },
            )
        }

        composable("player") {
            PlayerScreen(
                viewModel = viewModel,
                onExit = { navController.popBackStack() },
                onOpenSettings = openOtherSettings,
            )
        }
    }
}
