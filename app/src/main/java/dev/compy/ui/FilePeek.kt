package dev.compy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.rex.shellQuoted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a file turned out to hold, as far as it can be shown on a phone. */
internal sealed interface Peeked {
    /** The file's lines. [truncated] when it has more than are kept. */
    class Lines(val lines: List<String>, val truncated: Boolean) : Peeked
    /** A picture, or the first page of a PDF, with how many pages it has. */
    class Picture(val bitmap: Bitmap, val pages: Int = 0) : Peeked
    /** Something that is neither text nor a picture. */
    class Other(val bytes: Int) : Peeked
}

/** Reads a file's bytes as text when they are text: no zero byte near the start, as programs and archives have. */
internal fun textLines(bytes: ByteArray): Peeked.Lines? {
    if (bytes.take(8192).any { it == 0.toByte() }) return null
    val lines = String(bytes, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
    // A file's last line break ends its last line; it doesn't start an empty one.
    val whole = if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    return Peeked.Lines(whole.take(MAX_PEEK_LINES), whole.size > MAX_PEEK_LINES)
}

private fun pdfPage(context: Context, bytes: ByteArray): Peeked.Picture? = runCatching {
    val folder = File(context.cacheDir, "peek").apply { mkdirs() }
    // One file, written over each time, so previews don't pile up.
    val file = File(folder, "preview.pdf").apply { writeBytes(bytes) }
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                pdf.openPage(0).use { page ->
                    val scale = MAX_PICTURE_SIDE.toFloat() / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    // A page has no background of its own, and its text is usually black.
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Peeked.Picture(bitmap, pdf.pageCount)
                }
            }
        }
    } finally {
        file.delete()
    }
}.getOrNull()

private fun picture(bytes: ByteArray): Peeked.Picture? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return Peeked.Picture(bitmap)
}

private fun peeked(context: Context, bytes: ByteArray): Peeked {
    val pdf = bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
    return (if (pdf) pdfPage(context, bytes) else null) ?: picture(bytes) ?: textLines(bytes) ?: Peeked.Other(bytes.size)
}

/**
 * A file on a pane's machine, read through Rex and shown over the session: its lines, with the
 * one that was named marked and in view, or the picture, or a PDF's first page. From here its
 * path can be copied, or the file opened in an editor in a new pane beside this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePeek(controller: WorkspaceController, peek: WorkspaceController.Peek, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scheme = MaterialTheme.colorScheme
    val settings by controller.app.settings.collectAsStateWithLifecycle()
    val host by controller.connection.label.collectAsStateWithLifecycle()
    var path by remember(peek) { mutableStateOf(peek.path) }
    var content by remember(peek) { mutableStateOf<Peeked?>(null) }
    var problem by remember(peek) { mutableStateOf<String?>(null) }
    LaunchedEffect(peek) {
        try {
            val file = controller.connection.download(controller.sessionId, peek.blockId, peek.path)
            path = file.path
            content = withContext(Dispatchers.Default) { peeked(context, file.bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message ?: "Couldn't read this file"
        }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                path + if (peek.line > 0) ":${peek.line}" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val shown = content
                val failed = problem
                when {
                    failed != null -> Text(failed, Modifier.padding(24.dp), color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    shown == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "Reading from ${host.ifEmpty { "the host" }}…",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    shown is Peeked.Lines -> FileLines(shown, peek.line)
                    shown is Peeked.Picture -> Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(shown.bitmap.asImageBitmap(), "The file's picture", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        if (shown.pages > 1) {
                            Text(
                                "Page 1 of ${shown.pages}",
                                Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    shown is Peeked.Other -> Text(
                        "This file isn't text or a picture, so there is nothing to show of it here. It holds ${shown.bytes} bytes.",
                        Modifier.padding(24.dp),
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    controller.copy(path)
                    controller.showMessage("Path copied")
                }) { Text("Copy path") }
                TextButton(
                    enabled = problem == null,
                    onClick = {
                        onClose()
                        val editor = settings.editorCommand.ifBlank { "nvim" }
                        val at = if (peek.line > 0) " +${peek.line}" else ""
                        // To the right of the pane that named it, in the same folder, so a relative path still fits.
                        controller.connection.newSplitRunning(controller.sessionId, peek.blockId, horizontal = true, text = "$editor$at ${shellQuoted(path)}\r") {
                            controller.showMessage(it)
                        }
                    },
                ) { Text("Open in editor") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** A file's lines with their numbers, opened at the line that was named, which is marked. */
@Composable
private fun FileLines(file: Peeked.Lines, line: Int) {
    val scheme = MaterialTheme.colorScheme
    // A few lines above the one named, so it reads in its place.
    val list = rememberLazyListState((line - 4).coerceIn(0, maxOf(0, file.lines.size - 1)))
    val numberWidth = (file.lines.size.toString().length * 9 + 10).dp
    SelectionContainer {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(), state = list) {
            itemsIndexed(file.lines) { index, text ->
                val named = index + 1 == line
                Row(Modifier.fillMaxWidth().background(if (named) scheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)) {
                    Text(
                        (index + 1).toString(),
                        Modifier.width(numberWidth).padding(end = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                    )
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (file.truncated) {
                item {
                    Text(
                        "Only the first $MAX_PEEK_LINES lines are shown.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The most lines of a file that are shown. */
internal const val MAX_PEEK_LINES = 20_000
private const val MAX_PICTURE_SIDE = 2048
