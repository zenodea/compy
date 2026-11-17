package dev.compy.system

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.compy.rex.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns files picked or shared on the phone into uploads. A file whose size its app doesn't report
 * is copied into the cache first, because Rex needs each file's exact size before its bytes.
 */
suspend fun uploadFiles(context: Context, uris: List<Uri>): List<UploadFile> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val dir = File(context.cacheDir, "uploads")
    // Copies an upload left behind, such as when the app was closed partway through.
    val stale = System.currentTimeMillis() - 60 * 60 * 1000L
    dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
    uris.mapIndexed { i, uri ->
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        val plainName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file-${i + 1}"
        // A picture copied to the clipboard often has no name of its own. Without an ending, the
        // program that is handed its path couldn't tell that it is a picture.
        val fileName = withExtension(plainName, runCatching { resolver.getType(uri) }.getOrNull())
        val known = size
        if (known != null && known >= 0) {
            UploadFile(fileName, known) { resolver.openInputStream(uri) ?: error("Couldn't open $fileName") }
        } else {
            dir.mkdirs()
            val copy = File.createTempFile("upload", null, dir)
            try {
                resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: error("Couldn't open $fileName")
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
            UploadFile(fileName, copy.length(), discard = { copy.delete() }) { copy.inputStream() }
        }
    }
}

/** A file's name with the ending its kind goes by, when the name has none: `image/png` makes `clip` into `clip.png`. */
internal fun withExtension(name: String, mimeType: String?): String {
    if ('.' in name.trimStart('.')) return name
    val extension = when (mimeType) {
        null -> null
        "image/jpeg" -> "jpg"
        "text/plain" -> "txt"
        else -> mimeType.substringAfter('/', "").substringBefore('+').takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } && it != "*" }
    }
    return if (extension == null) name else "$name.$extension"
}

/** "photo.jpg", or "3 files". */
fun uploadLabel(files: List<UploadFile>): String = if (files.size == 1) files[0].name else "${files.size} files"
