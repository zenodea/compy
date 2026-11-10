package dev.compy.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.compy.rex.ChatImage
import dev.compy.rex.TranscriptFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

/** Decoded thumbnails, shared by every chat, sized by their pixels. */
private val thumbnails = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private const val THUMB_SIDE = 960
private const val FULL_SIDE = 4096

/** Decodes an image no larger than [maxSide] on its longer side, to keep big screenshots cheap. */
private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    // Until it fits. Stopping a step early let through twice the side, and a 5000-pixel
    // screenshot opened full screen as a 100 MB bitmap.
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private sealed interface Loaded {
    data object Loading : Loaded
    /** The conversation no longer has the image. */
    data object Missing : Loaded
    /** It couldn't be fetched or read this time. */
    data object Failed : Loaded
    data class Ready(val bitmap: ImageBitmap) : Loaded
}

/** Loads an image. Raising [attempt] tries again after a failure. */
@Composable
private fun rememberImage(feed: TranscriptFeed, image: ChatImage, maxSide: Int, attempt: Int = 0): Loaded {
    val key = feed.imageKey(image)
    fun cached(): Loaded = if (maxSide == THUMB_SIDE) thumbnails.get(key)?.let { Loaded.Ready(it) } ?: Loaded.Loading else Loaded.Loading
    val loaded by produceState(initialValue = cached(), key, maxSide, attempt) {
        // The state outlives a change of image, so start from what this one has.
        value = cached()
        if (value is Loaded.Ready) return@produceState
        value = try {
            val bytes = withContext(Dispatchers.IO) { feed.image(image) }
            val bitmap = bytes?.let { withContext(Dispatchers.IO) { decode(it, maxSide) } }
            if (bitmap != null && maxSide == THUMB_SIDE) thumbnails.put(key, bitmap)
            when {
                bytes == null -> Loaded.Missing
                bitmap == null -> Loaded.Failed
                else -> Loaded.Ready(bitmap)
            }
        } catch (e: TimeoutCancellationException) {
            Loaded.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded.Failed
        }
    }
    return loaded
}

/** A square thumbnail that opens the image. [image] is null when the host's compy.lua can't send it. */
@Composable
internal fun ChatImageThumb(feed: TranscriptFeed, image: ChatImage?, size: Dp, onOpen: (ChatImage) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    var attempt by remember(image) { mutableIntStateOf(0) }
    val loaded = image?.let { rememberImage(feed, it, THUMB_SIDE, attempt) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(scheme.surfaceContainerHighest)
            // A thumbnail that failed to load is tapped to try again, not to open nothing.
            .then(if (image != null) Modifier.clickable { if (loaded == Loaded.Failed) attempt++ else onOpen(image) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Text(
                "Update compy.lua on the host to see images",
                Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Box
        }
        when (loaded) {
            null, Loaded.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Loaded.Missing -> Icon(Icons.Rounded.BrokenImage, "Image unavailable", tint = scheme.onSurfaceVariant)
            Loaded.Failed -> Icon(Icons.Rounded.Refresh, "Couldn't load this image. Tap to try again.", tint = scheme.onSurfaceVariant)
            is Loaded.Ready -> Image(loaded.bitmap, "Image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** The images of one message or tool result, in a wrapping row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatImageRow(feed: TranscriptFeed, images: List<ChatImage?>, onOpen: (ChatImage) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { ChatImageThumb(feed, it, 120.dp, onOpen) }
    }
}

/** One image, full screen, to pinch and pan. The close button or going back leaves. */
@Composable
internal fun ChatImageViewer(feed: TranscriptFeed, image: ChatImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var attempt by remember(image) { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val loaded = rememberImage(feed, image, FULL_SIDE, attempt)) {
                Loaded.Loading -> CircularProgressIndicator(color = Color.White)
                Loaded.Missing -> Text("This image is no longer in the conversation", color = Color.White)
                Loaded.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load this image", color = Color.White)
                    TextButton(onClick = { attempt++ }) { Text("Try again") }
                }
                is Loaded.Ready -> Image(
                    loaded.bitmap,
                    "Image",
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            }
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
        }
    }
}

/** Every image in the conversation, newest first, read from the host's whole transcript. */
@Composable
internal fun ChatImageGallery(feed: TranscriptFeed, onOpen: (ChatImage) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var images by remember { mutableStateOf<List<ChatImage>?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feed) {
        try {
            val found = feed.allImages()
            if (found == null) unsupported = true else images = found
        } catch (e: Exception) {
            failed = e.message ?: "Couldn't list the images"
        }
    }
    Column(modifier.background(scheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 52.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to the chat") }
            Text(
                images?.let { if (it.isEmpty()) "Images" else "Images (${it.size})" } ?: "Images",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
        }
        val list = images
        when {
            failed != null -> GalleryNote(failed.orEmpty())
            unsupported -> GalleryNote("To see images, copy the new rex/compy.lua to the host again and reload its Rex config.")
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            list.isEmpty() -> GalleryNote("No images in this conversation yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { "${it.offset}:${it.index}" }) { image ->
                    ChatImageThumb(feed, image, 112.dp, onOpen, Modifier.aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
private fun GalleryNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The button that opens the gallery, beside the chat and terminal toggle. */
@Composable
internal fun GalleryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = CircleShape, color = scheme.secondaryContainer.copy(alpha = 0.92f), modifier = modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PhotoLibrary, "Images in this conversation", Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
        }
    }
}
