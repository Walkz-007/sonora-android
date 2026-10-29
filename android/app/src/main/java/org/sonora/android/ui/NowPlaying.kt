package org.sonora.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.android.playback.RepeatMode

@Composable
fun NowPlayingScreen(model: AppModel, onClose: () -> Unit) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val current = state.current
    LaunchedEffect(current) { if (current == null) onClose() }
    current ?: return
    var showQueue by remember { mutableStateOf(false) }
    val position = rememberPosition(state)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val player = model.player

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                Text(
                    current.source.label(),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(if (showQueue) Icons.Filled.Close else Icons.AutoMirrored.Filled.QueueMusic, "Queue")
                }
            }

            if (showQueue) {
                QueueList(model, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(0.15f))
                Cover(current.track.cover, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
                Spacer(Modifier.weight(0.15f))
            }

            Text(
                current.track.title,
                Modifier.fillMaxWidth().padding(top = 16.dp),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                current.track.artists,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val duration = state.durationMs.coerceAtLeast(1)
            val fraction = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = fraction,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    player.seekTo((dragValue * duration).toLong())
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                val shown = if (dragging) (dragValue * duration).toLong() else position
                Text(formatDuration(shown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                state.format?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                }
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (state.shuffle) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { player.previous() }, Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.background, strokeWidth = 3.dp)
                    } else {
                        IconButton(onClick = { player.toggle() }, Modifier.size(72.dp)) {
                            Icon(
                                if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "Play or pause",
                                Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.background,
                            )
                        }
                    }
                }
                IconButton(onClick = { player.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
                }
                IconButton(onClick = { player.cycleRepeat() }) {
                    Icon(
                        if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (state.repeat != RepeatMode.OFF) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QueueList(model: AppModel, modifier: Modifier) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.index.coerceAtLeast(0))
    LazyColumn(modifier.fillMaxWidth(), state = list) {
        itemsIndexed(state.queue, key = { _, item -> item.key }) { index, item ->
            val actions = if (index == state.index) emptyList() else listOf(
                TrackAction("Remove from queue") { model.player.removeAt(index) },
            )
            TrackRow(
                item.track,
                onClick = { model.player.jumpTo(index) },
                playing = index == state.index,
                actions = actions,
            )
        }
    }
}

@Suppress("unused")
private val transparent = Color.Transparent
