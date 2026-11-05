package dev.compy.ui

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import kotlinx.coroutines.launch

private enum class Screen { Main, Settings, AddServer }

/** Top-level navigation: the lock, the workspace, settings and adding a server. */
@Composable
fun CompyRoot(app: AppState, activity: FragmentActivity) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val locked by app.locked.collectAsStateWithLifecycle()
    val servers by app.servers.collectAsStateWithLifecycle()
    val canLock = remember(activity) {
        BiometricManager.from(activity).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }
    if (settings.biometricLock && canLock && locked) {
        LockScreen(app, activity)
        return
    }
    LaunchedEffect(Unit) { app.unlock() }

    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbars.showSnackbar(message) } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        when {
            servers.isEmpty() || screen == Screen.AddServer -> AddServerScreen(
                app,
                canGoBack = servers.isNotEmpty(),
                onDone = { screen = Screen.Main },
            )
            screen == Screen.Settings -> SettingsScreen(app, onBack = { screen = Screen.Main })
            else -> MainLayout(app, onOpenSettings = { screen = Screen.Settings }, onAddServer = { screen = Screen.AddServer }, onMessage = showMessage)
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { Snackbar(it) }
    }
}

@Composable
private fun MainLayout(app: AppState, onOpenSettings: () -> Unit, onAddServer: () -> Unit, onMessage: (String) -> Unit) {
    val connections by app.connections.collectAsStateWithLifecycle()
    val selection by app.selection.collectAsStateWithLifecycle()
    val connection = selection?.let { connections[it.serverId] }
    val sessionId = selection?.sessionId

    // The session may be ended from another device while it's open here.
    if (connection != null && sessionId != null) {
        val sessions by connection.sessions.collectAsStateWithLifecycle()
        val state by connection.state.collectAsStateWithLifecycle()
        LaunchedEffect(sessions, state) {
            if (state == dev.compy.rex.ConnectionState.Connected && sessions.none { it.id == sessionId }) app.select(null)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val sidebarWidth = if (maxWidth < 840.dp) 280.dp else 320.dp
        var sidebarOpen by rememberSaveable { mutableStateOf(true) }
        var showList by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(sessionId) { if (sessionId == null) showList = true }
        if (compact) {
            val inWorkspace = !showList && connection != null && sessionId != null
            BackHandler(enabled = inWorkspace) { showList = true }
            if (inWorkspace) {
                Workspace(app, connection!!, sessionId!!, compact = true, onShowSidebar = { showList = true }, onMessage = onMessage)
            } else {
                Sidebar(
                    app, Modifier.fillMaxSize(),
                    onOpenSettings = onOpenSettings,
                    onAddServer = onAddServer,
                    onOpened = { showList = false },
                    onMessage = onMessage,
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    sidebarOpen,
                    enter = expandHorizontally(tween(220)),
                    exit = shrinkHorizontally(tween(220)),
                ) {
                    Sidebar(
                        app, Modifier.width(sidebarWidth),
                        onOpenSettings = onOpenSettings,
                        onAddServer = onAddServer,
                        onOpened = {},
                        onMessage = onMessage,
                    )
                }
                Box(Modifier.weight(1f)) {
                    if (connection != null && sessionId != null) {
                        Workspace(app, connection, sessionId, compact = false, onShowSidebar = { sidebarOpen = !sidebarOpen }, onMessage = onMessage)
                    } else {
                        EmptyWorkspace()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkspace() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Terminal, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Choose a window to open it here",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val LOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

@Composable
private fun LockScreen(app: AppState, activity: FragmentActivity) {
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    app.unlock()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Compy")
                .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                .build(),
        )
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(48.dp))
            Text("Compy is locked", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { attempt++ }) { Text("Unlock") }
        }
    }
}
