package dev.compy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.compy.data.SavedServer
import dev.compy.data.SavedTheme
import dev.compy.data.Settings
import dev.compy.data.SettingsStore
import dev.compy.rex.ConnectionState
import dev.compy.rex.ServerConfig
import dev.compy.system.AlertNotifier
import dev.compy.system.Shortcuts
import dev.compy.system.StatusPublisher
import dev.compy.system.WatchService
import dev.compy.rex.ServerConnection
import dev.compy.rex.TerminalColors
import dev.compy.term.TerminalFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the workspace is showing. */
data class Selection(val serverId: String, val sessionId: String)

/** App-wide state: saved servers, live connections and the current selection. */
class AppState(private val context: Context) : DefaultLifecycleObserver {
    val store = SettingsStore(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        // Rex's server pings every 120 seconds. Pinging on the same schedule notices a dead server.
        .pingInterval(120, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    val settings: StateFlow<Settings> = store.settings.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { store.settings.first() },
    )
    val servers: StateFlow<List<SavedServer>> = store.servers.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { store.servers.first() },
    )

    private val _connections = MutableStateFlow<Map<String, ServerConnection>>(emptyMap())
    val connections: StateFlow<Map<String, ServerConnection>> = _connections.asStateFlow()

    private val hostThemeRevision = MutableStateFlow(0)

    private val _selection = MutableStateFlow(
        settings.value.let { s ->
            val server = s.lastServerId
            val session = s.lastSessionId
            if (server != null && session != null) Selection(server, session) else null
        },
    )
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _fonts = MutableStateFlow(TerminalFonts.bundled(context))
    val fonts: StateFlow<TerminalFonts> = _fonts.asStateFlow()

    /** Fires when something outside the sidebar, such as a shortcut, opens a session. */
    private val _openRequests = MutableSharedFlow<Selection>(replay = 1, extraBufferCapacity = 1)
    val openRequests: SharedFlow<Selection> = _openRequests.asSharedFlow()

