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
        val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file-${i + 1}"
        val known = size
        if (known != null && known >= 0) {
            UploadFile(fileName, known) { resolver.openInputStream(uri) ?: error("Couldn't open $fileName") }
        } else {
            val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
            // Copies from earlier uploads are long finished.
            val stale = System.currentTimeMillis() - 60 * 60 * 1000L
            dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
            val copy = File.createTempFile("upload", null, dir)
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: error("Couldn't open $fileName")
            UploadFile(fileName, copy.length()) { copy.inputStream() }
        }
    }
}

/** "photo.jpg", or "3 files". */
fun uploadLabel(files: List<UploadFile>): String = if (files.size == 1) files[0].name else "${files.size} files"
