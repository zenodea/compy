package dev.compy.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.compy.AppState
import dev.compy.BuildConfig
import dev.compy.rex.RexControl
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Turns what someone typed or scanned into a server address, or explains what's wrong with it. */
internal fun normalizeServerUrl(input: String, allowCleartext: Boolean): Result<String> {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Enter the server's address"))
    val typed = if ("://" in trimmed) trimmed else "https://$trimmed"
    val scheme = typed.substringBefore("://").lowercase()
    val rest = typed.substringAfter("://")
    // Only a scheme, such as "https://", which trimming the slashes turned into a host name.
    if (rest.isBlank() || trimmed.endsWith(":")) return Result.failure(IllegalArgumentException("Enter the server's address"))
    val withScheme = "$scheme://$rest"
    return when {
        scheme == "https" -> Result.success(withScheme)
        scheme == "http" && allowCleartext -> Result.success(withScheme)
        scheme == "http" -> Result.failure(IllegalArgumentException("Compy needs an https address, such as the one Rex's Tailscale pairing shows"))
        else -> Result.failure(IllegalArgumentException("Compy can't connect to a $scheme address"))
    }
}

internal fun defaultLabel(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore(':').let { host ->
        // An IP address is more recognizable whole; a host name reads better as its first part.
        if (host.firstOrNull()?.isDigit() == true) host else host.substringBefore('.')
    }.ifEmpty { "Rex" }

internal fun isVpnOn(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

@Composable
fun AddServerScreen(app: AppState, canGoBack: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saved: restored as true with no test running, it would leave the button off for good.
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (canGoBack) BackHandler(onBack = onDone)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else error = "Compy needs the camera to scan a pairing code. You can type the address instead."
    }

    fun connect(input: String) {
        val url = normalizeServerUrl(input, BuildConfig.ALLOW_CLEARTEXT).getOrElse {
            error = it.message
            return
        }
        // Two copies of one server list the same panes twice, which the activity list can't show.
        if (app.servers.value.any { it.url.equals(url, ignoreCase = true) }) {
            error = "That server is already in Compy"
            return
        }
        error = null
        testing = true
        scope.launch {
            val result = testServer(url)
            testing = false
            result.onSuccess {
                app.addServer(label.ifBlank { defaultLabel(url) }, url)
                onDone()
            }.onFailure {
                error = explain(context, url, it)
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (canGoBack) RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", filled = false, onClick = onDone)
        Text("Connect to Rex", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            "Join the same tailnet with the Tailscale app, then run this on the computer running Rex:",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            "rex server tailscale on\nrex server tailscale pair",
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainer)
                .padding(14.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        if (scanning) {
            QrScanner(
                Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)),
                onFailed = {
                    scanning = false
                    error = "Compy couldn't start the camera. You can type the address instead."
                },
                onCode = { code ->
                    scanning = false
                    address = code
                    connect(code)
                },
            )
            TextButton(onClick = { scanning = false }) { Text("Cancel scan") }
        } else {
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.height(18.dp))
                Text("Scan pairing code", Modifier.padding(start = 8.dp))
            }
        }
        Text("Or enter the address", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        OutlinedTextField(
            address, { address = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Address") },
            placeholder = { Text("rex.your-tailnet.ts.net") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            label, { label = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name (optional)") },
            singleLine = true,
        )
        error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodyMedium) }
        FilledTonalButton(onClick = { connect(address) }, enabled = !testing && address.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (testing) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).height(16.dp), strokeWidth = 2.dp)
                Text("Connecting…")
            } else {
                Text("Connect")
            }
        }
    }
}

private suspend fun testServer(url: String): Result<Unit> = runCatching {
    val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    val control = RexControl(http, url, "pairing", AppState.BuildConfigVersion, onEvent = { _, _ -> }, onClosed = { _, _ -> })
    try {
        control.open(null)
    } finally {
        control.close()
        http.dispatcher.executorService.shutdown()
    }
    Unit
}

private fun explain(context: Context, url: String, error: Throwable): String {
    val base = error.message ?: "Couldn't connect"
    return if (".ts.net" in url && !isVpnOn(context)) {
        "Couldn't reach this server, and Tailscale doesn't look connected on this phone. Open the Tailscale app, connect, and try again."
    } else {
        "Couldn't connect: $base"
    }
}

@Composable
private fun QrScanner(modifier: Modifier, onFailed: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val latest = rememberUpdated(onCode)
    val latestFailed = rememberUpdated(onFailed)
    val cameras = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) {
        onDispose {
            // The camera is bound to the activity, so it kept running after the scanner closed.
            // Listeners run in the order added, so this also undoes a bind that is yet to happen.
            cameras.addListener({
                runCatching { cameras.get().unbindAll() }
                executor.shutdown()
            }, ContextCompat.getMainExecutor(context))
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val preview = PreviewView(ctx)
            cameras.addListener({
                val provider = runCatching { cameras.get() }.getOrNull()
                if (provider == null) {
                    latestFailed.value()
                    return@addListener
                }
                val usecase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val reader = MultiFormatReader().apply {
                    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                }
                var finished = false
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { a ->
                        a.setAnalyzer(executor) { image ->
                            val text = decode(reader, image)
                            image.close()
                            if (text != null && !finished) {
                                finished = true
                                ContextCompat.getMainExecutor(context).execute {
                                    provider.unbindAll()
                                    latest.value(text)
                                }
                            }
                        }
                    }
                // The camera is optional in the manifest, so there may be none, or only a front one.
                runCatching {
                    val camera = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, camera, usecase, analysis)
                }.onFailure { latestFailed.value() }
            }, ContextCompat.getMainExecutor(ctx))
            preview
        },
    )
}

private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull().also { reader.reset() }
}
