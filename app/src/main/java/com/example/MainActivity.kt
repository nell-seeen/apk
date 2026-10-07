package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.Song
import com.example.ui.MusicViewModel
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.MiniPlayer
import com.example.ui.components.NowPlayingSheet
import com.example.ui.screens.*
import com.example.ui.theme.MetroTuneTheme

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    DOWNLOADS("Downloads", Icons.Default.DownloadForOffline),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()

            MetroTuneTheme(themeMode = themeMode) {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppScreen(viewModel: MusicViewModel) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var songForPlaylistDialog by remember { mutableStateOf<Song?>(null) }

    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val playlists by viewModel.localPlaylists.collectAsState()

    val safeDuration = if (duration > 0) duration else ((currentSong?.durationSeconds ?: 180) * 1000L).coerceAtLeast(1L)
    val progress = (currentPosition.toFloat() / safeDuration).coerceIn(0f, 1f)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column {
                // Docked Mini Player above Navigation Bar
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    MiniPlayer(
                        song = currentSong,
                        isPlaying = isPlaying,
                        progress = progress,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSkipNext = { viewModel.playNext() },
                        onClick = { showNowPlaying = true }
                    )
                }

                // Bottom Navigation Bar
                NavigationBar(
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    Screen.values().forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            modifier = Modifier.testTag("nav_${screen.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                Screen.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onSongSelected = { song -> viewModel.playSong(song) },
                    onNavigateToSearchWithQuery = { query ->
                        viewModel.onSearchQueryChange(query)
                        viewModel.executeSearch()
                        currentScreen = Screen.SEARCH
                    },
                    onOpenSongMenu = { song -> songForPlaylistDialog = song }
                )
                Screen.SEARCH -> SearchScreen(
                    viewModel = viewModel,
                    onOpenSongMenu = { song -> songForPlaylistDialog = song }
                )
                Screen.LIBRARY -> LibraryScreen(
                    viewModel = viewModel,
                    onOpenSongMenu = { song -> songForPlaylistDialog = song }
                )
                Screen.DOWNLOADS -> DownloadsScreen(
                    viewModel = viewModel,
                    onOpenSongMenu = { song -> songForPlaylistDialog = song }
                )
                Screen.SETTINGS -> SettingsScreen(
                    viewModel = viewModel
                )
            }
        }
    }

    // Full Now Playing Bottom Sheet
    if (showNowPlaying && currentSong != null) {
        NowPlayingSheet(
            viewModel = viewModel,
            onDismiss = { showNowPlaying = false }
        )
    }

    // Add to Playlist Dialog
    if (songForPlaylistDialog != null) {
        val songToAdd = songForPlaylistDialog!!
        AddToPlaylistDialog(
            song = songToAdd,
            playlists = playlists,
            onAddToPlaylist = { playlistId ->
                viewModel.addSongToPlaylist(playlistId, songToAdd)
                songForPlaylistDialog = null
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylist(name)
                // Note: user can add to the created playlist on next open
                songForPlaylistDialog = null
            },
            onDismiss = { songForPlaylistDialog = null }
        )
    }
}
