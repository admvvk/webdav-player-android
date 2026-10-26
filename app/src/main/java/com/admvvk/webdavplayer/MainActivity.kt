package com.admvvk.webdavplayer

import android.os.Bundle
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
import com.admvvk.webdavplayer.ui.ConnectScreen
import com.admvvk.webdavplayer.ui.FileListScreen
import com.admvvk.webdavplayer.ui.HomeScreen
import com.admvvk.webdavplayer.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                onOpenSettings = { navController.navigate("settings") },
                onOpenPlayer = {
                    when {
                        state is ConnectionState.Connected -> navController.navigate("files")
                        viewModel.savedServerUrl.isBlank() -> navController.navigate("settings")
                        else -> {
                            attemptingConnect = true
                            viewModel.connect(viewModel.savedServerUrl, viewModel.savedUsername, viewModel.savedPassword)
                        }
                    }
                },
            )
        }

        composable("settings") {
            var attemptingConnect by remember { mutableStateOf(false) }
            val state = viewModel.connectionState
            LaunchedEffect(state) {
                if (attemptingConnect && state is ConnectionState.Connected) {
                    attemptingConnect = false
                    navController.navigate("files") { popUpTo("home") { inclusive = false } }
                }
            }
            ConnectScreen(
                connectionState = state,
                initialServerUrl = viewModel.savedServerUrl.ifEmpty { "https://" },
                initialUsername = viewModel.savedUsername,
                initialPassword = viewModel.savedPassword,
                onConnect = { url, user, pass ->
                    attemptingConnect = true
                    viewModel.connect(url, user, pass)
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
                    viewModel.playSingleFile(resource)
                    navController.navigate("player")
                },
                onPlayFolder = { resource ->
                    viewModel.playFolder(resource) {
                        navController.navigate("player")
                    }
                },
                onDisconnect = {
                    viewModel.disconnect()
                    navController.navigate("home") { popUpTo("home") { inclusive = true } }
                },
            )
        }

        composable("player") {
            val state = viewModel.connectionState
            val client = (state as? ConnectionState.Connected)?.client
            if (client != null) {
                PlayerScreen(
                    playlist = viewModel.playlist,
                    startIndex = viewModel.playlistIndex,
                    resolveUrl = { href -> client.resolve(href) },
                    authHeader = client.authHeader(),
                    onExit = { navController.popBackStack() },
                )
            }
        }
    }
}
