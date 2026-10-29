package org.sonora.android.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.sonora.android.core.AccountChoice
import org.sonora.android.core.SignInListener
import org.sonora.android.core.SignInStep
import org.sonora.android.core.Source

/**
 * Google sign-in for YouTube Music in a WebView. Once Google sets the session cookies, the whole
 * cookie header goes to the engine, which is what Sonora's desktop app does in its own window.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginScreen(model: AppModel, onDone: () -> Unit) {
    val login = remember { model.core.youtubeWebLogin() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<AccountChoice>?>(null) }

    fun cookiesFor(url: String): String? = CookieManager.getInstance().getCookie(url)

    fun tryFinish() {
        if (busy || login == null) return
        val header = listOfNotNull(
            cookiesFor("https://music.youtube.com"),
            cookiesFor("https://www.youtube.com"),
        ).joinToString("; ")
        val names = header.split(";").map { it.trim().substringBefore("=") }.toSet()
        if (login.proof.none { it in names }) return
        busy = true
        status = "Signing in to YouTube Music…"
        val listener = object : SignInListener {
            override fun onStep(step: SignInStep) {
                if (step is SignInStep.ChooseAccount) choices = step.choices
            }
        }
        scope.launch {
            try {
                val account = model.core.signInYoutubeCookies(dedupe(header), listener)
                model.signedIn(account)
                onDone()
            } catch (e: Exception) {
                status = e.message ?: "Sign-in failed"
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                model.core.cancelSignIn(Source.YOU_TUBE)
                onDone()
            }) { Icon(Icons.Filled.Close, "Close") }
            Text("Sign in to YouTube Music", style = MaterialTheme.typography.titleMedium)
        }
        status?.let {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(6.dp))
                }
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (login == null) {
            Message("This build has no YouTube sign-in.")
            return@Column
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in from an embedded WebView; it accepts the same
                        // engine when it presents itself as the phone's Chrome.
                        val agent = login.userAgent ?: settings.userAgentString
                            .replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+ "), "")
                        settings.userAgentString = agent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                if (url.contains(login.domain)) tryFinish()
                            }
                        }
                        loadUrl(login.url)
                    }
                },
            )
        }
    }

    choices?.let { list ->
        ChooseAccountDialog(list, onDismiss = { choices = null; model.core.cancelSignIn(Source.YOU_TUBE) }) {
            choices = null
            model.core.provideSignInInput(Source.YOU_TUBE, it.id)
        }
    }
}

/** Two cookie jars can repeat a name; the first one wins. */
private fun dedupe(header: String): String {
    val seen = LinkedHashMap<String, String>()
    header.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
        val name = pair.substringBefore("=")
        if (name !in seen) seen[name] = pair
    }
    return seen.values.joinToString("; ")
}
