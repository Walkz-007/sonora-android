package org.sonora.android

import android.app.Application
import android.util.Log
import org.sonora.android.core.Core
import org.sonora.android.playback.PlayerController

/** Loads the Rust engine once per process and keeps the one player controller. */
class SonoraApp : Application() {
    lateinit var core: Core
        private set
    lateinit var player: PlayerController
        private set
    var startupError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            System.loadLibrary("sonora_core")
            NativeInit.initVerifier(this)
            core = Core(filesDir.absolutePath, cacheDir.absolutePath)
        } catch (error: Throwable) {
            Log.e("sonora", "cannot start the engine", error)
            startupError = error.toString()
            return
        }
        player = PlayerController(this, core)
    }

    companion object {
        lateinit var instance: SonoraApp
            private set
    }
}

/** The one call into Rust that bypasses UniFFI, see core/src/jni_init.rs. */
object NativeInit {
    @JvmStatic
    external fun initVerifier(context: android.content.Context)
}
