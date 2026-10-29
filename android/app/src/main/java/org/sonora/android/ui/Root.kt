package org.sonora.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.sonora.android.playback.PlayerState

@Composable
fun SonoraRoot(model: AppModel) {
    val restoring by model.restoring.collectAsStateWithLifecycle()
    val player by model.player.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        model.restore()
    }
    LaunchedEffect(player.error) {
        val error = player.error ?: return@LaunchedEffect
        snackbar.showSnackbar(error)
        model.player.clearError()
    }

    val route = model.stack.lastOrNull() ?: Route.Home
    BackHandler(enabled = model.stack.size > 1) { model.pop() }

    if (route == Route.NowPlaying) {
        NowPlayingScreen(model, onClose = { model.pop() })
        return
    }
    if (route == Route.YouTubeLogin) {
        YouTubeLoginScreen(model, onDone = { model.pop() })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = player.current != null,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    MiniPlayer(player, model)
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    val root = model.stack.firstOrNull()
                    Tab(Icons.Filled.Home, "Home", root == Route.Home) { model.tab(Route.Home) }
                    Tab(Icons.Filled.Search, "Search", root == Route.Search) { model.tab(Route.Search) }
                    Tab(Icons.Filled.LibraryMusic, "Library", root == Route.Library) { model.tab(Route.Library) }
                    Tab(Icons.Filled.AccountCircle, "Accounts", root == Route.Accounts) { model.tab(Route.Accounts) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (restoring && route != Route.Accounts) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(160.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Signing back in…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                when (route) {
                    Route.Home -> HomeScreen(model)
                    Route.Search -> SearchScreen(model)
                    Route.Library -> LibraryScreen(model)
                    Route.Accounts -> AccountsScreen(model)
                    is Route.Album -> CollectionScreen(model, route.source, route.id, route.title, album = true)
                    is Route.Playlist -> CollectionScreen(model, route.source, route.id, route.title, album = false)
                    is Route.Artist -> ArtistScreen(model, route)
                    Route.NowPlaying, Route.YouTubeLogin -> {}
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label) },
    )
}

/** Re-reads the player's position a few times a second while it plays. */
@Composable
fun rememberPosition(state: PlayerState): Long {
    val position by produceState(state.positionNow(), state) {
        while (true) {
            value = state.positionNow()
            if (!state.playing) break
            delay(250)
        }
    }
    return position
}

@Composable
private fun MiniPlayer(state: PlayerState, model: AppModel) {
    val current = state.current ?: return
    val position = rememberPosition(state)
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { model.push(Route.NowPlaying) },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(current.track.cover, Modifier.size(44.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(current.track.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            } else {
                IconButton(onClick = { model.player.toggle() }) {
                    Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause")
                }
            }
            IconButton(onClick = { model.player.next() }) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}
