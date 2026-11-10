package dev.compy.system

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.CompyApp
import dev.compy.rex.ServerConnection
import dev.compy.rex.UploadFile
import dev.compy.rex.WindowView
import dev.compy.ui.CompyTheme
import dev.compy.ui.HideFromScreenshots
import dev.compy.ui.LockScreen
import dev.compy.ui.rememberCanLock
import kotlinx.coroutines.launch

/**
 * Receives text or files shared from other apps. Text is pasted into a chosen terminal pane. Files
 * are uploaded to that pane's machine, and Rex types their paths into it.
 */
class ShareActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = (application as CompyApp).state
        val uris = sharedUris(intent)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.isEmpty() && text.isNullOrEmpty()) {
            finish()
            return
        }
        setContent {
            val settings by state.settings.collectAsStateWithLifecycle()
            val hostColors by state.appColors.collectAsStateWithLifecycle()
            val locked by state.locked.collectAsStateWithLifecycle()
            val canLock = rememberCanLock(this@ShareActivity)
            HideFromScreenshots(window, settings.blockScreenshots)
            CompyTheme(settings, hostColors) {
                // This lists sessions and types into a terminal, so it needs the same unlock as
                // opening Compy does.
                if (settings.biometricLock && canLock && locked) {
                    LockScreen(state, this@ShareActivity)
                } else {
                    Surface(Modifier.fillMaxSize()) {
                        if (uris.isNotEmpty()) FileShare(state, uris) { finish() }
                        else PanePicker(state, "Paste into…", text.orEmpty()) { connection, session, block ->
                            // The activity is gone by the time this could fail, so say so in a toast.
                            val app = applicationContext
                            connection.pasteInto(session, block, text.orEmpty()) { reason ->
                                mainExecutor.execute { Toast.makeText(app, reason, Toast.LENGTH_LONG).show() }
                            }
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        @Suppress("DEPRECATION")
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                ).orEmpty()
            else -> emptyList()
        }
    }
}

/** Picks a pane, then uploads the shared files there, staying open until they've arrived. */
@Composable
private fun FileShare(app: AppState, uris: List<Uri>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<UploadFile>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(uris) {
        files = runCatching { uploadFiles(context, uris) }.getOrElse {
            failed = it.message ?: "Couldn't read the shared files"
            emptyList()
        }
    }
    val ready = files
    when {
        failed != null -> Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Upload failed", style = MaterialTheme.typography.titleLarge)
            Text(failed.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onDone) { Text("Close") }
        }
        ready == null -> Unit
        target != null -> Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Uploading ${uploadLabel(ready)}", style = MaterialTheme.typography.titleLarge)
            Text("to $target", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        }
        else -> PanePicker(app, "Upload to…", ready.joinToString(", ") { it.name }) { connection, session, block ->
            target = connection.label.value
            scope.launch {
                try {
                    connection.upload(session, block, ready) { sent, total ->
                        progress = if (total > 0) sent.toFloat() / total else 1f
                    }
                    Toast.makeText(context, "Uploaded ${uploadLabel(ready)}", Toast.LENGTH_SHORT).show()
                    onDone()
                } catch (e: Exception) {
                    failed = e.message ?: "The upload didn't finish"
                }
            }
        }
    }
}

@Composable
private fun PanePicker(
    app: AppState,
    title: String,
    preview: String,
    onPick: (ServerConnection, sessionId: String, blockId: String) -> Unit,
) {
    val connections by app.connections.collectAsStateWithLifecycle()
    Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(top = 8.dp)) {
        Text(title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            preview.take(160),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        LazyColumn(Modifier.padding(top = 8.dp)) {
            connections.values.forEach { connection ->
                item(key = connection.config.id) { ServerPanes(connection, onPick) }
            }
        }
    }
}

@Composable
private fun ServerPanes(connection: ServerConnection, onPick: (ServerConnection, String, String) -> Unit) {
    val sessions by connection.sessions.collectAsState()
    val views by connection.views.collectAsState()
    val titles by connection.titles.collectAsState()
    Column {
        Text(
            connection.label.collectAsState().value,
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
                    modifier = Modifier.clickable { onPick(connection, session.id, block) },
                )
            }
        }
    }
}
