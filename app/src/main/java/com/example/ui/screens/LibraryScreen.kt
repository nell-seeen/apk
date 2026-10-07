package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.PlaylistEntity
import com.example.data.model.Song
import com.example.ui.MusicViewModel
import com.example.ui.components.SongItem

enum class LibraryTab {
    FAVORITES, PLAYLISTS, HISTORY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    onOpenSongMenu: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(LibraryTab.FAVORITES) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    var selectedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var showRenameDialog by remember { mutableStateOf<PlaylistEntity?>(null) }
    var renamePlaylistName by remember { mutableStateOf("") }

    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val localPlaylists by viewModel.localPlaylists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen")
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "My Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (activeTab == LibraryTab.PLAYLISTS) {
                FilledTonalButton(
                    onClick = { showCreatePlaylistDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Playlist")
                }
            } else if (activeTab == LibraryTab.HISTORY && history.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearHistory() }) {
                    Text("Clear History")
                }
            }
        }

        // Tabs
        PrimaryTabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Tab(
                selected = activeTab == LibraryTab.FAVORITES,
                onClick = { activeTab = LibraryTab.FAVORITES },
                text = { Text("Favorites (${favorites.size})") }
            )
            Tab(
                selected = activeTab == LibraryTab.PLAYLISTS,
                onClick = { activeTab = LibraryTab.PLAYLISTS },
                text = { Text("Playlists (${localPlaylists.size})") }
            )
            Tab(
                selected = activeTab == LibraryTab.HISTORY,
                onClick = { activeTab = LibraryTab.HISTORY },
                text = { Text("History (${history.size})") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Body
        when (activeTab) {
            LibraryTab.FAVORITES -> {
                val favSongs = favorites.map { it.toSong() }
                if (favSongs.isEmpty()) {
                    EmptyLibraryPlaceholder(
                        icon = Icons.Default.FavoriteBorder,
                        title = "No favorites yet",
                        subtitle = "Tap the heart on any song to save it here"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
                    ) {
                        item {
                            ActionButtonsRow(
                                onPlayAll = { viewModel.playSong(favSongs.first(), favSongs, 0) },
                                onShuffle = {
                                    val shuffled = favSongs.shuffled()
                                    viewModel.playSong(shuffled.first(), shuffled, 0)
                                }
                            )
                        }

                        items(favSongs) { song ->
                            SongItem(
                                song = song,
                                isCurrent = currentSong?.id == song.id,
                                isPlaying = isPlaying && currentSong?.id == song.id,
                                onClick = {
                                    val idx = favSongs.indexOf(song).coerceAtLeast(0)
                                    viewModel.playSong(song, favSongs, idx)
                                },
                                onPlayNext = { viewModel.addToQueueNext(song) },
                                onAddToQueue = { viewModel.addToQueueEnd(song) },
                                onAddToPlaylist = { onOpenSongMenu(song) },
                                onToggleFavorite = { viewModel.toggleFavorite(song) },
                                onDownload = { viewModel.startDownload(song) },
                                isFavorite = true,
                                isDownloaded = viewModel.isDownloaded(song.id)
                            )
                        }
                    }
                }
            }

            LibraryTab.PLAYLISTS -> {
                if (localPlaylists.isEmpty()) {
                    EmptyLibraryPlaceholder(
                        icon = Icons.Default.PlaylistPlay,
                        title = "No playlists created",
                        subtitle = "Create a custom playlist to organize your songs"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        items(localPlaylists) { playlist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable { selectedPlaylist = playlist },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlaylistPlay,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Local Playlist",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            renamePlaylistName = playlist.name
                                            showRenameDialog = playlist
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.deletePlaylist(playlist.id) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.HISTORY -> {
                val histSongs = history.map { it.toSong() }
                if (histSongs.isEmpty()) {
                    EmptyLibraryPlaceholder(
                        icon = Icons.Default.History,
                        title = "No listening history",
                        subtitle = "Songs you actually play will appear here"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
                    ) {
                        items(histSongs) { song ->
                            SongItem(
                                song = song,
                                isCurrent = currentSong?.id == song.id,
                                isPlaying = isPlaying && currentSong?.id == song.id,
                                onClick = {
                                    val idx = histSongs.indexOf(song).coerceAtLeast(0)
                                    viewModel.playSong(song, histSongs, idx)
                                },
                                onPlayNext = { viewModel.addToQueueNext(song) },
                                onAddToQueue = { viewModel.addToQueueEnd(song) },
                                onAddToPlaylist = { onOpenSongMenu(song) },
                                onToggleFavorite = { viewModel.toggleFavorite(song) },
                                onDownload = { viewModel.startDownload(song) },
                                isFavorite = favorites.any { it.songId == song.id },
                                isDownloaded = viewModel.isDownloaded(song.id)
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Playlist Dialog
    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = renamePlaylistName,
                    onValueChange = { renamePlaylistName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = showRenameDialog
                        if (p != null && renamePlaylistName.isNotBlank()) {
                            viewModel.renamePlaylist(p.id, renamePlaylistName.trim())
                            showRenameDialog = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Selected Playlist Detail Sheet
    if (selectedPlaylist != null) {
        val currentSelected = selectedPlaylist!!
        val playlistSongs by viewModel.getPlaylistTracks(currentSelected.id).collectAsState(initial = emptyList())

        ModalBottomSheet(
            onDismissRequest = { selectedPlaylist = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(max = 500.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSelected.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${playlistSongs.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (playlistSongs.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.playSong(playlistSongs.first(), playlistSongs, 0)
                                selectedPlaylist = null
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (playlistSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No songs added to this playlist yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(playlistSongs) { song ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    SongItem(
                                        song = song,
                                        isCurrent = currentSong?.id == song.id,
                                        isPlaying = isPlaying && currentSong?.id == song.id,
                                        onClick = {
                                            val idx = playlistSongs.indexOf(song).coerceAtLeast(0)
                                            viewModel.playSong(song, playlistSongs, idx)
                                            selectedPlaylist = null
                                        },
                                        onPlayNext = { viewModel.addToQueueNext(song) },
                                        onAddToQueue = { viewModel.addToQueueEnd(song) },
                                        onAddToPlaylist = { onOpenSongMenu(song) },
                                        onToggleFavorite = { viewModel.toggleFavorite(song) },
                                        onDownload = { viewModel.startDownload(song) },
                                        isFavorite = favorites.any { it.songId == song.id },
                                        isDownloaded = viewModel.isDownloaded(song.id)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.removeSongFromPlaylist(currentSelected.id, song.id)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove from playlist",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtonsRow(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onPlayAll,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Play All")
        }

        OutlinedButton(
            onClick = onShuffle,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

@Composable
private fun EmptyLibraryPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    }
}
