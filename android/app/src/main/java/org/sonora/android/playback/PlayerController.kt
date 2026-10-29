package org.sonora.android.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sonora.android.core.Core
import org.sonora.android.core.PlayerEvent
import org.sonora.android.core.PlayerListener
import org.sonora.android.core.Source
import org.sonora.android.core.TrackItem

data class QueueItem(val source: Source, val track: TrackItem, val key: Long)

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Where playback was at [positionAt], an elapsedRealtime timestamp. */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    val durationMs: Long = 0,
    val format: String? = null,
    val error: String? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val autoplay: Boolean = true,
) {
    val current: QueueItem? get() = queue.getOrNull(index)

    /** The position now, run forward from the last report while playing. */
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
        val run = if (playing && !loading) now - positionAt else 0
        val position = positionMs + run
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}

/**
 * The queue and transport, in Kotlin. Rust plays one track at a time; this decides what comes
 * next. Every method runs on the main thread, and engine events are posted there.
 */
class PlayerController(private val context: Context, private val core: Core) : PlayerListener {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Called after every state change, for the media session to follow. */
    var onChanged: (() -> Unit)? = null

    private var nextKey = 0L
    private var preloadedKey: Long? = null
    private var fetchingRadio = false
    /** The original order, kept while shuffle is on so turning it off restores it. */
    private var unshuffled: List<QueueItem>? = null

    init {
        core.setListener(this)
    }

    fun playTracks(source: Source, tracks: List<TrackItem>, start: Int = 0, shuffle: Boolean = false) {
        val chosen = tracks.getOrNull(start)
        val items = tracks.filter { it.playable }.map { QueueItem(source, it, nextKey++) }
        if (items.isEmpty()) return
        var queue = items
        var index = chosen?.let { c -> items.indexOfFirst { it.track.id == c.id } }?.coerceAtLeast(0) ?: 0
        unshuffled = null
        if (shuffle) {
            unshuffled = items
            val first = items[index]
            queue = listOf(first) + (items - first).shuffled()
            index = 0
        }
        _state.update {
            it.copy(queue = queue, index = index, shuffle = shuffle, error = null, format = null)
        }
        loadCurrent(0, seamless = false)
    }

    fun playNext(source: Source, track: TrackItem) {
        if (!track.playable) return
        val item = QueueItem(source, track, nextKey++)
        val state = _state.value
        if (state.current == null) {
            playTracks(source, listOf(track))
            return
        }
        val queue = state.queue.toMutableList().apply { add(state.index + 1, item) }
        preloadedKey = null
        _state.update { it.copy(queue = queue) }
        changed()
    }

    fun addToQueue(source: Source, track: TrackItem) {
        if (!track.playable) return
        if (_state.value.current == null) {
            playTracks(source, listOf(track))
            return
        }
        _state.update { it.copy(queue = it.queue + QueueItem(source, track, nextKey++)) }
        changed()
    }

