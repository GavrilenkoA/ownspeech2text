package app.murmur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.murmur.feature.onboarding.OnboardingScreen
import app.murmur.platform.a11y.A11yStatus
import app.murmur.shell.DeepLink
import app.murmur.ui.design.MurmurTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The only activity. Shows onboarding until Settings.onboardingDone, then the shell. Deep links from the bubble and the
 * notification ([DeepLink] extras) arrive in onCreate or, while it runs (singleTop), in onNewIntent; the shell consumes
 * them once (a re-created activity keeps its restored back stack instead of replaying the launch intent).
 */
class MainActivity : ComponentActivity() {
    private val links = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) links.value = DeepLink.parse(intent)
        val container = checkNotNull((application as MurmurApp).container) { "MainActivity runs in :main" }
        setContent {
            MurmurTheme {
                Root(container)
            }
        }
    }

    /**
     * Every return to the app: a trip to the accessibility settings ends here (A11yStatus, ADR-054; it survives process
     * death), then the permission reading is refreshed before any screen's own resume effect reads it.
     */
    override fun onResume() {
        super.onResume()
        A11yStatus.get(this).onReturnedToApp()
        (application as MurmurApp).container?.permissions?.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.parse(intent)?.let { links.value = it }
    }

    @Composable
    private fun Root(container: AppContainer) {
        // Null until the stored settings are read, so that onboarding never flashes for a set-up user.
        val done = produceState<Boolean?>(null, container) {
            container.settings.current()
            container.settings.settings.collect { value = it.onboardingDone }
        }.value
        when (done) {
            null -> Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg).testTag("app-loading"))
            false -> OnboardingScreen(container.onboardingDeps, onDone = {})
            true -> AppShell(container.shellDeps, links, onLinkHandled = { links.value = null }, onExit = { finish() })
        }
    }
}
