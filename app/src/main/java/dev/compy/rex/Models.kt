package dev.compy.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A rectangle in fractions of a window's width and height. */
data class FractionRect(val x: Float, val y: Float, val w: Float, val h: Float)

data class SessionSummary(val id: String, val label: String)

data class BlockInfo(
    val id: String,
    val label: String,
    val creator: String,
    val flavor: String,
    val owner: String?,
)

sealed interface LayoutNode {
    val rect: FractionRect

    data class Leaf(override val rect: FractionRect, val blockId: String) : LayoutNode

    data class Split(
        override val rect: FractionRect,
        val splitId: String,
        val horizontal: Boolean,
        val ratio: Float,
        val before: LayoutNode,
        val after: LayoutNode,
    ) : LayoutNode
}

data class LayerView(
    val id: String,
    val floating: Boolean,
    val minimized: Boolean,
    val bounds: FractionRect,
    val layout: LayoutNode?,
    val blocks: List<BlockInfo>,
)

data class WindowView(
    val id: String,
    val label: String,
    val focusedBlockId: String?,
    val zoomedBlockId: String?,
    val layers: List<LayerView>,
) {
    val blocks: List<BlockInfo> get() = layers.flatMap { it.blocks }

    /** The window's label, unless Rex generated it, when the program's own title says more. */
    fun displayTitle(titles: Map<String, String>): String {
        val generated = label.isEmpty() || GENERATED_LABEL.matches(label)
        if (!generated) return label
        val block = focusedBlockId ?: blocks.firstOrNull()?.id
        return block?.let { titles[it] } ?: blocks.firstOrNull()?.label?.ifEmpty { null } ?: "Window"
    }

    private companion object {
        val GENERATED_LABEL = Regex("Window [0-9a-f]{8}")
    }
}

data class SessionView(
    val revision: Long,
    val sessionId: String,
    val label: String,
    val activeWindowId: String?,
    val windows: List<WindowView>,
) {
    val activeWindow: WindowView? get() = windows.firstOrNull { it.id == activeWindowId } ?: windows.firstOrNull()
    fun windowOf(blockId: String) = windows.firstOrNull { w -> w.blocks.any { it.id == blockId } }
}

/** What a program in a terminal last reported with OSC 7501. */
data class ProgramStatus(
    val state: String,
    val kind: String?,
    val app: String?,
    val message: String?,
) {
    val isBlocked get() = state == "blocked"
    val isWorking get() = state == "working"
    val isDone get() = state == "done"
    val isError get() = state == "error"
}

data class HostEntry(val id: String, val label: String, val endpoint: String)

class RexError(val code: String, message: String, val status: Int) : Exception(message)

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
internal fun JsonElement?.float(): Float? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.toFloat()
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

private fun rectOf(e: JsonElement?): FractionRect {
    val a = e.arr()
    if (a == null || a.size < 4) return FractionRect(0f, 0f, 1f, 1f)
    return FractionRect(a[0].float() ?: 0f, a[1].float() ?: 0f, a[2].float() ?: 1f, a[3].float() ?: 1f)
}

internal fun parseLayout(e: JsonElement?): LayoutNode? {
    val o = e.obj() ?: return null
    val rect = rectOf(o["rect"])
    val split = o["split"].obj()
    if (split != null) {
        val before = parseLayout(split["before"]) ?: return parseLayout(split["after"])
        val after = parseLayout(split["after"]) ?: return before
        return LayoutNode.Split(
            rect = rect,
            splitId = split["split_id"].str().orEmpty(),
            // The docs say to draw an unknown direction as horizontal.
            horizontal = split["direction"].str() != "vertical",
            ratio = split["ratio"].float() ?: 0.5f,
            before = before,
            after = after,
        )
    }
    val block = o["block_id"].str() ?: return null
    return LayoutNode.Leaf(rect, block)
}

internal fun parseView(e: JsonElement?): SessionView? {
    val o = e.obj() ?: return null
    return SessionView(
        revision = o["revision"].long() ?: 0,
        sessionId = o["session_id"].str() ?: return null,
        label = o["label"].str().orEmpty(),
        activeWindowId = o["active_window_id"].str(),
        windows = o["windows"].arr().orEmpty().mapNotNull { w ->
            val win = w.obj() ?: return@mapNotNull null
            WindowView(
                id = win["window_id"].str() ?: return@mapNotNull null,
                label = win["label"].str().orEmpty(),
                focusedBlockId = win["focused_block_id"].str(),
                zoomedBlockId = win["zoomed_block_id"].str(),
                layers = win["layers"].arr().orEmpty().mapNotNull { parseLayer(it) },
            )
        },
    )
}

private fun parseLayer(e: JsonElement?): LayerView? {
    val l = e.obj() ?: return null
    return LayerView(
        id = l["layer_id"].str().orEmpty(),
        // The docs say to draw a kind we don't know as floating.
        floating = l["kind"].str() != "tiled",
        minimized = l["state"].str() == "minimized",
        bounds = rectOf(l["bounds"]),
        layout = parseLayout(l["layout"]),
        blocks = l["blocks"].arr().orEmpty().mapNotNull { b ->
            val block = b.obj() ?: return@mapNotNull null
            BlockInfo(
                id = block["block_id"].str() ?: return@mapNotNull null,
                label = block["label"].str().orEmpty(),
                creator = block["creator_name"].str().orEmpty(),
                flavor = block["flavor"].str().orEmpty(),
                owner = block["owner"].str(),
            )
        },
    )
}

internal fun parseSessions(e: JsonElement?): List<SessionSummary> =
    e.arr().orEmpty().mapNotNull { s ->
        val o = s.obj() ?: return@mapNotNull null
        val id = o["session_id"].str() ?: return@mapNotNull null
        SessionSummary(id, o["label"].str().orEmpty())
    }

internal fun parseStatusRecord(e: JsonElement?): ProgramStatus? {
    val o = e.obj() ?: return null
    return ProgramStatus(
        state = o["state"].str() ?: return null,
        kind = o["kind"].str(),
        app = o["effective_app"].str() ?: o["app"].str(),
        message = o["msg"].str(),
    )
}

/** The status to show for a block: the most urgent record it holds. */
internal fun mostUrgent(records: List<ProgramStatus>): ProgramStatus? =
    records.maxByOrNull {
        when (it.state) {
            "blocked" -> 4
            "error" -> 3
            "working" -> 2
            "done" -> 1
            else -> 0
        }
    }
