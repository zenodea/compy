package dev.compy.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A pane kept on the phone to read without a connection. */
@Serializable
data class SavedPane(
    val id: String,
    val title: String,
    /** Where it was: the server, the session and the folder. */
    val where: String,
    /** [CHAT] for an agent's conversation, [TERMINAL] for a pane's screen and history. */
    val kind: String,
    val savedAt: Long,
    /** The size of its file on the phone. */
    val bytes: Long,
) {
    companion object {
        const val CHAT = "chat"
        const val TERMINAL = "terminal"
    }
}

/**
 * Saved panes, each one compressed file in [dir] beside an index of them all. It never grows
 * without end: only the newest [maxCount] are kept, and only as many of those as fit in
 * [maxBytes], so saving a pane drops the oldest when there is no room. One pane larger than
 * [maxOne] isn't saved at all. Files the index doesn't name, left by a save that was cut short,
 * are removed when the list is read.
 *
 * It reads and writes files, so call it off the main thread.
 */
class SavedPaneStore(
    private val dir: File,
    private val maxCount: Int = MAX_COUNT,
    private val maxBytes: Long = MAX_BYTES,
    private val maxOne: Long = MAX_ONE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(dir, "index.json")
    private val _list = MutableStateFlow<List<SavedPane>>(emptyList())

    /** Newest first. */
    val list: StateFlow<List<SavedPane>> = _list.asStateFlow()

    /** How much the saved panes take on the phone. */
    val totalBytes: Long get() = _list.value.sumOf { it.bytes }

    private fun file(id: String) = File(dir, "$id.gz")

    /** Reads the list from the phone and tidies up after anything that went wrong before. */
    @Synchronized
    fun load() {
        val listed = runCatching { json.decodeFromString(ListSerializer(SavedPane.serializer()), index.readText()) }.getOrDefault(emptyList())
        // A pane whose file is gone can't be opened, and a file nothing lists can't be reached.
        val present = listed.filter { file(it.id).isFile }
        val names = present.map { "${it.id}.gz" }.toSet() + index.name
        dir.listFiles()?.forEach { if (it.name !in names) it.delete() }
        _list.value = kept(present.sortedByDescending { it.savedAt })
        if (_list.value != listed) writeIndex()
    }

    /**
     * Saves [content] under a new entry and makes room for it by dropping the oldest. Null when
     * it is too large to keep or the phone couldn't write it.
     */
    @Synchronized
    fun save(title: String, where: String, kind: String, content: ByteArray): SavedPane? {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(content) } }.toByteArray()
        if (packed.size > maxOne || packed.size > maxBytes) return null
        val pane = SavedPane(UUID.randomUUID().toString(), title, where, kind, now(), packed.size.toLong())
        val written = runCatching {
            dir.mkdirs()
            file(pane.id).writeBytes(packed)
        }.isSuccess
        if (!written) {
            file(pane.id).delete()
            return null
        }
        _list.value = kept(listOf(pane) + _list.value)
        writeIndex()
        return pane
    }

    /** What was saved, or null when it is gone or can't be read. */
    fun read(id: String): ByteArray? = runCatching {
        GZIPInputStream(file(id).inputStream()).use { it.readBytes() }
    }.getOrNull()

    @Synchronized
    fun delete(id: String) {
        file(id).delete()
        _list.value = _list.value.filter { it.id != id }
        writeIndex()
    }

    @Synchronized
    fun deleteAll() {
        _list.value.forEach { file(it.id).delete() }
        _list.value = emptyList()
        writeIndex()
    }

    /** The newest panes that fit the limits. The files of the rest are deleted. */
    private fun kept(newestFirst: List<SavedPane>): List<SavedPane> {
        val keep = mutableListOf<SavedPane>()
        var total = 0L
        for (pane in newestFirst) {
            if (keep.size < maxCount && total + pane.bytes <= maxBytes) {
                keep += pane
                total += pane.bytes
            } else {
                file(pane.id).delete()
            }
        }
        return keep
    }

    private fun writeIndex() {
        runCatching {
            dir.mkdirs()
            // Written beside and moved into place, so a write that is cut short leaves the old list.
            val draft = File(dir, "index.json.new")
            draft.writeText(json.encodeToString(ListSerializer(SavedPane.serializer()), _list.value))
            if (!draft.renameTo(index)) {
                index.writeText(draft.readText())
                draft.delete()
            }
        }
    }

    companion object {
        const val MAX_COUNT = 40
        const val MAX_BYTES = 64_000_000L
        const val MAX_ONE = 16_000_000L
    }
}
