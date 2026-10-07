package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.Album
import com.example.data.model.Artist
import com.example.data.model.PlaylistSummary
import com.example.data.model.Song
import com.example.data.network.SearchFilter
import com.example.ui.MusicViewModel
import com.example.ui.components.SongItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MusicViewModel,
    onOpenSongMenu: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchFilter by viewModel.searchFilter.collectAsState()
    val searchResult by viewModel.searchResult.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    var selectedContainerTitle by remember { mutableStateOf<String?>(null) }
    var selectedContainerTracks by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoadingContainer by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_screen")
    ) {
        // Search Input Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("search_input"),
            placeholder = { Text("Search songs, artists, albums...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    viewModel.executeSearch()
                }
            )
        )

        // Filter Chips Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                Pair("All", SearchFilter.ALL),
                Pair("Songs", SearchFilter.SONGS),
                Pair("Artists", SearchFilter.ARTISTS),
                Pair("Albums", SearchFilter.ALBUMS),
                Pair("Playlists", SearchFilter.PLAYLISTS)
            )

            items(filters) { (title, filter) ->
                FilterChip(
                    selected = searchFilter == filter,
                    onClick = { viewModel.onSearchFilterChange(filter) },
                    label = { Text(title) }
                )
            }
        }

        // Search Content or Empty State
        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (searchQuery.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Search for your favorite music",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Type an artist, song, or album name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
            ) {
                // Artists row (if any)
                if (searchResult.artists.isNotEmpty() && (searchFilter == SearchFilter.ALL || searchFilter == SearchFilter.ARTISTS)) {
                    item {
                        Text(
                            text = "Artists",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(searchResult.artists) { artist ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .clickable {
                                            viewModel.onSearchQueryChange(artist.name)
                                            viewModel.onSearchFilterChange(SearchFilter.SONGS)
                                        }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        shadowElevation = 4.dp,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                    ) {
                                        AsyncImage(
                                            model = artist.thumbnailUrl,
                                            contentDescription = artist.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = artist.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Albums row (if any)
                if (searchResult.albums.isNotEmpty() && (searchFilter == SearchFilter.ALL || searchFilter == SearchFilter.ALBUMS)) {
                    item {
                        Text(
                            text = "Albums",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(searchResult.albums) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clickable {
                                            selectedContainerTitle = album.title
                                            isLoadingContainer = true
                                            coroutineScope.launch {
                                                selectedContainerTracks = viewModel.getAlbumOrPlaylistSongs(album.id)
                                                isLoadingContainer = false
                                            }
                                        }
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        shadowElevation = 4.dp,
                                        modifier = Modifier
                                            .size(130.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = album.thumbnailUrl,
                                            contentDescription = album.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = album.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = album.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Playlists row (if any)
                if (searchResult.playlists.isNotEmpty() && (searchFilter == SearchFilter.ALL || searchFilter == SearchFilter.PLAYLISTS)) {
                    item {
                        Text(
                            text = "Community Playlists",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(searchResult.playlists) { playlist ->
                                Column(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clickable {
                                            selectedContainerTitle = playlist.title
                                            isLoadingContainer = true
                                            coroutineScope.launch {
                                                selectedContainerTracks = viewModel.getAlbumOrPlaylistSongs(playlist.id)
                                                isLoadingContainer = false
                                            }
                                        }
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        shadowElevation = 4.dp,
                                        modifier = Modifier
                                            .size(130.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = playlist.thumbnailUrl,
                                            contentDescription = playlist.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = playlist.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = playlist.author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Songs list
                if (searchResult.songs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Songs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(searchResult.songs) { song ->
                        SongItem(
                            song = song,
                            isCurrent = currentSong?.id == song.id,
                            isPlaying = isPlaying && currentSong?.id == song.id,
                            onClick = {
                                val idx = searchResult.songs.indexOf(song).coerceAtLeast(0)
                                viewModel.playSong(song, searchResult.songs, idx)
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
                } else if (searchResult.songs.isEmpty() && searchResult.albums.isEmpty() && searchResult.artists.isEmpty() && searchResult.playlists.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No results found for '$searchQuery'",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet for viewing Album / Playlist tracks
    if (selectedContainerTitle != null) {
        ModalBottomSheet(
            onDismissRequest = {
                selectedContainerTitle = null
                selectedContainerTracks = emptyList()
            }
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
                    Text(
                        text = selectedContainerTitle ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (selectedContainerTracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.playSong(selectedContainerTracks.first(), selectedContainerTracks, 0)
                                selectedContainerTitle = null
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoadingContainer) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (selectedContainerTracks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tracks found in this collection",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(selectedContainerTracks) { song ->
                            SongItem(
                                song = song,
                                isCurrent = currentSong?.id == song.id,
                                isPlaying = isPlaying && currentSong?.id == song.id,
                                onClick = {
                                    val idx = selectedContainerTracks.indexOf(song).coerceAtLeast(0)
                                    viewModel.playSong(song, selectedContainerTracks, idx)
                                    selectedContainerTitle = null
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
}
