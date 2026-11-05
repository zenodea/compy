package dev.compy

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.ui.CompyRoot
import dev.compy.ui.CompyTheme

class MainActivity : FragmentActivity() {
    private val state get() = (application as CompyApp).state

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val settings by state.settings.collectAsStateWithLifecycle()
            val hostColors by state.appColors.collectAsStateWithLifecycle()
            LaunchedEffect(settings.blockScreenshots) {
                if (settings.blockScreenshots) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            CompyTheme(settings, hostColors) {
                CompyRoot(state, this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: return
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val block = intent.getStringExtra(EXTRA_BLOCK)
        if (block != null) {
            state.notifier.cancel(block)
            state.openBlock(server, session, block)
        } else {
            state.requestOpen(Selection(server, session))
        }
    }

    companion object {
        const val EXTRA_SERVER = "server"
        const val EXTRA_SESSION = "session"
        const val EXTRA_BLOCK = "block"
    }
}
