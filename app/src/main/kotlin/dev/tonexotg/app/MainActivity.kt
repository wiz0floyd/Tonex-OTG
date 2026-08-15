package dev.tonexotg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

/**
 * Placeholder entry point only (S10 / issue #15).
 *
 * This exists solely to prove the AGP + Compose + Material 3 wiring resolves, compiles, and
 * assembles end to end. It is intentionally not a real screen: the design gate has not been
 * signed off, and real UI is out of scope until S15-S19.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("Tonex-OTG")
                }
            }
        }
    }
}