    fun jumpTo(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index, error = null) }
        loadCurrent(0, seamless = false)
    }

    fun removeAt(index: Int) {
        val state = _state.value
        if (index !in state.queue.indices || index == state.index) return
        val queue = state.queue.toMutableList().apply { removeAt(index) }
        val current = if (index < state.index) state.index - 1 else state.index
        preloadedKey = null
        _state.update { it.copy(queue = queue, index = current) }
        changed()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        val current = _state.value.current ?: return
        if (_state.value.loading) return
        core.play(current.source)
        // Optimistic, so the button answers at once; the engine confirms with an event.
        _state.update { it.copy(playing = true, positionAt = SystemClock.elapsedRealtime()) }
        changed()
    }

    fun pause() {
        val current = _state.value.current ?: return
        core.pause(current.source)
        val now = SystemClock.elapsedRealtime()
        _state.update { it.copy(playing = false, positionMs = it.positionNow(now), positionAt = now) }
        changed()
    }

    fun seekTo(positionMs: Long) {
        val current = _state.value.current ?: return
        val target = positionMs.coerceAtLeast(0)
        core.seek(current.source, target.toULong())
        _state.update { it.copy(positionMs = target, positionAt = SystemClock.elapsedRealtime()) }
        changed()
    }

    fun next() {
        val state = _state.value
        when {
            state.index + 1 < state.queue.size -> {
                _state.update { it.copy(index = it.index + 1, error = null) }
                loadCurrent(0, seamless = false)
            }
            state.repeat == RepeatMode.ALL && state.queue.isNotEmpty() -> {
                _state.update { it.copy(index = 0, error = null) }
                loadCurrent(0, seamless = false)
            }
            else -> continueWithRadio(seamless = false)
        }
    }

    fun previous() {
        val state = _state.value
        if (state.positionNow() > 4000 || state.index <= 0) {
            seekTo(0)
            return
        }
        _state.update { it.copy(index = it.index - 1, error = null) }
        loadCurrent(0, seamless = false)
    }

    fun cycleRepeat() {
        _state.update {
            it.copy(repeat = when (it.repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            })
        }
        preloadedKey = null
        changed()
    }

    fun toggleShuffle() {
        val state = _state.value
        val current = state.current ?: return
        if (state.shuffle) {
            val original = unshuffled ?: state.queue
            val index = original.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
            unshuffled = null
            _state.update { it.copy(queue = original, index = index, shuffle = false) }
        } else {
            unshuffled = state.queue
            val rest = (state.queue - current).shuffled()
            _state.update { it.copy(queue = listOf(current) + rest, index = 0, shuffle = true) }
        }
        preloadedKey = null
        changed()
    }

    fun setAutoplay(on: Boolean) {
        _state.update { it.copy(autoplay = on) }
    }

    fun stop() {
        _state.value.current?.let { core.pause(it.source) }
        _state.update { PlayerState(repeat = it.repeat, autoplay = it.autoplay) }
        unshuffled = null
        changed()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // Engine events arrive on Rust threads.
    override fun onEvent(source: Source, event: PlayerEvent) {
        main.post { handle(source, event) }
    }

    private fun handle(source: Source, event: PlayerEvent) {
        val current = _state.value.current ?: return
        if (source != current.source) return
        val now = SystemClock.elapsedRealtime()
        val currentId = current.track.id
        fun mine(id: String?) = id == null || id == currentId
        when (event) {
            is PlayerEvent.Loading -> if (mine(event.id)) {
                _state.update { it.copy(loading = true) }
            }
            is PlayerEvent.Playing -> if (mine(event.id)) {
                _state.update {
                    it.copy(playing = true, loading = false, positionMs = event.positionMs.toLong(), positionAt = now)
                }
            }
            is PlayerEvent.Paused -> if (mine(event.id)) {
                _state.update {
                    it.copy(playing = false, loading = false, positionMs = event.positionMs.toLong(), positionAt = now)
                }
            }
            is PlayerEvent.Position -> if (mine(event.id)) {
                _state.update { it.copy(positionMs = event.positionMs.toLong(), positionAt = now) }
                maybePreload()
            }
            is PlayerEvent.Seeked -> if (mine(event.id)) {
                _state.update { it.copy(positionMs = event.positionMs.toLong(), positionAt = now) }
            }
            is PlayerEvent.Length -> if (mine(event.id)) {
                _state.update { it.copy(durationMs = event.durationMs.toLong()) }
            }
            is PlayerEvent.Format -> if (event.id == currentId) {
                _state.update { it.copy(format = event.description) }
            }
            is PlayerEvent.Ended -> if (mine(event.id)) onEnded()
            is PlayerEvent.Unavailable -> if (mine(event.id)) {
                _state.update { it.copy(error = "“${current.track.title}” is unavailable, skipped") }
                next()
            }
            is PlayerEvent.Throttled -> if (mine(event.id)) {
                _state.update { it.copy(loading = false, playing = false, error = "The service is limiting requests. Try again shortly.") }
            }
            is PlayerEvent.Refused -> {
                _state.update {
                    it.copy(loading = false, playing = false, error = "Playback was refused. Spotify needs Premium, and only one device can play at a time.")
                }
            }
        }
        changed()
    }

    private fun onEnded() {
        val state = _state.value
        when {
            state.repeat == RepeatMode.ONE -> loadCurrent(0, seamless = false)
            state.index + 1 < state.queue.size -> {
                _state.update { it.copy(index = it.index + 1) }
                loadCurrent(0, seamless = true)
            }
            state.repeat == RepeatMode.ALL -> {
                _state.update { it.copy(index = 0) }
                loadCurrent(0, seamless = true)
            }
            else -> continueWithRadio(seamless = true)
        }
    }

    /** At the end of the queue, a station seeded by the last track keeps the music going. */
    private fun continueWithRadio(seamless: Boolean) {
        val state = _state.value
        val last = state.current ?: return
        val account = core.accounts().firstOrNull { it.source == last.source }
        if (!state.autoplay || account?.radio != true || fetchingRadio) {
            _state.update {
                it.copy(playing = false, loading = false, positionMs = it.durationMs, positionAt = SystemClock.elapsedRealtime())
            }
            changed()
            return
        }
        fetchingRadio = true
        scope.launch {
            try {
                val known = _state.value.queue.map { it.track.id }.toSet()
                val more = core.radio(last.source, last.track.id)
                    .filter { it.playable && it.id !in known }
                    .map { QueueItem(last.source, it, nextKey++) }
                if (more.isEmpty()) {
                    _state.update { it.copy(playing = false) }
                } else {
                    _state.update { it.copy(queue = it.queue + more, index = it.index + 1) }
                    loadCurrent(0, seamless)
                }
            } catch (error: Exception) {
                Log.w("sonora", "radio failed", error)
                _state.update { it.copy(playing = false, error = "Couldn't continue with radio: ${error.message}") }
            } finally {
                fetchingRadio = false
                changed()
            }
        }
    }

    private fun maybePreload() {
        val state = _state.value
        if (state.durationMs <= 0 || state.repeat == RepeatMode.ONE) return
        if (state.durationMs - state.positionMs > 20_000) return
        val next = state.queue.getOrNull(state.index + 1)
            ?: if (state.repeat == RepeatMode.ALL) state.queue.firstOrNull() else null
        next ?: return
        if (preloadedKey == next.key || next.source != state.current?.source) return
        preloadedKey = next.key
        try {
            core.preload(next.source, next.track.id, true)
        } catch (error: Exception) {
            Log.w("sonora", "preload failed", error)
        }
    }

    private fun loadCurrent(positionMs: Long, seamless: Boolean) {
        val current = _state.value.current ?: return
        preloadedKey = null
        _state.update {
            it.copy(
                loading = true, playing = true, positionMs = positionMs,
                positionAt = SystemClock.elapsedRealtime(),
                durationMs = current.track.durationMs.toLong(), format = null,
            )
        }
        try {
            core.load(current.source, current.track.id, positionMs.toULong(), seamless)
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, playing = false, error = error.message ?: "Cannot play this track") }
        }
        changed()
    }

    private fun changed() {
        onChanged?.invoke()
    }
}
