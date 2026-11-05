package dev.compy.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val scheme = withScheme.substringBefore("://").lowercase()
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

private fun isVpnOn(context: Context): Boolean {
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
    var testing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (canGoBack) BackHandler(onBack = onDone)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else error = "Compy needs the camera to scan a pairing code. You can type the address instead."
    }

    fun connect(input: String) {
        val url = normalizeServerUrl(input, BuildConfig.DEBUG).getOrElse {
            error = it.message
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

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack) IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Add a Rex server", style = MaterialTheme.typography.headlineSmall)
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On the computer running Rex:", style = MaterialTheme.typography.titleSmall)
                    Text("rex server tailscale on\nrex server tailscale pair", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("Then scan the code it shows. Your phone must be on the same tailnet, using the Tailscale app.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (scanning) {
                QrScanner(
                    Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)),
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null)
                    Text("Scan pairing code", Modifier.padding(start = 8.dp))
                }
            }
            Text("Or type the address", style = MaterialTheme.typography.titleSmall)
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            FilledTonalButton(onClick = { connect(address) }, enabled = !testing && address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (testing) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).height(16.dp), strokeWidth = 2.dp)
                    Text("Connecting…")
                } else {
                    Text("Connect")
                }
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
private fun QrScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val latest = rememberUpdated(onCode)
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val preview = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
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
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, usecase, analysis)
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