    /** Colors for the app's own interface, or null to use wallpaper colors. */
    val appColors: StateFlow<TerminalColors?> = combine(settings, selection, hostThemeRevision) { s, sel, _ ->
        if (s.appColors != "host") return@combine null
        when (s.terminalTheme) {
            "rex" -> {
                val serverId = sel?.serverId ?: _connections.value.keys.firstOrNull()
                _connections.value[serverId]?.hostTheme?.value ?: serverId?.let { s.hostThemes[it]?.toColors() }
            }
            "material" -> null
            else -> dev.compy.ui.TerminalThemes.fixedColors(s.terminalTheme)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val notifier = AlertNotifier(context, this)

    private var pauseJob: Job? = null
    private var leftAt = 0L

    init {
        scope.launch { servers.collect(::syncConnections) }
        // React when alerts are switched on or off, or the first server is added.
        scope.launch {
            settings.map { it.backgroundAlerts }.distinctUntilChanged().drop(1).collect { if (_foreground.value) syncService() }
        }
        scope.launch { servers.map { it.isNotEmpty() }.distinctUntilChanged().drop(1).collect { if (_foreground.value) syncService() } }
        scope.launch {
            settings.collect { s ->
                val font = s.customFontPath?.let { TerminalFonts.fromFile(context, File(it)) } ?: TerminalFonts.bundled(context)
                if (_fonts.value !== font) _fonts.value = font
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        StatusPublisher(context, this).start()
        scope.launch {
            connections.collectLatest { map ->
                coroutineScope {
                    map.values.forEach { c ->
                        settings.value.hostThemes[c.config.id]?.let { c.seedHostTheme(it.toColors()) }
                        hostThemeRevision.value++
                        launch { watchHost(c) }
                    }
                }
            }
        }
        scope.launch {
            connections.collectLatest { map ->
                coroutineScope {
                    map.values.forEach { c ->
                        launch {
                            c.clipboard.collect { text ->
                                if (_foreground.value) {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Terminal", text))
                                }
                            }
                        }
                    }
                }
            }
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (_foreground.value || settings.value.backgroundAlerts) {
                        _connections.value.values.forEach { it.retryNow() }
                    }
                }
            },
        )
    }

    private fun syncConnections(saved: List<SavedServer>) {
        val current = _connections.value
        val next = saved.associate { s ->
            val existing = current[s.id]
            s.id to if (existing != null && existing.config.url == s.url && existing.config.label == s.label) {
                existing
            } else {
                existing?.shutdown()
                ServerConnection(
                    ServerConfig(s.id, s.label, s.url), http, scope,
                    clientLabel = Build.MODEL ?: "Android",
                    clientVersion = BuildConfigVersion,
                ).also { if (_foreground.value || watching) it.connect() }
            }
        }
        current.filterKeys { it !in next }.values.forEach { it.shutdown() }
        _connections.value = next
    }

    private suspend fun watchHost(c: ServerConnection) = coroutineScope {
        launch {
            c.hostTheme.filterNotNull().collect { colors ->
                hostThemeRevision.value++
                val saved = colors.toSaved()
                if (settings.value.hostThemes[c.config.id] != saved) {
                    updateSettings { it.copy(hostThemes = it.hostThemes + (c.config.id to saved)) }
                }
            }
        }
        // A host we've never seen needs one terminal opened to learn its colors.
        launch { c.selections.collect { requestOpen(Selection(c.config.id, it)) } }
        launch { notifier.watch(c) }
        launch { c.views.collect { if (it.isNotEmpty() && c.config.id !in settings.value.hostThemes) c.learnHostTheme() } }
    }

    private fun SavedTheme.toColors() = TerminalColors(
        foreground, background, cursor,
        TerminalColors.BUILTIN_PALETTE.copyOf().also { p -> ansi.forEachIndexed { i, v -> if (i < 16) p[i] = v } },
        scheme,
    )

    private fun TerminalColors.toSaved() = SavedTheme(
        foreground, background, cursor,
        (palette ?: TerminalColors.BUILTIN_PALETTE).take(16),
        scheme,
    )

    /** True while the background service keeps connections open with the app closed. */
    @Volatile var watching = false
        private set

    /** Called by the background service when it starts and stops. */
    fun setWatching(active: Boolean) {
        watching = active
        if (active) _connections.value.values.forEach { it.connect() }
    }

    /** Opens a specific pane, such as from a notification, once its server is connected. */
    fun openBlock(serverId: String, sessionId: String, blockId: String) {
        requestOpen(Selection(serverId, sessionId))
        scope.launch {
            val connection = _connections.value[serverId] ?: return@launch
            withTimeoutOrNull(OPEN_TIMEOUT_MS) { connection.state.first { it == ConnectionState.Connected } } ?: return@launch
            connection.focusBlock(sessionId, blockId)
        }
    }

    private fun syncService() {
        if (settings.value.backgroundAlerts && servers.value.isNotEmpty()) {
            WatchService.start(context)
        } else {
            WatchService.stop(context)
        }
    }

    fun select(selection: Selection?) {
        _selection.value = selection
        remember(selection)
    }

    private fun remember(selection: Selection?) {
        updateSettings { it.copy(lastServerId = selection?.serverId, lastSessionId = selection?.sessionId) }
    }

    fun requestOpen(selection: Selection) {
        _selection.value = selection
        remember(selection)
        _openRequests.tryEmit(selection)
    }

    /** Keeps the launcher's long-press menu pointing at recently used windows. */
    fun recordRecent(serverId: String, sessionId: String, title: String) {
        Shortcuts.publish(context, serverId, sessionId, title)
    }

    fun unlock() {
        _locked.value = false
    }

    fun addServer(label: String, url: String): String {
        val id = UUID.randomUUID().toString()
        scope.launch { store.updateServers { it + SavedServer(id, label, url) } }
        return id
    }

    fun removeServer(id: String) {
        if (_selection.value?.serverId == id) _selection.value = null
        scope.launch { store.updateServers { list -> list.filterNot { it.id == id } } }
    }

    fun renameServer(id: String, label: String) {
        scope.launch { store.updateServers { list -> list.map { if (it.id == id) it.copy(label = label) else it } } }
    }

    fun updateSettings(change: (Settings) -> Settings) {
        scope.launch { store.updateSettings(change) }
    }

    override fun onStart(owner: LifecycleOwner) {
        _foreground.value = true
        pauseJob?.cancel()
        val away = System.currentTimeMillis() - leftAt
        if (leftAt != 0L && away > LOCK_AFTER_MS) _locked.value = true
        _connections.value.values.forEach {
            it.retryNow()
            it.resumeStreams()
        }
        // Services can only start reliably while the app is visible, so do it here.
        syncService()
    }

    override fun onStop(owner: LifecycleOwner) {
        _foreground.value = false
        leftAt = System.currentTimeMillis()
        // Give terminal sizes back straight away so other devices reflow, but keep the sockets for
        // a moment, because switching apps briefly is common.
        pauseJob = scope.launch {
            _connections.value.values.forEach { runCatching { it.releaseAllOwnership() } }
            delay(PAUSE_AFTER_MS)
            _connections.value.values.forEach { if (watching) it.pauseStreams() else it.pause() }
        }
    }

    companion object {
        private const val PAUSE_AFTER_MS = 20_000L
        private const val LOCK_AFTER_MS = 60_000L
        private const val OPEN_TIMEOUT_MS = 20_000L
        const val BuildConfigVersion = dev.compy.BuildConfig.VERSION_NAME
    }
}
