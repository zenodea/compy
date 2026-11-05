package dev.compy.system

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.CompyApp
import dev.compy.rex.ServerConnection
import dev.compy.rex.WindowView
import dev.compy.ui.CompyTheme

/** Receives text shared from other apps and pastes it into a chosen terminal pane. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = (application as CompyApp).state
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrEmpty()) {
            finish()
            return
        }
        setContent {
            val settings by state.settings.collectAsStateWithLifecycle()
            CompyTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    PanePicker(state, text) { finish() }
                }
            }
        }
    }
}

@Composable
private fun PanePicker(app: AppState, text: String, onDone: () -> Unit) {
    val connections by app.connections.collectAsStateWithLifecycle()
    Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(top = 8.dp)) {
        Text("Paste into…", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            text.take(160),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        LazyColumn(Modifier.padding(top = 8.dp)) {
            connections.values.forEach { connection ->
                item(key = connection.config.id) { ServerPanes(connection, text, onDone) }
            }
        }
    }
}

@Composable
private fun ServerPanes(connection: ServerConnection, text: String, onDone: () -> Unit) {
    val sessions by connection.sessions.collectAsState()
    val views by connection.views.collectAsState()
    val titles by connection.titles.collectAsState()
    Column {
        Text(
            connection.config.label,
            Modifier.padding(start = 16.dp, top = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        for (session in sessions) {
            val windows: List<WindowView> = views[session.id]?.windows.orEmpty()
            for (window in windows) {
                val block = window.focusedBlockId ?: window.blocks.firstOrNull()?.id ?: continue
                ListItem(
                    headlineContent = { Text(window.displayTitle(titles)) },
                    supportingContent = { Text(session.label) },
                    modifier = Modifier.clickable {
                        connection.pasteInto(session.id, block, text)
                        onDone()
                    },
                )
            }
        }
    }
}
