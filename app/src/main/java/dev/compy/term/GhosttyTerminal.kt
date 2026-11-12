package dev.compy.term

/**
 * Thin wrapper around a libghostty-vt terminal. Not thread-safe by itself: every call must hold
 * [lock]. Frames are copied out so the UI thread never touches terminal state while drawing.
 */
class GhosttyTerminal private constructor(private var handle: Long) {
    val lock = Any()

    val meta = IntArray(META_COUNT)
    var cells = IntArray(0)
        private set
    val extras = IntArray(8192)

    fun write(data: ByteArray, off: Int = 0, len: Int = data.size - off) = synchronized(lock) {
        if (handle != 0L && len > 0) nativeWrite(handle, data, off, len)
    }

    fun resize(cols: Int, rows: Int, cellW: Int, cellH: Int) = synchronized(lock) {
        if (handle != 0L) nativeResize(handle, cols, rows, cellW, cellH)
    }

    /**
     * The whole terminal, history and all, as bytes [fromSnapshot] reads back. Null when it can't
     * be taken just now, which is the case in the middle of an escape sequence.
     */
    fun snapshot(): ByteArray? = synchronized(lock) { if (handle != 0L) nativeSnapshot(handle) else null }

    fun scroll(delta: Int) = synchronized(lock) { if (handle != 0L) nativeScroll(handle, delta) }
    fun scrollToBottom() = synchronized(lock) { if (handle != 0L) nativeScrollToBottom(handle) }

    /** Colors are 0xRRGGBB with a non-zero alpha; 0 clears back to the built-in value. */
    fun setColors(fg: Int, bg: Int, cursor: Int, palette: IntArray?) = synchronized(lock) {
        if (handle != 0L) nativeSetColors(handle, fg, bg, cursor, palette)
    }

    /** Counts the frames copied out, so each view of this terminal can tell when it has an old one. */
    var frame = 0L
        private set

    /**
     * Copies the current screen into [meta], [cells] and [extras]. False when unchanged. [above] is
     * how many lines above the screen to copy too; they follow the screen's rows, nearest first.
     */
    fun render(force: Boolean, above: Int = 1): Boolean = synchronized(lock) {
        if (handle == 0L) return false
        var updated = nativeRender(handle, meta, cells, extras, force, above)
        val needed = cellsNeeded(meta[META_COLS], meta[META_ROWS], above)
        if (updated && needed > cells.size) {
            cells = IntArray(needed)
            updated = nativeRender(handle, meta, cells, extras, true, above)
        }
        if (updated) {
            frame++
            imageCount = nativeImages(handle, images)
        }
        updated
    }

    /** The pictures on screen as of the last frame, [IMAGE_INTS] for each of [imageCount]. */
    val images = IntArray(IMAGE_INTS * MAX_IMAGES)
    var imageCount = 0
        private set

