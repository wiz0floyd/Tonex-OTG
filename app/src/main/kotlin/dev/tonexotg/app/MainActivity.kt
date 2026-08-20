package dev.tonexotg.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import dev.tonexotg.app.session.TonexSessionHolder
import dev.tonexotg.app.ui.navigation.TonexApp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.usb.connection.UsbConnectionService

/**
 * The real entry point (S23, issue #74; `docs/architecture/s23-ui-wiring.md` §3.5). Hosts
 * [TonexApp] -- the `NavHost` shell wiring the preset list, parameter editor, and about screens
 * to the single process-lifetime [TonexSessionHolder] -- inside [TonexTheme].
 *
 * This class never calls [dev.tonexotg.app.usb.connection.UsbConnectionManager.connectToAttachedDeviceIfPresent]
 * or `onDeviceAttached()` itself, and never touches [TonexSessionHolder.controller] directly
 * outside of what [TonexApp] does on its behalf. Every path to a live connection goes through
 * [UsbConnectionService] -- see doc §3.4/§3.5 for why: starting a session directly from here
 * would bypass the foreground-service gate that exists specifically to stop a pedal session from
 * ever running unprotected (issue #18).
 *
 * [onStart] (not [onCreate]) is what starts [UsbConnectionService], per doc §3.5: an `Activity` in
 * `onStart` is a foreground context, which is what makes `startForegroundService` legal under
 * API 31+'s background-start restriction. `START_STICKY` plus [UsbConnectionService]'s own
 * idempotent `startForeground` call makes repeat calls across rotations and re-`onStart`s
 * harmless.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionHolder = TonexSessionHolder.getInstance(this)
        setContent {
            TonexTheme {
                TonexApp(sessionHolder = sessionHolder)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.startForegroundService(this, Intent(this, UsbConnectionService::class.java))
    }
}
