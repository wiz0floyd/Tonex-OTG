package dev.tonexotg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.tonexotg.app.ui.theme.TonexTheme

/**
 * Placeholder entry point only (S10 / issue #15, theme wiring by S15 / issue #20).
 *
 * This proves the AGP + Compose + Material 3 wiring resolves, compiles, and assembles end to
 * end, now through [TonexTheme] rather than a bare [MaterialTheme] default. It is intentionally
 * still not a real screen: the actual preset list / parameter editor screens are S16-S19, out
 * of scope for both S10 and S15. See `dev.tonexotg.app.ui.theme` and
 * `dev.tonexotg.app.ui.components` for the theme and component library those screens will use.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TonexTheme {
                Surface {
                    Text("Tonex-OTG")
                }
            }
        }
    }
}
