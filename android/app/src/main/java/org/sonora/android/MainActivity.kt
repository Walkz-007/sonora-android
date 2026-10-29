package org.sonora.android

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.sonora.android.playback.PlaybackService
import org.sonora.android.ui.AppModel
import org.sonora.android.ui.SonoraRoot
import org.sonora.android.ui.SonoraTheme

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SonoraApp
        setContent {
            SonoraTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val error = app.startupError
                    if (error != null) {
                        Box(Modifier.padding(24.dp)) {
                            Column {
                                Text("Sonora couldn't start its engine.", style = MaterialTheme.typography.titleLarge)
                                Text(error, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        SonoraRoot(AppModel.get(app))
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if ((application as SonoraApp).startupError != null) return
        // Binding a controller starts the playback service, which keeps music going in the
        // background and owns the notification.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }
}
