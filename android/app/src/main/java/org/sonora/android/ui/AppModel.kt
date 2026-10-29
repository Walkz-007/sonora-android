package org.sonora.android.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sonora.android.SonoraApp
import org.sonora.android.core.Account
import org.sonora.android.core.Source

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data object Accounts : Route
    data object NowPlaying : Route
    data object YouTubeLogin : Route
    data class Album(val source: Source, val id: String, val title: String) : Route
    data class Playlist(val source: Source, val id: String, val title: String) : Route
    data class Artist(val source: Source, val id: String, val name: String) : Route
}

fun Source.label(): String = when (this) {
    Source.LOCAL -> "Local files"
    Source.SUBSONIC -> "Subsonic"
    Source.YOU_TUBE -> "YouTube Music"
    Source.SPOTIFY -> "Spotify"
}

/** App-wide state: accounts, the chosen source and the screen stack. Lives as long as the process. */
class AppModel(val app: SonoraApp) {
    val core get() = app.core
    val player get() = app.player

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts

    private val _source = MutableStateFlow<Source?>(null)
    val source: StateFlow<Source?> = _source

    private val _restoring = MutableStateFlow(true)
    val restoring: StateFlow<Boolean> = _restoring

    val stack = mutableStateListOf<Route>(Route.Home)

    suspend fun restore() {
        if (!_restoring.value) return
        val restored = core.restore()
        _accounts.value = restored
        val preferred = prefs().getString(SOURCE_KEY, null)
            ?.let { name -> Source.entries.firstOrNull { it.name == name } }
        _source.value = restored.firstOrNull { it.source == preferred }?.source ?: restored.firstOrNull()?.source
        _restoring.value = false
        if (restored.isEmpty()) stack.apply { clear(); add(Route.Accounts) }
    }

    fun refreshAccounts() {
        val accounts = core.accounts()
        _accounts.value = accounts
        if (accounts.none { it.source == _source.value }) {
            _source.value = accounts.firstOrNull()?.source
        }
    }

    fun select(source: Source) {
        _source.value = source
        prefs().edit().putString(SOURCE_KEY, source.name).apply()
    }

    fun signedIn(account: Account) {
        refreshAccounts()
        select(account.source)
    }

    fun signOut(source: Source) {
        if (player.state.value.current?.source == source) player.stop()
        core.signOut(source)
        refreshAccounts()
    }

    fun push(route: Route) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun tab(route: Route) {
        stack.clear()
        stack.add(route)
    }

    private fun prefs() = app.getSharedPreferences("sonora", Context.MODE_PRIVATE)

    companion object {
        private const val SOURCE_KEY = "source"
        private var instance: AppModel? = null
        fun get(app: SonoraApp): AppModel = instance ?: AppModel(app).also { instance = it }
    }
}
