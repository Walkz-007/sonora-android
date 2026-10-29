package org.sonora.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.sonora.android.core.Account
import org.sonora.android.core.AccountChoice
import org.sonora.android.core.SignInListener
import org.sonora.android.core.SignInStep
import org.sonora.android.core.Source

private val blurbs = mapOf(
    Source.LOCAL to "Music files on this phone.",
    Source.SUBSONIC to "Your Navidrome, Gonic or other Subsonic server.",
    Source.YOU_TUBE to "Browse and play YouTube Music, with or without a Google account.",
    Source.SPOTIFY to "Plays through librespot. Needs Spotify Premium.",
)

@Composable
fun AccountsScreen(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val restoring by model.restoring.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SourceBar(model, "Accounts")
        if (restoring) {
            Loading()
            return
        }
        if (accounts.isEmpty()) {
            Text(
                "Sonora plays music from the sources below. Sign in to one to start.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Source.entries.forEach { source ->
            SourceCard(model, source, accounts.firstOrNull { it.source == source })
        }
        Text(
            "Sonora for Android is an unofficial port of Sonora (github.com/sonorahq/sonora), GPL-3.0. " +
                "It is not affiliated with Spotify, Google or any service it plays from.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SourceCard(model: AppModel, source: Source, account: Account?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Account) {
        busy = true
        error = null
        scope.launch {
            try {
                model.signedIn(block())
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.label(), style = MaterialTheme.typography.titleMedium)
                    Text(blurbs[source] ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (account != null) Icon(Icons.Filled.CheckCircle, "Signed in", tint = Accent)
            }
            Spacer(Modifier.height(12.dp))
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (source == Source.LOCAL) "Scanning your music…" else "Signing in…")
                    if (source == Source.SPOTIFY) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { model.core.cancelSignIn(source) }) { Text("Cancel") }
                    }
                }
                account != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(account.avatar, Modifier.size(32.dp), CircleShape)
                    Spacer(Modifier.width(10.dp))
                    Text(account.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (source == Source.LOCAL) {
                        TextButton(onClick = { dialog = true }) { Text("Folders") }
                    }
                    OutlinedButton(onClick = { model.signOut(source) }) { Text("Sign out") }
                }
                else -> when (source) {
                    Source.LOCAL -> FilledTonalButton(onClick = { dialog = true }) { Text("Choose music folders") }
                    Source.SUBSONIC -> FilledTonalButton(onClick = { dialog = true }) { Text("Connect to a server") }
                    Source.YOU_TUBE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { run { model.core.signInYoutubeGuest() } }) { Text("Use as guest") }
                        OutlinedButton(onClick = { model.push(Route.YouTubeLogin) }) { Text("Sign in with Google") }
                    }
                    Source.SPOTIFY -> SpotifyButton(model) { run(it) }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (dialog && source == Source.SUBSONIC) {
        SubsonicDialog(onDismiss = { dialog = false }) { server, user, password ->
            dialog = false
            run { model.core.signInSubsonic(server, user, password) }
        }
    }
    if (dialog && source == Source.LOCAL) {
        LocalDialog(onDismiss = { dialog = false }) { folders ->
            dialog = false
            run { model.core.signInLocal(folders) }
        }
    }
}

@Composable
private fun SpotifyButton(model: AppModel, run: (suspend () -> Account) -> Unit) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<AccountChoice>?>(null) }
    FilledTonalButton(onClick = {
        val listener = object : SignInListener {
            override fun onStep(step: SignInStep) {
                when (step) {
                    is SignInStep.OpenUrl -> openBrowser(context, step.url)
                    is SignInStep.ShowCode -> openBrowser(context, step.url)
                    is SignInStep.ChooseAccount -> choices = step.choices
                }
            }
        }
        run { model.core.signInSpotify(listener) }
    }) { Text("Sign in with Spotify") }
    choices?.let { list ->
        ChooseAccountDialog(list, onDismiss = { choices = null }) {
            choices = null
            model.core.provideSignInInput(Source.SPOTIFY, it.id)
        }
    }
}

fun openBrowser(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    android.os.Handler(android.os.Looper.getMainLooper()).post { context.startActivity(intent) }
}

@Composable
fun ChooseAccountDialog(choices: List<AccountChoice>, onDismiss: () -> Unit, onChoose: (AccountChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an account") },
        text = {
            Column {
                choices.forEach { choice ->
                    TextButton(onClick = { onChoose(choice) }, Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.name)
                            choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SubsonicDialog(onDismiss: () -> Unit, onConnect: (String, String, String) -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subsonic server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(server, { server = it }, label = { Text("Server address") }, placeholder = { Text("https://music.example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = {
            Button(enabled = server.isNotBlank() && user.isNotBlank() && password.isNotEmpty(), onClick = { onConnect(server.trim(), user.trim(), password) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A folder picked through the system picker, as the file path the Rust scanner reads. */
private fun treeToPath(uri: Uri): String? {
    val id = DocumentsContract.getTreeDocumentId(uri)
    val (volume, path) = id.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val root = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    return if (path.isEmpty()) root else "$root/$path"
}

@Composable
private fun LocalDialog(onDismiss: () -> Unit, onScan: (List<String>) -> Unit) {
    val context = LocalContext.current
    val defaultFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
    var folders by remember { mutableStateOf(listOf(defaultFolder)) }
    var denied by remember { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onScan(folders) else denied = true
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeToPath)?.let { path -> if (path !in folders) folders = folders + path }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music folders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sonora scans these folders for audio files.", style = MaterialTheme.typography.bodySmall)
                folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { folders = folders - folder }) { Text("Remove") }
                    }
                }
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Add folder") }
                if (denied) {
                    Text(
                        "Sonora needs permission to read your music. Allow it in the app's settings.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = folders.isNotEmpty(), onClick = {
                val has = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                if (has) onScan(folders) else askPermission.launch(permission)
            }) { Text("Scan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
