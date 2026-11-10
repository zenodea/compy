package dev.compy

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.ui.CompyRoot
import dev.compy.ui.CompyTheme
import dev.compy.ui.HideFromScreenshots

class MainActivity : FragmentActivity() {
    private val state get() = (application as CompyApp).state

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not when restored or reopened from Recents: that delivers the first intent again, and
        // would jump back to the pane a notification opened long ago.
        val replayed = savedInstanceState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (!replayed) handleIntent(intent)
        setContent {
            val settings by state.settings.collectAsStateWithLifecycle()
            val hostColors by state.appColors.collectAsStateWithLifecycle()
            HideFromScreenshots(window, settings.blockScreenshots)
            CompyTheme(settings, hostColors) {
                CompyRoot(state, this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
