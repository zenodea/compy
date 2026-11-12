package dev.compy.ui

import android.Manifest
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.compy.system.batteryExemptionIntent
import dev.compy.system.isIgnoringBatteryOptimizations
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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

private enum class Screen { Main, Board, Saved, Settings, AddServer }

/** Top-level navigation: the lock, the workspace, settings and adding a server. */
@Composable
fun CompyRoot(app: AppState, activity: FragmentActivity) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val locked by app.locked.collectAsStateWithLifecycle()
    val servers by app.servers.collectAsStateWithLifecycle()
    val canLock = rememberCanLock(activity)
    if (settings.biometricLock && canLock && locked) {
        LockScreen(app, activity)
        return
    }
    LaunchedEffect(Unit) { app.unlock() }

    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    // Kept here so returning from Settings lands back where you were.
    // Reopen where you left off; the session list is one back gesture away.
    var showList by rememberSaveable { mutableStateOf(app.selection.value == null) }
    var sidebarOpen by rememberSaveable { mutableStateOf(true) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbars.showSnackbar(message) } }

    val context = LocalContext.current
    val batteryPrompt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Without the exemption, phones can stop the connection that alerts depend on.
        if (granted && !settings.askedBatteryExemption && !isIgnoringBatteryOptimizations(context)) {
            app.updateSettings { it.copy(askedBatteryExemption = true) }
            batteryPrompt.launch(batteryExemptionIntent(context))
        }
    }
    // Ask once, after there's a server to be alerted about, rather than at first launch.
    LaunchedEffect(servers.isNotEmpty(), settings.backgroundAlerts) {
        if (servers.isNotEmpty() && settings.backgroundAlerts && !settings.askedNotificationPermission) {
            app.updateSettings { it.copy(askedNotificationPermission = true) }
            notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val current = if (servers.isEmpty()) Screen.AddServer else screen

    val connections by app.connections.collectAsStateWithLifecycle()
    val alertHost = remember { SnackbarHostState() }
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_BELOW_DP
    // Whether the selected session's panes are what's on screen: not Settings, nor the list on a phone.
    val workspaceShown = rememberUpdated(current == Screen.Main && !(compact && showList))
    // Tell people when something elsewhere needs them, unless they're already looking at it.
    // Watched here, above every screen, so an alert raised while Settings is open isn't lost.
    connections.values.forEach { c ->
        key(c.config.id) {
            LaunchedEffect(c) {
                c.alerts.collect { alert ->
                    val selected = app.selection.value
                    val window = c.views.value[alert.sessionId]?.activeWindow
                    val focused = workspaceShown.value && app.foreground.value &&
                        selected?.serverId == alert.serverId && selected.sessionId == alert.sessionId &&
                        window?.focusedBlockId == alert.blockId && window.layerOf(alert.blockId)?.minimized != true
                    if (alert.always || !focused) {
                        scope.launch {
                            val result = alertHost.showSnackbar(alert.text, actionLabel = if (focused) null else "Open", duration = SnackbarDuration.Short)
                            if (result == SnackbarResult.ActionPerformed) {
                                screen = Screen.Main
                                app.requestOpen(dev.compy.Selection(alert.serverId, alert.sessionId))
                                app.revealPane(c, alert.sessionId, alert.blockId)
                            }
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            current,
            transitionSpec = {
                // Going deeper slides in from the right, and going back reverses it.
                val forward = targetState.ordinal > initialState.ordinal
                val direction = if (forward) 1 else -1
                (slideInHorizontally(motion()) { direction * it / 4 } + fadeIn(motion(MOTION_SHORT))) togetherWith
                    (slideOutHorizontally(motion()) { -direction * it / 8 } + fadeOut(motion(MOTION_SHORT)))
            },
            label = "screen",
        ) { shown ->
            when (shown) {
                Screen.AddServer -> AddServerScreen(
                    app,
                    canGoBack = servers.isNotEmpty(),
                    onDone = { screen = Screen.Main },
                )
                Screen.Settings -> SettingsScreen(app, onBack = { screen = Screen.Main })
                Screen.Saved -> SavedPanes(app, onBack = { screen = Screen.Main })
                Screen.Board -> AgentBoard(
                    app,
                    onBack = { screen = Screen.Main },
                    onOpened = {
                        showList = false
                        screen = Screen.Main
                    },
                )
                Screen.Main -> MainLayout(
                    app,
                    showList = showList,
                    onShowList = { showList = it },
                    sidebarOpen = sidebarOpen,
                    onSidebarOpen = { sidebarOpen = it },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenBoard = { screen = Screen.Board },
                    onOpenSaved = { screen = Screen.Saved },
                    onAddServer = { screen = Screen.AddServer },
                    onMessage = showMessage,
                )
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { Snackbar(it) }
        SnackbarHost(alertHost, Modifier.align(Alignment.TopCenter).statusBarsPadding()) { Snackbar(it) }
    }
}

@Composable
private fun MainLayout(
    app: AppState,
    showList: Boolean,
    onShowList: (Boolean) -> Unit,
    sidebarOpen: Boolean,
    onSidebarOpen: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBoard: () -> Unit,
    onOpenSaved: () -> Unit,
    onAddServer: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val connections by app.connections.collectAsStateWithLifecycle()
    val selection by app.selection.collectAsStateWithLifecycle()
    val connection = selection?.let { connections[it.serverId] }
    val sessionId = selection?.sessionId
    val alertScope = rememberCoroutineScope()

    // The session may be ended from another device while it's open here.
    if (connection != null && sessionId != null) {
        val sessions by connection.sessions.collectAsStateWithLifecycle()
        val state by connection.state.collectAsStateWithLifecycle()
        LaunchedEffect(sessions, state, sessionId) {
            if (state == dev.compy.rex.ConnectionState.Connected && sessions.none { it.id == sessionId }) app.select(null)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < COMPACT_BELOW_DP.dp
        val sidebarWidth = if (maxWidth < 840.dp) 280.dp else 320.dp
        LaunchedEffect(Unit) {
            app.openRequests.collect {
                onShowList(false)
                // Handled once. Otherwise coming back from Settings replays it and leaves the list.
                app.openRequestHandled()
            }
        }
        LaunchedEffect(sessionId) { if (sessionId == null) onShowList(true) }
        if (compact) {
            val inWorkspace = !showList && connection != null && sessionId != null
            // Keep drawing the workspace while it slides away, even after its session has gone.
            var shownConnection by remember { mutableStateOf(connection) }
            var shownSession by remember { mutableStateOf(sessionId) }
            if (connection != null && sessionId != null) {
                shownConnection = connection
                shownSession = sessionId
            }
            // 1 shows the workspace and 0 the session list. The back gesture drives it directly.
            val position = remember { Animatable(if (inWorkspace) 1f else 0f) }
            var gesturing by remember { mutableStateOf(false) }
            LaunchedEffect(inWorkspace) {
                if (!gesturing) position.animateTo(if (inWorkspace) 1f else 0f, motion(MOTION_LONG))
            }
            PredictiveBackHandler(enabled = inWorkspace) { progress ->
                gesturing = true
                var committed = false
                try {
                    progress.collect { position.snapTo(1f - it.progress * 0.4f) }
                    committed = true
                    gesturing = false
                    // This disables the handler and ends this block; the effect above slides away.
                    onShowList(true)
                } catch (e: CancellationException) {
                    gesturing = false
                    if (!committed) alertScope.launch { position.animateTo(1f, motion(MOTION_MEDIUM)) }
                    throw e
                }
            }
            val listShown by remember { derivedStateOf { position.value < 1f } }
            val sliding by remember { derivedStateOf { position.value > 0f } }
            if (listShown) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -size.width * 0.18f * position.value
                            alpha = 1f - 0.3f * position.value
                        },
                ) {
                    Sidebar(
                        app, Modifier.fillMaxSize(),
                        onOpenSettings = onOpenSettings,
                        onOpenBoard = onOpenBoard,
                        onOpenSaved = onOpenSaved,
                        onAddServer = onAddServer,
                        onOpened = { onShowList(false) },
                        onMessage = onMessage,
                    )
                }
            }
            val workspaceConnection = shownConnection
            val workspaceSession = shownSession
            if ((inWorkspace || sliding) && workspaceConnection != null && workspaceSession != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = 1f - position.value
                            translationX = size.width * p
                            shape = RoundedCornerShape(28.dp * minOf(1f, p * 4f))
                            clip = p > 0f
                        },
                ) {
                    // Keyed, so nothing a workspace remembers or a gesture captured outlives its session.
                    key(workspaceConnection, workspaceSession) {
                        Workspace(app, workspaceConnection, workspaceSession, compact = true, onShowSidebar = { onShowList(true) }, onOpenSaved = onOpenSaved, onMessage = onMessage)
                    }
                }
            }
        } else {
            // One value drives both sides, so the sidebar's edge and the workspace stay locked
            // together. It's read only while placing them, so nothing recomposes as it moves.
            val open = remember { Animatable(if (sidebarOpen) 1f else 0f) }
            LaunchedEffect(sidebarOpen) { open.animateTo(if (sidebarOpen) 1f else 0f, motion(MOTION_LONG)) }
            val sidebarShown by remember { derivedStateOf { open.value > 0f } }
            Box(Modifier.fillMaxSize()) {
                if (sidebarShown) {
                    Box(
                        Modifier
                            .width(sidebarWidth)
                            .fillMaxHeight()
                            .graphicsLayer { translationX = -(1f - open.value) * size.width },
                    ) {
                        Sidebar(
                            app, Modifier.fillMaxWidth(),
                            onOpenSettings = onOpenSettings,
                            onOpenBoard = onOpenBoard,
                            onOpenSaved = onOpenSaved,
                            onAddServer = onAddServer,
                            onOpened = { onShowList(false) },
                            onMessage = onMessage,
                            onHideSidebar = { onSidebarOpen(false) },
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            val left = (sidebarWidth.toPx() * open.value).roundToInt()
                            val width = constraints.maxWidth - left
                            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, 0) }
                        },
                ) {
                    if (connection != null && sessionId != null) {
                        key(connection, sessionId) {
                            Workspace(
                                app, connection, sessionId, compact = false,
                                sidebarHidden = !sidebarOpen,
                                layoutMoving = open.isRunning,
                                onShowSidebar = { onSidebarOpen(true) },
                                onOpenSaved = onOpenSaved,
                                onMessage = onMessage,
                            )
                        }
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
        Icon(Icons.Rounded.Terminal, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Choose a window to open it here",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Narrower than this, the session list and the workspace take turns instead of sitting side by side. */
private const val COMPACT_BELOW_DP = 600

private const val LOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Whether this device has a fingerprint, face or screen lock to unlock Compy with. */
@Composable
internal fun rememberCanLock(activity: FragmentActivity): Boolean = remember(activity) {
    BiometricManager.from(activity).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}

/** Keeps a window out of screenshots and the recent apps list while the setting is on. */
@Composable
fun HideFromScreenshots(window: Window, hide: Boolean) {
    LaunchedEffect(window, hide) {
        if (hide) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Asks for the fingerprint, face or screen lock, and stays in the way until it's given. */
@Composable
internal fun LockScreen(app: AppState, activity: FragmentActivity) {
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
            Icon(Icons.Rounded.Lock, null, Modifier.size(48.dp))
            Text("Compy is locked", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { attempt++ }) { Text("Unlock") }
        }
    }
}