    /** A picture a program drew, or null when it is gone or too large. Made new on every call. */
    fun imageBitmap(id: Int): android.graphics.Bitmap? = synchronized(lock) {
        if (handle == 0L) return null
        val size = IntArray(2)
        val pixels = nativeImagePixels(handle, id, size) ?: return null
        runCatching {
            android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
            }
        }.getOrNull()
    }

    fun selectRange(x1: Int, y1: Int, x2: Int, y2: Int) = synchronized(lock) {
        handle != 0L && nativeSelectRange(handle, x1, y1, x2, y2)
    }

    fun selectWord(x: Int, y: Int) = synchronized(lock) {
        handle != 0L && nativeSelectWord(handle, x, y)
    }

    /** Moves the selection's end to a cell, keeping its start even when that has scrolled out of view. */
    fun extendSelection(x: Int, y: Int) = synchronized(lock) { handle != 0L && nativeExtendSelection(handle, x, y) }

    /** Makes the selection's first or last end the one [extendSelection] moves. */
    fun grabSelection(first: Boolean) = synchronized(lock) { handle != 0L && nativeGrabSelection(handle, first) }

    /**
     * Writes the selection's ends into [out] as start x, start y, end x, end y. Rows count from the
     * top of the screen, so an end that has scrolled away is negative or past the last row.
     */
    fun selectionBounds(out: IntArray) = synchronized(lock) { handle != 0L && nativeSelectionBounds(handle, out) }

    fun clearSelection() = synchronized(lock) { if (handle != 0L) nativeClearSelection(handle) }
    fun selectionText(): String? = synchronized(lock) { if (handle != 0L) nativeSelectionText(handle) else null }?.toString(Charsets.UTF_8)

    fun hyperlinkAt(x: Int, y: Int): String? = synchronized(lock) { if (handle != 0L) nativeHyperlinkAt(handle, x, y) else null }?.toString(Charsets.UTF_8)
    fun scrollPrompt(delta: Int) = synchronized(lock) { if (handle != 0L) nativeScrollPrompt(handle, delta) }
    fun selectOutputAt(x: Int, y: Int) = synchronized(lock) { handle != 0L && nativeSelectOutputAt(handle, x, y) }

    /**
     * Selects the most recent command output on screen, looking upwards from the bottom row.
     * False if the shell doesn't mark command output, which needs shell integration.
     */
    fun selectLastOutput(): Boolean = synchronized(lock) {
        if (handle == 0L) return false
        render(true)
        for (y in meta[META_ROWS] - 1 downTo 0) {
            if (nativeSelectOutputAt(handle, 0, y)) return true
        }
        false
    }

    /** Starts or changes a search, returning how many matches it found. */
    fun search(needle: String): Int = synchronized(lock) { if (handle != 0L) nativeSearch(handle, needle.toByteArray()) else 0 }
    fun searchStep(older: Boolean): Int = synchronized(lock) { if (handle != 0L) nativeSearchStep(handle, older) else -1 }
    fun searchClear() = synchronized(lock) { if (handle != 0L) nativeSearchClear(handle) }
    fun searchHighlights(out: IntArray): Int = synchronized(lock) { if (handle != 0L) nativeSearchHighlights(handle, out) else 0 }

    fun close() = synchronized(lock) {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    companion object {
        const val META_COLS = 0
        const val META_ROWS = 1
        const val META_BG = 2
        const val META_FG = 3
        const val META_CURSOR_COLOR = 4
        const val META_CURSOR_VISIBLE = 5
        const val META_CURSOR_X = 6
        const val META_CURSOR_Y = 7
        const val META_CURSOR_STYLE = 8
        const val META_SCROLL_TOTAL = 9
        const val META_SCROLL_OFFSET = 10
        const val META_SCROLL_LEN = 11
        const val META_MOUSE = 12
        const val META_ALT = 13
        const val META_BRACKETED = 14
        const val META_CURSOR_BLINK = 15
        const val META_EXTRAS_LEN = 16
        /** How many lines above the screen follow the screen's own rows. */
        const val META_ABOVE = 17
        /** A cell's size in pixels where the terminal's size was set, which pictures are measured in. */
        const val META_CELL_W = 18
        const val META_CELL_H = 19
        const val META_COUNT = 20

        /**
         * Ints for each picture: id, generation, above text, column, row, offset x and y in that
         * cell, width, height, and the part of the picture shown as x, y, width, height.
         */
        const val IMAGE_INTS = 13
        const val MAX_IMAGES = 64

        /** How many ints a frame of this size takes: four per cell, with [above] rows past the screen's. */
        fun cellsNeeded(cols: Int, rows: Int, above: Int) = cols * (rows + above) * 4

        const val FLAG_BOLD = 1
        const val FLAG_ITALIC = 2
        const val FLAG_FAINT = 4
        const val FLAG_INVERSE = 8
        const val FLAG_INVISIBLE = 16
        const val FLAG_STRIKE = 32
        const val FLAG_UNDERLINE = 64
        const val FLAG_OVERLINE = 128
        const val FLAG_BLINK = 256
        const val FLAG_SELECTED = 512
        const val FLAG_WIDE = 1024
        const val FLAG_SPACER = 2048
        const val FLAG_LINK = 4096
        /** On a row's first cell, when its line carries on into the next row. */
        const val FLAG_WRAPS = 1 shl 16

        /** The underline's kind, above the flags: 1 single, 2 double, 3 curly, 4 dotted, 5 dashed. */
        const val UNDERLINE_SHIFT = 13
        const val UNDERLINE_MASK = 7 shl UNDERLINE_SHIFT

        init {
            System.loadLibrary("compyterm")
        }

        fun create(cols: Int, rows: Int): GhosttyTerminal = GhosttyTerminal(nativeNew(cols, rows))

        /** Null when the snapshot can't be decoded, such as after a format change upstream. */
        fun fromSnapshot(bytes: ByteArray, len: Int): GhosttyTerminal? {
            val h = nativeDecodeSnapshot(bytes, len)
            return if (h == 0L) null else GhosttyTerminal(h)
        }

        @JvmStatic private external fun nativeNew(cols: Int, rows: Int): Long
        @JvmStatic private external fun nativeDecodeSnapshot(data: ByteArray, len: Int): Long
        @JvmStatic private external fun nativeSnapshot(h: Long): ByteArray?
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeWrite(h: Long, data: ByteArray, off: Int, len: Int)
        @JvmStatic private external fun nativeResize(h: Long, cols: Int, rows: Int, cw: Int, ch: Int)
        @JvmStatic private external fun nativeScroll(h: Long, delta: Int)
        @JvmStatic private external fun nativeScrollToBottom(h: Long)
        @JvmStatic private external fun nativeSetColors(h: Long, fg: Int, bg: Int, cursor: Int, palette: IntArray?)
        @JvmStatic private external fun nativeRender(h: Long, meta: IntArray, cells: IntArray, extras: IntArray, force: Boolean, above: Int): Boolean
        @JvmStatic private external fun nativeImages(h: Long, out: IntArray): Int
        @JvmStatic private external fun nativeImagePixels(h: Long, id: Int, size: IntArray): ByteArray?
        @JvmStatic private external fun nativeSelectRange(h: Long, x1: Int, y1: Int, x2: Int, y2: Int): Boolean
        @JvmStatic private external fun nativeSelectWord(h: Long, x: Int, y: Int): Boolean
        @JvmStatic private external fun nativeExtendSelection(h: Long, x: Int, y: Int): Boolean
        @JvmStatic private external fun nativeGrabSelection(h: Long, first: Boolean): Boolean
        @JvmStatic private external fun nativeSelectionBounds(h: Long, out: IntArray): Boolean
        @JvmStatic private external fun nativeClearSelection(h: Long)
        // Text comes back as UTF-8 bytes, since JNI's own strings can't carry all of it safely.
        @JvmStatic private external fun nativeSelectionText(h: Long): ByteArray?
        @JvmStatic private external fun nativeHyperlinkAt(h: Long, x: Int, y: Int): ByteArray?
        @JvmStatic private external fun nativeScrollPrompt(h: Long, delta: Int)
        @JvmStatic private external fun nativeSelectOutputAt(h: Long, x: Int, y: Int): Boolean
        @JvmStatic private external fun nativeSearch(h: Long, needle: ByteArray): Int
        @JvmStatic private external fun nativeSearchStep(h: Long, older: Boolean): Int
        @JvmStatic private external fun nativeSearchClear(h: Long)
        @JvmStatic private external fun nativeSearchHighlights(h: Long, out: IntArray): Int
    }
}
