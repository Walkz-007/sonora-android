package org.sonora.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sonora.android.core.AlbumItem
import org.sonora.android.core.ArtistItem
import org.sonora.android.core.CollectionPage
import org.sonora.android.core.LibraryContents
import org.sonora.android.core.PlaylistItem
import org.sonora.android.core.SearchResults
import org.sonora.android.core.Shelf
import org.sonora.android.core.Source
import org.sonora.android.core.TrackItem

/** What a screen shows while it loads something from the engine. */
sealed interface Load<out T> {
    data object Busy : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Busy) }
    var attempt by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, attempt) {
        state = Load.Busy
        state = try {
            Load.Done(block())
        } catch (error: Exception) {
            Load.Failed(error.message ?: error.toString())
        }
    }
    return state to { attempt++ }
}

/** The row of signed-in sources at the top of a browsing screen. */
@Composable
fun SourceBar(model: AppModel, title: String) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val source by model.source.collectAsStateWithLifecycle()
    Column(Modifier.statusBarsPadding()) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        if (accounts.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                accounts.forEach { account ->
                    FilterChip(
                        selected = account.source == source,
                        onClick = { model.select(account.source) },
                        label = { Text(account.source.label()) },
                    )
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NoSource(model: AppModel) {
    Message("Sign in to a music source to get started.", action = "Open accounts") { model.tab(Route.Accounts) }
}

fun trackActions(model: AppModel, source: Source, track: TrackItem): List<TrackAction> {
    val actions = mutableListOf(
        TrackAction("Play next") { model.player.playNext(source, track) },
        TrackAction("Add to queue") { model.player.addToQueue(source, track) },
    )
    track.albumId?.let { id -> actions += TrackAction("Go to album") { model.push(Route.Album(source, id, track.album)) } }
    track.artistId?.let { id -> actions += TrackAction("Go to artist") { model.push(Route.Artist(source, id, track.artists)) } }
    return actions
}

@Composable
fun HomeScreen(model: AppModel) {
    val source by model.source.collectAsStateWithLifecycle()
    val player by model.player.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        SourceBar(model, "Home")
        val current = source ?: return NoSource(model)
        val (load, retry) = rememberLoad(current) { model.core.home(current) }
        when (load) {
            Load.Busy -> Loading()
            is Load.Failed -> Message(load.message, action = "Retry", onAction = retry)
            is Load.Done -> {
                if (load.value.isEmpty()) {
                    Message("Nothing here yet. Try Search or your Library.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(load.value, key = { it.title + it.hashCode() }) { shelf ->
                            ShelfView(model, current, shelf, player.current?.track?.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfView(model: AppModel, source: Source, shelf: Shelf, playingId: String?) {
    SectionHeader(shelf.title)
    if (shelf.tracks.isNotEmpty()) {
        // Tracks in a shelf read as a short list, four at a time, swiped sideways.
        val pages = shelf.tracks.chunked(4)
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 0.dp)) {
            items(pages.size) { page ->
                Column(Modifier.width(330.dp)) {
                    pages[page].forEach { track ->
                        TrackRow(
                            track,
                            onClick = { model.player.playTracks(source, shelf.tracks, shelf.tracks.indexOf(track)) },
                            playing = track.id == playingId,
                            actions = trackActions(model, source, track),
                        )
                    }
                }
            }
        }
    }
    if (shelf.albums.isNotEmpty()) AlbumRow(shelf.albums) { model.push(Route.Album(source, it.id, it.title)) }
    if (shelf.playlists.isNotEmpty()) PlaylistRow(shelf.playlists) { model.push(Route.Playlist(source, it.id, it.name)) }
    if (shelf.artists.isNotEmpty()) ArtistRow(shelf.artists) { model.push(Route.Artist(source, it.id, it.name)) }
}

@Composable
fun SearchScreen(model: AppModel) {
    val source by model.source.collectAsStateWithLifecycle()
    val player by model.player.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SourceBar(model, "Search")
        val current = source ?: return NoSource(model)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Songs, albums, playlists") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = "" }) { Icon(Icons.Filled.Clear, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
        )
        LaunchedEffect(query) {
            delay(500)
            if (query.trim().length >= 2) submitted = query.trim()
        }
        if (submitted.isEmpty()) {
            Message("Search ${current.label()}")
            return
        }
        val (load, retry) = rememberLoad(current, submitted) { model.core.search(current, submitted) }
        when (load) {
            Load.Busy -> Loading()
            is Load.Failed -> Message(load.message, action = "Retry", onAction = retry)
            is Load.Done -> SearchResultsView(model, current, load.value, player.current?.track?.id)
        }
    }
}

@Composable
private fun SearchResultsView(model: AppModel, source: Source, results: SearchResults, playingId: String?) {
    if (results.tracks.isEmpty() && results.albums.isEmpty() && results.playlists.isEmpty()) {
        Message("No results")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (results.tracks.isNotEmpty()) {
            item { SectionHeader("Songs") }
            itemsIndexed(results.tracks.take(30), key = { i, t -> "t$i" + t.id }) { index, track ->
                TrackRow(
                    track,
                    onClick = { model.player.playTracks(source, results.tracks, index) },
                    playing = track.id == playingId,
                    actions = trackActions(model, source, track),
                )
            }
        }
        if (results.albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item { AlbumRow(results.albums) { model.push(Route.Album(source, it.id, it.title)) } }
        }
        if (results.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists") }
            item { PlaylistRow(results.playlists) { model.push(Route.Playlist(source, it.id, it.name)) } }
        }
    }
}

@Composable
fun LibraryScreen(model: AppModel) {
    val source by model.source.collectAsStateWithLifecycle()
    val player by model.player.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SourceBar(model, "Library")
        val current = source ?: return NoSource(model)
        val (load, retry) = rememberLoad(current) { model.core.library(current) }
        val tabs = listOf("Playlists", "Albums", "Artists", "Songs")
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
        }
        when (load) {
            Load.Busy -> Loading()
            is Load.Failed -> Message(load.message, action = "Retry", onAction = retry)
            is Load.Done -> LibraryTab(model, current, load.value, tab, player.current?.track?.id)
        }
    }
}

@Composable
private fun LibraryTab(model: AppModel, source: Source, library: LibraryContents, tab: Int, playingId: String?) {
    when (tab) {
        0 -> GridList(library.playlists, empty = "No playlists") { playlist: PlaylistItem ->
            ListEntry(playlist.name, playlist.owner.ifBlank { "${playlist.trackCount} tracks" }, playlist.cover) {
                model.push(Route.Playlist(source, playlist.id, playlist.name))
            }
        }
        1 -> GridList(library.albums, empty = "No saved albums") { album: AlbumItem ->
            ListEntry(album.title, album.artists, album.cover) { model.push(Route.Album(source, album.id, album.title)) }
        }
        2 -> GridList(library.artists, empty = "No artists") { artist: ArtistItem ->
            ListEntry(artist.name, null, artist.cover, round = true) { model.push(Route.Artist(source, artist.id, artist.name)) }
        }
        else -> {
            if (library.tracks.isEmpty()) {
                Message("No saved songs")
                return
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    PlayButtons(
                        onPlay = { model.player.playTracks(source, library.tracks, 0) },
                        onShuffle = { model.player.playTracks(source, library.tracks, 0, shuffle = true) },
                    )
                }
                itemsIndexed(library.tracks, key = { i, t -> "s$i" + t.id }) { index, track ->
                    TrackRow(
                        track,
                        onClick = { model.player.playTracks(source, library.tracks, index) },
                        playing = track.id == playingId,
                        actions = trackActions(model, source, track),
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> GridList(entries: List<T>, empty: String, row: @Composable (T) -> Unit) {
    if (entries.isEmpty()) {
        Message(empty)
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(entries.size) { index -> row(entries[index]) }
    }
}

@Composable
private fun ListEntry(title: String, subtitle: String?, cover: String?, round: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableRow(onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            cover,
            Modifier.width(56.dp).aspectRatio(1f),
            if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(6.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

@Composable
fun BackBar(model: AppModel, title: String = "") {
    Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { model.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun CollectionScreen(model: AppModel, source: Source, id: String, title: String, album: Boolean) {
    val player by model.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (load, retry) = rememberLoad(source, id) {
        if (album) model.core.album(source, id) else model.core.playlist(source, id)
    }
    var extra by remember(source, id) { mutableStateOf<List<TrackItem>>(emptyList()) }
    var continuation by remember(source, id) { mutableStateOf<String?>(null) }
    var loadingMore by remember(source, id) { mutableStateOf(false) }
    LaunchedEffect(load) { if (load is Load.Done) continuation = load.value.continuation }

    Column(Modifier.fillMaxSize()) {
        BackBar(model)
        when (load) {
            Load.Busy -> Loading()
            is Load.Failed -> Message(load.message, action = "Retry", onAction = retry)
            is Load.Done -> {
                val page: CollectionPage = load.value
                val tracks = page.tracks + extra
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(page.cover, Modifier.fillMaxWidth(0.62f).aspectRatio(1f), RoundedCornerShape(12.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(page.title.ifBlank { title }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (page.subtitle.isNotBlank()) {
                                Text(page.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        PlayButtons(
                            onPlay = { model.player.playTracks(source, tracks, 0) },
                            onShuffle = { model.player.playTracks(source, tracks, 0, shuffle = true) },
                        )
                    }
                    itemsIndexed(tracks, key = { i, t -> "c$i" + t.id }) { index, track ->
                        TrackRow(
                            track,
                            onClick = { model.player.playTracks(source, tracks, index) },
                            playing = track.id == player.current?.track?.id,
                            number = index + 1,
                            showCover = !album,
                            actions = trackActions(model, source, track),
                        )
                    }
                    val more = continuation
                    if (more != null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                FilledTonalButton(enabled = !loadingMore, onClick = {
                                    loadingMore = true
                                    scope.launch {
                                        try {
                                            val next = model.core.playlistMore(source, more)
                                            extra = extra + next.tracks
                                            continuation = next.continuation
                                        } catch (_: Exception) {
                                            continuation = null
                                        } finally {
                                            loadingMore = false
                                        }
                                    }
                                }) { Text(if (loadingMore) "Loading…" else "Load more") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(model: AppModel, route: Route.Artist) {
    val player by model.player.state.collectAsStateWithLifecycle()
    val (load, retry) = rememberLoad(route.source, route.id) { model.core.artist(route.source, route.id) }
    Column(Modifier.fillMaxSize()) {
        BackBar(model)
        when (load) {
            Load.Busy -> Loading()
            is Load.Failed -> Message(load.message, action = "Retry", onAction = retry)
            is Load.Done -> {
                val artist = load.value
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(artist.cover, Modifier.fillMaxWidth(0.5f).aspectRatio(1f), androidx.compose.foundation.shape.CircleShape)
                            Spacer(Modifier.height(16.dp))
                            Text(artist.name.ifBlank { route.name }, style = MaterialTheme.typography.headlineMedium)
                        }
                        if (artist.topTracks.isNotEmpty()) {
                            PlayButtons(
                                onPlay = { model.player.playTracks(route.source, artist.topTracks, 0) },
                                onShuffle = { model.player.playTracks(route.source, artist.topTracks, 0, shuffle = true) },
                            )
                        }
                    }
                    if (artist.topTracks.isNotEmpty()) {
                        item { SectionHeader("Popular") }
                        itemsIndexed(artist.topTracks.take(10), key = { i, t -> "p$i" + t.id }) { index, track ->
                            TrackRow(
                                track,
                                onClick = { model.player.playTracks(route.source, artist.topTracks, index) },
                                playing = track.id == player.current?.track?.id,
                                actions = trackActions(model, route.source, track),
                            )
                        }
                    }
                    if (artist.albums.isNotEmpty()) {
                        item { SectionHeader("Discography") }
                        item { AlbumRow(artist.albums) { model.push(Route.Album(route.source, it.id, it.title)) } }
                    }
                }
            }
        }
    }
}
