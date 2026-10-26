package com.admvvk.webdavplayer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.admvvk.webdavplayer.ui.ConnectScreen
import com.admvvk.webdavplayer.ui.FileListScreen
import com.admvvk.webdavplayer.ui.PlayerScreen
import com.admvvk.webdavplayer.webdav.WebDavResource

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

    NavHost(navController = navController, startDestination = "connect") {
        composable("connect") {
            ConnectScreen(
                connectionState = viewModel.connectionState,
                initialServerUrl = viewModel.savedServerUrl.ifEmpty { "https://" },
                initialUsername = viewModel.savedUsername,
                initialPassword = viewModel.savedPassword,
                onConnect = { url, user, pass ->
                    viewModel.connect(url, user, pass)
                },
            )
            val state = viewModel.connectionState
            if (state is ConnectionState.Connected) {
                navController.navigate("files") { popUpTo("connect") { inclusive = true } }
            }
        }

        composable("files") {
            FileListScreen(
                currentPath = viewModel.currentPath,
                entries = viewModel.entries,
                isLoading = viewModel.isLoading,
                errorMessage = viewModel.errorMessage,
                onOpenDirectory = { viewModel.openDirectory(it) },
                onPlayFile = { resource: WebDavResource ->
                    val state = viewModel.connectionState
                    if (state is ConnectionState.Connected) {
                        val url = state.client.resolve(resource.href)
                        val encoded = Uri.encode(url)
                        navController.navigate("player/$encoded")
                    }
                },
                onDisconnect = {
                    viewModel.disconnect()
                    navController.navigate("connect") { popUpTo("files") { inclusive = true } }
                },
            )
        }

        composable("player/{url}") { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("url").orEmpty()
            val url = Uri.decode(encoded)
            val state = viewModel.connectionState
            val authHeader = (state as? ConnectionState.Connected)?.client?.authHeader().orEmpty()
            PlayerScreen(mediaUrl = url, authHeader = authHeader)
        }
    }
}
