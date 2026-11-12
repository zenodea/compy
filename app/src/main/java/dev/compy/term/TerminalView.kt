package dev.compy.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.text.InputType
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.compy.rex.RexKey
import dev.compy.rex.TerminalSession
import dev.compy.term.GhosttyTerminal.Companion.FLAG_BOLD
import dev.compy.term.GhosttyTerminal.Companion.FLAG_FAINT
import dev.compy.term.GhosttyTerminal.Companion.FLAG_INVERSE
import dev.compy.term.GhosttyTerminal.Companion.FLAG_INVISIBLE
import dev.compy.term.GhosttyTerminal.Companion.FLAG_ITALIC
import dev.compy.term.GhosttyTerminal.Companion.FLAG_OVERLINE
import dev.compy.term.GhosttyTerminal.Companion.FLAG_SELECTED
import dev.compy.term.GhosttyTerminal.Companion.FLAG_SPACER
import dev.compy.term.GhosttyTerminal.Companion.FLAG_STRIKE
import dev.compy.term.GhosttyTerminal.Companion.FLAG_UNDERLINE
import dev.compy.term.GhosttyTerminal.Companion.FLAG_WIDE
import dev.compy.term.GhosttyTerminal.Companion.UNDERLINE_MASK
import dev.compy.term.GhosttyTerminal.Companion.UNDERLINE_SHIFT
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a terminal and turns touch, keyboard and input-method events into terminal input. It
 * holds no connection of its own; [Host] sends everything to the server.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val fonts: () -> TerminalFonts) : View(context), TerminalSession.Listener {

    interface Host {
        fun sendKey(key: RexKey)
        fun sendText(text: String)
        fun paste(text: String)
        fun sendMouse(action: String, button: String?, col: Int, row: Int)
        fun sendWheel(lines: Float, col: Int, row: Int)
        /** Modifiers latched on the key bar, consumed by the next key. */
        fun consumeStickyMods(): Int
        fun onTapped()
        /** The grid that fits this view at the current font size. */
        fun onGridMeasured(cols: Int, rows: Int, cellW: Int, cellH: Int)
        fun onFontSizeChanged(sizeSp: Float)
        fun copyText(text: String)
        fun clipboardText(): String?
        fun openUrl(url: String)
        /** Hands text to another app through Android's share sheet. */
        fun shareText(text: String)
        fun showMessage(text: String)
    }

    var host: Host? = null
    var interactive = true
        set(value) {
            val was = field
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
            // Sizes aren't reported while this is off, so one that changed meanwhile was missed.
            if (value && !was) reportGrid()
        }
    /** Whether this pane receives typing, which decides how its cursor looks. */
    var paneFocused = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var session: TerminalSession? = null
    private var boundTerminal: GhosttyTerminal? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale

    var fontSizeSp = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT, MAX_FONT)
            if (field != clamped) {
                field = clamped
                measureCell()
                reportGrid()
                invalidate()
            }
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val fillPaint = Paint()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    /** A cell's size, in whole pixels so that every cell edge, line and cursor is sharp. */
    private var cellW = 8f
    private var cellH = 16f
    private var baseline = 12f
    /** How much glyphs are stretched sideways so that one character is exactly one cell wide. */
    private var stretch = 1f
    /** Changes whenever the font or its size does, so rows drawn with the old one aren't reused. */
    private var styleGeneration = 0

    /** How the person set the terminal to look and behave. */
    var look = TerminalLook()
        set(value) {
            if (field == value) return
            val resized = field.lineHeight != value.lineHeight || field.letterSpacing != value.letterSpacing ||
                field.ligatures != value.ligatures
            field = value
            styleGeneration++
            if (resized) {
                measureCell()
                reportGrid()
            }
            invalidate()
        }

    /** What a pane shows before its terminal arrives: the theme's background, not a flash of black. */
    var emptyColor = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                if (!haveFrame) invalidate()
            }
        }

    /** Told when the view starts or stops showing history, for a button that goes back to the newest line. */
    var onScrolledUp: ((Boolean) -> Unit)? = null
    private var scrolledUp = false

    /** True when the program accepts a paste as one piece, so its line breaks don't run anything. */
    val bracketedPaste: Boolean get() = meta[GhosttyTerminal.META_BRACKETED] != 0

    private val meta = IntArray(GhosttyTerminal.META_COUNT)
    private var cells = IntArray(0)
    private val extras = IntArray(8192)
    private var haveFrame = false
    private var chars = CharArray(512)

    // Pictures a program drew, as of the frame held here, and their bitmaps while they are on screen.
    private val images = IntArray(GhosttyTerminal.IMAGE_INTS * GhosttyTerminal.MAX_IMAGES)
    private var imageCount = 0
    private val bitmaps = HashMap<Long, android.graphics.Bitmap>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageSource = android.graphics.Rect()

    /** Keeps text clear of the pane's rounded corners. */
    private val padding = Math.round(8 * density).toFloat()

    /** Extra room above the text, in pixels, so a pane's drag handle doesn't cover the first line. */
    var topSpace = 0f
        set(value) {
            if (field != value) {
                field = value
                reportGrid()
                invalidate()
            }
        }
    private val padTop get() = padding + Math.round(topSpace)

    // Where the last frame put the grid, which touches are measured against.
    private var drawScale = 1f
    private var originX = padding
    private var originY = padding

    /**
     * How far the text sits below its rows' usual place, in pixels of the grid: from 0 up to one
     * row. The engine scrolls by whole rows, and this is the rest, so scrolling follows the finger.
     */
    private var scrollFrac = 0f
    private var touching = false
    private val rowNodes = RowNodes()
    /** The engine's count of the frame held here, and how many lines above the screen it was asked for. */
    private var seenFrame = -1L
    private var wantAbove = 1
    private var gotAbove = 1

    // Selection, in viewport cells.
    private var selecting = false
    private var selStartX = 0
    private var selStartY = 0
    private var selEndX = 0
    private var selEndY = 0
    private var draggingHandle = 0
    private var actionMode: ActionMode? = null

    /**
     * When true this terminal keeps another device's size. Pinching then zooms the picture and
     * dragging pans it, because the font size wouldn't change what fits.
     */
    var keepSize = false
        set(value) {
            if (field != value) {
                field = value
                zoom = 1f
                panX = 0f
                panY = 0f
                invalidate()
            }
        }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private var links: List<TerminalLinks.Link> = emptyList()
    private var linksStale = false
    private var linksPending = false
    private var lastOffset = -1
    private var searchActive = false
    private val highlights = IntArray(128 * 5)

    private var sizeChangedAt = 0L
    /** The grid last asked for, which the terminal has until the server answers. */
    private var wantCols = 0
    private var wantRows = 0
    private var lastCols = 0
    private var lastRows = 0
    private var reflowedAt = 0L
    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private var scrollbarVisibleUntil = 0L
    private var blinkOn = true
    /** When the cursor last moved or a key was sent, from which it blinks, so it shows while typing. */
    private var blinkFrom = 0L
    private var lastCursorX = -1
    private var lastCursorY = -1

    init {
        measureCell()
        interactive = true
    }

    fun bind(newSession: TerminalSession?) {
        if (session === newSession) return
        session?.removeListener(this)
        session = newSession
        boundTerminal = null
        haveFrame = false
        scrollFrac = 0f
        links = emptyList()
        dropImages()
        // A terminal that arrives at another device's size is about to be fitted to this one.
        sizeChangedAt = SystemClock.uptimeMillis()
        newSession?.addListener(this)
        invalidate()
    }

    override fun onTerminalChanged() {
        postInvalidateOnAnimation()
    }

    private fun measureCell() {
        textPaint.typeface = fonts().regular
        textPaint.textSize = fontSizeSp * scaledDensity
        textPaint.textScaleX = 1f
        textPaint.letterSpacing = 0f
        textPaint.fontFeatureSettings = if (look.ligatures) null else "'liga' 0, 'calt' 0"
        // Monospace fonts have fractional advances. Measuring a long run and dividing gives the
        // real one, and stretching glyphs by under a pixel then makes each cell a whole number of
        // pixels wide, so text, lines and backgrounds all land on the same pixel grid.
        val natural = textPaint.measureText(RULER) / RULER.length
        val extra = look.letterSpacing * density
        cellW = max(1f, Math.round(natural + extra).toFloat())
        // Extra room between letters is split to either side of each one, which keeps a
        // character in the middle of its wider cell.
        if (extra != 0f && textPaint.textSize > 0f) textPaint.letterSpacing = extra / textPaint.textSize
        stretch = 1f
        // Twice, since the stretch may or may not apply to the room between letters as well.
        repeat(2) {
            val advance = textPaint.measureText(RULER) / RULER.length
            if (advance > 0f) stretch *= cellW / advance
            textPaint.textScaleX = stretch
        }
        val fm = textPaint.fontMetrics
        val height = max(1f, ceil(fm.descent - fm.ascent))
        cellH = max(1f, Math.round(height * look.lineHeight).toFloat())
        // Taller rows keep the text in their middle.
        baseline = Math.round(-fm.ascent) + floor((cellH - height) / 2)
        styleGeneration++
    }

    /** Call after the font changes. */
    fun fontsChanged() {
        measureCell()
        reportGrid()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeChangedAt = SystemClock.uptimeMillis()
        reportGrid()
    }

    private fun reportGrid() {
        if (width <= 0 || height <= 0 || !interactive) return
        val avail = width - padding * 2
        val availH = height - padding - padTop
        val cols = max(2, floor(avail / cellW).toInt())
        val rows = max(1, floor(availH / cellH).toInt())
        if (cols != wantCols || rows != wantRows) {
            wantCols = cols
            wantRows = rows
            sizeChangedAt = SystemClock.uptimeMillis()
        }
        host?.onGridMeasured(cols, rows, Math.round(cellW), Math.round(cellH))
    }

    /**
     * True while the terminal is still the size it had before this view changed, and the server
     * has been asked to fit it. The keyboard sliding in is the usual case. The text keeps its size
     * meanwhile instead of shrinking to fit and growing back a moment later.
     */
    private fun awaitingFit(cols: Int, rows: Int): Boolean {
        if (!interactive || keepSize) return false
        val since = SystemClock.uptimeMillis() - sizeChangedAt
        if (since < SIZE_SETTLE_MS) return true
        return wantCols > 0 && (cols != wantCols || rows != wantRows) && since < FIT_WAIT_MS
    }

    private fun scale(cols: Int, rows: Int): Float {
        if (cols <= 0 || rows <= 0 || width <= 0 || height <= 0) return 1f
        if (awaitingFit(cols, rows)) return 1f
        val w = cols * cellW + padding * 2
        val h = rows * cellH + padding + padTop
        val fit = min(width / w, height / h)
        return min(1f, fit) * zoom
    }

    /**
     * How far up the grid is moved while it is taller than the view, so the cursor's row stays in
     * sight above the keyboard; or how far down while it is shorter, when lines from history are
     * about to fill the room above it. Either way the text is already where the fitted terminal
     * will draw it, so nothing jumps when the server's answer arrives.
     */
    private fun anchorShift(cols: Int, rows: Int, s: Float): Float {
        if (!awaitingFit(cols, rows)) return 0f
        val room = height - padding - padTop
        val over = rows * cellH * s - room
        val cursorY = meta[GhosttyTerminal.META_CURSOR_Y]
        val cursorShown = meta[GhosttyTerminal.META_CURSOR_VISIBLE] != 0
        if (over > 0f) {
            // A program that hides the cursor draws its own input at the bottom.
            if (!cursorShown) return over
            return ((cursorY + 1) * cellH * s - room).coerceIn(0f, over)
        }
        val history = meta[GhosttyTerminal.META_SCROLL_TOTAL] > meta[GhosttyTerminal.META_SCROLL_LEN]
        val atBottom = !cursorShown || cursorY >= rows - 1
        return if (history && atBottom && meta[GhosttyTerminal.META_ALT] == 0) over else 0f
    }

    private val fitWaitOver = Runnable { invalidate() }

    private fun clampPan() {
        val s = scale(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS])
        val w = meta[GhosttyTerminal.META_COLS] * cellW * s + padding * 2
        val h = meta[GhosttyTerminal.META_ROWS] * cellH * s + padding + padTop
        panX = panX.coerceIn(0f, max(0f, w - width))
        panY = panY.coerceIn(0f, max(0f, h - height))
    }

    override fun onDraw(canvas: Canvas) {
        val terminal = session?.terminal
        if (terminal == null) {
            canvas.drawColor(if (haveFrame) meta[GhosttyTerminal.META_BG] else emptyColor)
            return
        }
        val isNew = terminal !== boundTerminal
        boundTerminal = terminal
        // Another terminal numbers its pictures from the start again.
        if (isNew) dropImages()
        val changed = synchronized(terminal.lock) {
            terminal.render(isNew || !haveFrame || wantAbove != gotAbove, wantAbove)
            // Another view of this terminal may have taken the change, so go by the frame's number.
            val updated = terminal.frame != seenFrame || isNew || !haveFrame
            if (updated) {
                seenFrame = terminal.frame
                gotAbove = wantAbove
                terminal.meta.copyInto(meta)
                val needed = GhosttyTerminal.cellsNeeded(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS], meta[GhosttyTerminal.META_ABOVE])
                if (cells.size < needed) cells = IntArray(needed)
                terminal.cells.copyInto(cells, 0, 0, min(needed, terminal.cells.size))
                terminal.extras.copyInto(extras, 0, 0, meta[GhosttyTerminal.META_EXTRAS_LEN])
                imageCount = terminal.imageCount
                terminal.images.copyInto(images, 0, 0, imageCount * GhosttyTerminal.IMAGE_INTS)
                loadImages(terminal)
                haveFrame = true
            }
            updated
        }
        if (!haveFrame) {
            canvas.drawColor(emptyColor)
            return
        }
        val cols = meta[GhosttyTerminal.META_COLS]
        val rows = meta[GhosttyTerminal.META_ROWS]
        val defaultBg = meta[GhosttyTerminal.META_BG]
        val defaultFg = meta[GhosttyTerminal.META_FG]
        val now = SystemClock.uptimeMillis()
        if (changed) {
            // Links are found a moment after the text settles, not for every frame of output.
            // Ones found before the view scrolled would be underlined in the wrong place.
            if (meta[GhosttyTerminal.META_SCROLL_OFFSET] != lastOffset) links = emptyList()
            lastOffset = meta[GhosttyTerminal.META_SCROLL_OFFSET]
            linksStale = true
            if (!linksPending) {
                linksPending = true
                postDelayed(findLinks, LINK_DELAY_MS)
            }
            // A cursor that just moved is shown at once, whatever the blink was doing.
            val cx = meta[GhosttyTerminal.META_CURSOR_X]
            val cy = meta[GhosttyTerminal.META_CURSOR_Y]
            if (cx != lastCursorX || cy != lastCursorY) blinkFrom = now
            lastCursorX = cx
            lastCursorY = cy
        }
        if (selecting) {
            // The engine keeps a selection on its text, so its ends are read back every frame.
            val before = selStartY * 31 + selEndY
            readSelection()
            if (changed || before != selStartY * 31 + selEndY) updateActionMode()
        }
        canvas.drawColor(defaultBg)
        // Soften the moment text rewraps to a new size, instead of letting it snap.
        if ((cols != lastCols || rows != lastRows) && lastCols != 0) reflowedAt = now
        lastCols = cols
        lastRows = rows
        val sinceReflow = now - reflowedAt
        val softening = interactive && sinceReflow < REFLOW_FADE_MS
        if (softening) {
            val t = sinceReflow.toFloat() / REFLOW_FADE_MS
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (190 + 65 * t).toInt())
            postInvalidateOnAnimation()
        }
        blinkOn = ((now - blinkFrom) / BLINK_MS) % 2 == 0L

        // With nothing above the screen there is nothing to show part of.
        if (meta[GhosttyTerminal.META_ABOVE] == 0) scrollFrac = 0f
        val rowsUp = rowsUp()
        val up = rowsUp > 0 && !appOwnsScroll
        if (up != scrolledUp) {
            scrolledUp = up
            // Not from inside a draw, where the screen it changes is already being built.
            post { onScrolledUp?.invoke(up) }
        }
        if (rowsUp == 0 && scrollFrac > 0f && !touching && scroller.isFinished) {
            // Back at the newest line: let the last part of a row slide away, so the screen's
            // bottom row and the cursor are whole again.
            scrollFrac -= max(scrollFrac * 0.4f, 1f)
            if (scrollFrac < 0.5f) scrollFrac = 0f
            postInvalidateOnAnimation()
        }

        val waiting = awaitingFit(cols, rows)
        val s = scale(cols, rows)
        drawScale = s
        originX = padding - panX
        val shift = anchorShift(cols, rows, s)
        originY = padTop - panY - shift
        // A grid moved down leaves room above it, which the lines just above the screen fill:
        // the same lines the fitted terminal is about to show there.
        val gapRows = if (shift < 0f) ceil(-shift / (cellH * s)).toInt() else 0
        if (gapRows + 1 != wantAbove) {
            wantAbove = gapRows + 1
            invalidate()
        }
        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(s, s)
        drawImages(canvas, above = false)
        drawRows(canvas, cols, rows, defaultFg, defaultBg)
        canvas.translate(0f, scrollFrac)
        drawImages(canvas, above = true)
        drawLinks(canvas, defaultFg)
        if (searchActive) drawSearchHighlights(canvas, terminal, cols)
        drawCursor(canvas, cols, rows, defaultFg, defaultBg)
        drawHandles(canvas, s)
        canvas.restore()
        if (softening) canvas.restore()
        drawScrollbar(canvas)
        if (blinks && interactive) {
            // One pending redraw, at the next change of phase. Every frame used to add its own,
            // each of which drew a frame that added another, so a burst of output left the view
            // redrawing constantly for as long as it stayed open.
            removeCallbacks(blinkRunnable)
            postOnAnimationDelayed(blinkRunnable, BLINK_MS - (now - blinkFrom) % BLINK_MS)
        }
        if (waiting) {
            // Draw once more when the wait ends, in case the server never fits the terminal.
            removeCallbacks(fitWaitOver)
            postDelayed(fitWaitOver, max(16L, FIT_WAIT_MS - (now - sizeChangedAt) + 16))
        }
    }

    private val blinkRunnable = Runnable { invalidate() }

    /** Whether the cursor blinks: the person allows it, the program asks for it and this pane has the keyboard. */
    private val blinks get() = look.cursorBlink && meta[GhosttyTerminal.META_CURSOR_BLINK] != 0 && paneFocused

    /** The pane as a picture, as it looks now, for sharing. Null before it has a size. */
    fun picture(): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0 || !haveFrame) return null
        return runCatching {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        }.getOrNull()
    }

    /** Shows the newest line again. */
    fun scrollToBottom() {
        toBottom()
        invalidate()
    }

    private val findLinks = Runnable {
        linksPending = false
        refreshLinks()
        invalidate()
    }

    private fun refreshLinks() {
        if (!linksStale || !haveFrame) return
        linksStale = false
        links = TerminalLinks.find(rowText())
    }

    private fun imageKey(at: Int) = (images[at].toLong() shl 32) or (images[at + 1].toLong() and 0xFFFFFFFFL)

    /**
     * Makes bitmaps for pictures that came on screen and drops those that left, so a program
     * redrawing a picture over and over doesn't pile them up. Called while the terminal is held,
     * since the pixels are read out of it.
     */
    private fun loadImages(terminal: GhosttyTerminal) {
        if (imageCount == 0 && bitmaps.isEmpty()) return
        val shown = HashSet<Long>(imageCount)
        for (i in 0 until imageCount) {
            val at = i * GhosttyTerminal.IMAGE_INTS
            val key = imageKey(at)
            shown += key
            if (key !in bitmaps) terminal.imageBitmap(images[at])?.let { bitmaps[key] = it }
        }
        val gone = bitmaps.keys.filter { it !in shown }
        for (key in gone) bitmaps.remove(key)?.recycle()
    }

    private fun dropImages() {
        for (bitmap in bitmaps.values) bitmap.recycle()
        bitmaps.clear()
        imageCount = 0
    }

    /**
     * Draws the pictures that sit under the text, or the ones over it. A picture's place and size
     * are in pixels of the terminal as it was sized, so they are scaled to this view's cells.
     */
    private fun drawImages(canvas: Canvas, above: Boolean) {
        if (imageCount == 0) return
        val theirW = meta[GhosttyTerminal.META_CELL_W]
        val theirH = meta[GhosttyTerminal.META_CELL_H]
        val kx = if (theirW > 0) cellW / theirW else 1f
        val ky = if (theirH > 0) cellH / theirH else 1f
        // The first pass runs before the rows are moved down by a part row; the second after.
        val shift = if (above) 0f else scrollFrac
        for (i in 0 until imageCount) {
            val at = i * GhosttyTerminal.IMAGE_INTS
            if ((images[at + 2] != 0) != above) continue
            val bitmap = bitmaps[imageKey(at)] ?: continue
            val left = images[at + 3] * cellW + images[at + 5] * kx
            val top = images[at + 4] * cellH + images[at + 6] * ky + shift
            imageSource.set(images[at + 9], images[at + 10], images[at + 9] + images[at + 11], images[at + 10] + images[at + 12])
            rect.set(left, top, left + images[at + 7] * kx, top + images[at + 8] * ky)
            canvas.drawBitmap(bitmap, imageSource, rect, bitmapPaint)
        }
    }

    /** How many rows above the newest line the view is scrolled. */
    private fun rowsUp(): Int =
        max(0, meta[GhosttyTerminal.META_SCROLL_TOTAL] - meta[GhosttyTerminal.META_SCROLL_LEN] - meta[GhosttyTerminal.META_SCROLL_OFFSET])

    /**
     * Draws the rows, and the one above the screen while the view is scrolled part of a row into
     * history. Each row is kept as a recording and only made again when its contents change, so a
     * keystroke lays out one row and scrolling lays out only the row that comes into view.
     */
    private fun drawRows(canvas: Canvas, cols: Int, rows: Int, defaultFg: Int, defaultBg: Int) {
        val gap = max(0f, originY - (padTop - panY)) / drawScale
        val wanted = ceil((gap + scrollFrac) / cellH).toInt()
        val first = -min(wanted, meta[GhosttyTerminal.META_ABOVE])
        if (!canvas.isHardwareAccelerated) {
            for (y in first until rows) {
                val row = if (y < 0) rows - 1 - y else y
                if (rowHash(row, cols, defaultFg, defaultBg) == 0L) continue
                canvas.save()
                canvas.translate(0f, y * cellH + scrollFrac)
                drawRow(canvas, row, cols, defaultFg, defaultBg)
                canvas.restore()
            }
            return
        }
        rowNodes.begin(rows + meta[GhosttyTerminal.META_ABOVE])
        for (y in first until rows) {
            val row = if (y < 0) rows - 1 - y else y
            val hash = rowHash(row, cols, defaultFg, defaultBg)
            // A row with nothing in it has nothing to draw.
            if (hash == 0L) continue
            var node = rowNodes.reuse(row, hash)
            if (node == null) {
                node = rowNodes.blank()
                node.setPosition(0, 0, ceil(cols * cellW).toInt(), cellH.toInt())
                val recording = node.beginRecording()
                try {
                    drawRow(recording, row, cols, defaultFg, defaultBg)
                } finally {
                    node.endRecording()
                }
                rowNodes.keep(row, hash, node)
            }
            canvas.save()
            canvas.translate(0f, y * cellH + scrollFrac)
            canvas.drawRenderNode(node)
            canvas.restore()
        }
        rowNodes.end()
    }

    /**
     * A fingerprint of everything that decides how a row looks, or 0 for a row that draws nothing.
     */
    private fun rowHash(row: Int, cols: Int, defaultFg: Int, defaultBg: Int): Long {
        var h = HASH_SEED
        fun mix(v: Int) {
            h = (h xor v.toLong()) * HASH_PRIME
        }
        mix(styleGeneration)
        mix(cols)
        mix(defaultFg)
        mix(defaultBg)
        var empty = true
        val from = row * cols * 4
        var i = from
        val to = from + cols * 4
        while (i < to) {
            val cp = cells[i]
            val flags = cells[i + 3]
            if (empty && !((cp == 0 || cp == 0x20) && cells[i + 2] == 0 && flags and DRAWN_WHEN_EMPTY == 0)) empty = false
            mix(cp)
            mix(cells[i + 1])
            mix(cells[i + 2])
            mix(flags)
            if (cp < 0) {
                val at = -cp - 1
                for (k in 1..extras[at]) mix(extras[at + k])
            }
            i += 4
        }
        if (empty) return 0L
        return if (h == 0L) 1L else h
    }

    /** Draws one row with its top at 0. [row] counts from the top of the frame. */
    private fun drawRow(canvas: Canvas, row: Int, cols: Int, defaultFg: Int, defaultBg: Int) {
        val rowOffset = row * cols * 4
        // Backgrounds first, so wide glyphs and overhangs aren't clipped by the next cell.
        var x = 0
        while (x < cols) {
            val i = rowOffset + x * 4
            val flags = cells[i + 3]
            val bg = effectiveBg(cells[i + 1], cells[i + 2], flags, defaultFg, defaultBg)
            if (bg != defaultBg) {
                var end = x + 1
                while (end < cols) {
                    val j = rowOffset + end * 4
                    if (effectiveBg(cells[j + 1], cells[j + 2], cells[j + 3], defaultFg, defaultBg) != bg) break
                    end++
                }
                fillPaint.color = bg
                canvas.drawRect(x * cellW, 0f, end * cellW, cellH, fillPaint)
                x = end
            } else {
                x++
            }
        }
        x = 0
        while (x < cols) {
            val i = rowOffset + x * 4
            val cp = cells[i]
            val flags = cells[i + 3]
            if (cp == 0 || cp == IMAGE_PLACEHOLDER || flags and FLAG_SPACER != 0 || flags and FLAG_INVISIBLE != 0 ||
                (cp == 0x20 && flags and DECORATIONS == 0)
            ) {
                x++
                continue
            }
            val fg = effectiveFg(cells[i + 1], cells[i + 2], flags, defaultFg, defaultBg)
            val ink = if (flags and FLAG_FAINT != 0) (fg and 0xFFFFFF) or (0x99 shl 24) else fg
            if (cp > 0 && BoxDrawing.handles(cp)) {
                BoxDrawing.draw(canvas, cp, x * cellW, 0f, cellW, cellH, ink)
                drawDecorations(canvas, x, 1, flags, ink)
                x++
                continue
            }
            // Group plain ASCII of one style into a run so it draws in a single call.
            var n = 0
            var end = x
            if (cp in 0x20..0x7E) {
                while (end < cols) {
                    val j = rowOffset + end * 4
                    val c = cells[j]
                    if (c !in 0x20..0x7E) break
                    val f = cells[j + 3]
                    if (f and RUN_STYLE != flags and RUN_STYLE) break
                    if (effectiveFg(cells[j + 1], cells[j + 2], f, defaultFg, defaultBg) != fg) break
                    if (n >= chars.size) chars = chars.copyOf(chars.size * 2)
                    chars[n++] = c.toChar()
                    end++
                }
            } else {
                end = x + if (flags and FLAG_WIDE != 0) 2 else 1
                n = appendCell(cp)
            }
            setTextStyle(flags, ink)
            if (cp in 0x20..0x7E) {
                canvas.drawText(chars, 0, n, x * cellW, baseline, textPaint)
            } else {
                drawFitted(canvas, n, x * cellW, baseline, (end - x) * cellW)
            }
            drawDecorations(canvas, x, end - x, flags, ink)
            x = end
        }
    }

    /**
     * Draws a symbol inside its cells. Fallback fonts aren't monospaced, so a wide symbol is
     * squeezed to fit and a narrow one is centered, which keeps the grid straight.
     */
    private fun drawFitted(canvas: Canvas, count: Int, left: Float, y: Float, width: Float) {
        val measured = textPaint.measureText(chars, 0, count)
        when {
            measured > width * 1.02f -> {
                textPaint.textScaleX = stretch * width / measured
                canvas.drawText(chars, 0, count, left, y, textPaint)
                textPaint.textScaleX = stretch
            }
            measured > 0f && measured < width * 0.9f -> canvas.drawText(chars, 0, count, left + (width - measured) / 2, y, textPaint)
            else -> canvas.drawText(chars, 0, count, left, y, textPaint)
        }
    }

    /** The screen as one string per row, with one character per column, for finding links. */
    private fun rowText(): List<String> {
        val cols = meta[GhosttyTerminal.META_COLS]
        val rows = meta[GhosttyTerminal.META_ROWS]
        return List(rows) { y ->
            buildString(cols) {
                for (x in 0 until cols) {
                    val cp = cells[(y * cols + x) * 4]
                    append(if (cp in 0x21..0xFFFF) cp.toChar() else ' ')
                }
            }
        }
    }

    private fun drawLinks(canvas: Canvas, defaultFg: Int) {
        drawLabelledLinks(canvas, defaultFg)
        if (links.isEmpty()) return
        fillPaint.color = (defaultFg and 0xFFFFFF) or (0x80 shl 24)
        val thin = max(1f, Math.round(density * 0.75f).toFloat())
        for (link in links) for (s in link.segments) {
            val y = s.row * cellH + min(baseline + thin * 2, cellH - thin)
            canvas.drawRect(s.start * cellW, y, (s.end + 1) * cellW, y + thin, fillPaint)
        }
    }

    /** Links a program printed with a label: their target isn't on screen, only the cells know. */
    private fun drawLabelledLinks(canvas: Canvas, defaultFg: Int) {
        val cols = meta[GhosttyTerminal.META_COLS]
        val rows = meta[GhosttyTerminal.META_ROWS]
        val thin = max(1f, Math.round(density * 0.75f).toFloat())
        fillPaint.color = (defaultFg and 0xFFFFFF) or (0x80 shl 24)
        for (y in 0 until rows) {
            var x = 0
            while (x < cols) {
                if (cells[(y * cols + x) * 4 + 3] and GhosttyTerminal.FLAG_LINK == 0) {
                    x++
                    continue
                }
                val start = x
                while (x < cols && cells[(y * cols + x) * 4 + 3] and GhosttyTerminal.FLAG_LINK != 0) x++
                val top = y * cellH + min(baseline + thin * 2, cellH - thin)
                canvas.drawRect(start * cellW, top, x * cellW, top + thin, fillPaint)
            }
        }
    }

    private fun drawSearchHighlights(canvas: Canvas, terminal: GhosttyTerminal, cols: Int) {
        val count = terminal.searchHighlights(highlights)
        for (i in 0 until count) {
            val at = i * 5
            val selected = highlights[at + 4] != 0
            fillPaint.color = if (selected) SEARCH_SELECTED else SEARCH_MATCH
            for (y in highlights[at + 1]..highlights[at + 3]) {
                val from = if (y == highlights[at + 1]) highlights[at] else 0
                val to = if (y == highlights[at + 3]) highlights[at + 2] else cols - 1
                rect.set(from * cellW, y * cellH, (to + 1) * cellW, (y + 1) * cellH)
                canvas.drawRoundRect(rect, 2 * density, 2 * density, fillPaint)
            }
        }
    }

    /** Starts or changes a search, jumping to the newest match. Returns how many there are. */
    fun search(query: String): Int {
        val terminal = session?.terminal ?: return 0
        val total = terminal.search(query)
        searchActive = true
        if (total > 0) terminal.searchStep(older = true)
        scrollFrac = 0f
        invalidate()
        return total
    }

    /** Moves between matches. Returns the match's index, counted from the newest, or -1. */
    fun searchStep(older: Boolean): Int {
        val index = session?.terminal?.searchStep(older) ?: -1
        scrollFrac = 0f
        invalidate()
        return index
    }

    fun clearSearch() {
        searchActive = false
        session?.terminal?.searchClear()
        toBottom()
        invalidate()
    }

    /** Scrolls to an earlier or later prompt, which needs shell integration. */
    fun jumpPrompt(delta: Int) {
        session?.terminal?.scrollPrompt(delta)
        scrollFrac = 0f
        scrollbarVisibleUntil = SystemClock.uptimeMillis() + 800
        invalidate()
    }

    /** Copies the latest command's output. False when the shell doesn't mark command output. */
    fun copyLastOutput(): Boolean {
        val terminal = session?.terminal ?: return false
        if (!terminal.selectLastOutput()) return false
        val text = terminal.selectionText()
        terminal.clearSelection()
        invalidate()
        if (text.isNullOrEmpty()) return false
        host?.copyText(text)
        return true
    }

    private fun linkAt(col: Int, row: Int): String? {
        refreshLinks()
        val hyperlink = session?.terminal?.hyperlinkAt(col, row)
        val url = hyperlink ?: links.firstOrNull { it.contains(col, row) }?.url ?: return null
        // A program can print any link target, so only ever hand web links to other apps.
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun appendCell(cp: Int): Int {
        if (cp > 0) return Character.toChars(cp, chars, 0)
        val at = -cp - 1
        val len = extras[at]
        var n = 0
        if (chars.size < len * 2) chars = CharArray(len * 2)
        for (k in 1..len) n += Character.toChars(extras[at + k], chars, n)
        return n
    }

    private fun setTextStyle(flags: Int, ink: Int) {
        textPaint.typeface = fonts().pick(flags and FLAG_BOLD != 0, flags and FLAG_ITALIC != 0)
        textPaint.color = ink
    }

    /** Underlines, strikes and overlines for [count] cells from column [x], in a row whose top is 0. */
    private fun drawDecorations(canvas: Canvas, x: Int, count: Int, flags: Int, ink: Int) {
        if (flags and DECORATIONS == 0) return
        fillPaint.color = ink
        val thin = max(1f, Math.round(density).toFloat())
        val left = x * cellW
        val right = (x + count) * cellW
        if (flags and FLAG_UNDERLINE != 0) {
            // Kept inside the cell, for fonts with little room under the baseline.
            val top = min(baseline + thin, cellH - thin)
            when ((flags and UNDERLINE_MASK) shr UNDERLINE_SHIFT) {
                2 -> {
                    val upper = min(top, cellH - thin * 3)
                    canvas.drawRect(left, upper, right, upper + thin, fillPaint)
                    canvas.drawRect(left, upper + thin * 2, right, upper + thin * 3, fillPaint)
                }
                3 -> {
                    // A wave, one cell long, that reads as different from a plain line.
                    val rise = thin * 1.5f
                    val middle = min(top + rise / 2, cellH - rise / 2 - thin / 2)
                    path.rewind()
                    path.moveTo(left, middle)
                    var at = left
                    while (at < right) {
                        path.rQuadTo(cellW / 4, -rise, cellW / 2, 0f)
                        path.rQuadTo(cellW / 4, rise, cellW / 2, 0f)
                        at += cellW
                    }
                    linePaint.color = ink
                    linePaint.strokeWidth = thin
                    canvas.drawPath(path, linePaint)
                }
                4 -> {
                    var at = left
                    while (at < right) {
                        canvas.drawRect(at, top, min(at + thin, right), top + thin, fillPaint)
                        at += thin * 2
                    }
                }
                5 -> {
                    val dash = max(thin * 2, Math.round(cellW / 2).toFloat())
                    var at = left
                    while (at < right) {
                        canvas.drawRect(at, top, min(at + dash, right), top + thin, fillPaint)
                        at += dash + max(thin, Math.round(dash / 2).toFloat())
                    }
                }
                else -> canvas.drawRect(left, top, right, top + thin, fillPaint)
            }
        }
        if (flags and FLAG_STRIKE != 0) {
            val top = Math.round(cellH * 0.55f).toFloat()
            canvas.drawRect(left, top, right, top + thin, fillPaint)
        }
        if (flags and FLAG_OVERLINE != 0) canvas.drawRect(left, 0f, right, thin, fillPaint)
    }

    private fun effectiveBg(fg: Int, bg: Int, flags: Int, defaultFg: Int, defaultBg: Int): Int {
        if (flags and FLAG_SELECTED != 0) return defaultFg
        return if (flags and FLAG_INVERSE != 0) (if (fg != 0) fg else defaultFg) else if (bg != 0) bg else defaultBg
    }

    private fun effectiveFg(fg: Int, bg: Int, flags: Int, defaultFg: Int, defaultBg: Int): Int {
        if (flags and FLAG_SELECTED != 0) return defaultBg
        val color = if (flags and FLAG_INVERSE != 0) (if (bg != 0) bg else defaultBg) else if (fg != 0) fg else defaultFg
        if (!look.improveContrast) return color
        return readable(color, effectiveBg(fg, bg, flags, defaultFg, defaultBg))
    }

    // The last few answers, since a screen uses a handful of color pairs over and over.
    private val contrastKeys = LongArray(64)
    private val contrastValues = IntArray(64)

    /**
     * Text too close to its background moves toward black or white until it can be read. Text set
     * to exactly its background's color is hidden on purpose, and stays hidden.
     */
    private fun readable(fg: Int, bg: Int): Int {
        if (fg == bg) return fg
        val key = (fg.toLong() shl 32) or (bg.toLong() and 0xFFFFFFFFL)
        val slot = ((key * HASH_PRIME) ushr 58).toInt()
        if (contrastKeys[slot] == key) return contrastValues[slot]
        val result = Contrast.readable(fg, bg)
        contrastKeys[slot] = key
        contrastValues[slot] = result
        return result
    }

    private fun drawCursor(canvas: Canvas, cols: Int, rows: Int, defaultFg: Int, defaultBg: Int) {
        if (!interactive || meta[GhosttyTerminal.META_CURSOR_VISIBLE] == 0) return
        val x = meta[GhosttyTerminal.META_CURSOR_X]
        val y = meta[GhosttyTerminal.META_CURSOR_Y]
        if (x !in 0 until cols || y !in 0 until rows) return
        val color = meta[GhosttyTerminal.META_CURSOR_COLOR].takeIf { it != 0 } ?: defaultFg
        val i = (y * cols + x) * 4
        val cp = cells[i]
        val flags = cells[i + 3]
        // On a wide character the cursor covers both of its cells.
        val span = if (flags and FLAG_WIDE != 0 && x + 1 < cols) 2 else 1
        val left = x * cellW
        val top = y * cellH
        val right = left + cellW * span
        val bottom = top + cellH
        val thin = max(1f, Math.round(density * 1.25f).toFloat())
        val bar = max(1f, Math.round(density * look.cursorWidth).toFloat())
        fillPaint.color = color
        val blinkedOff = blinks && !blinkOn
        val style = when (look.cursorStyle) {
            "bar" -> 0
            "block" -> 1
            "underline" -> 2
            else -> meta[GhosttyTerminal.META_CURSOR_STYLE]
        }
        if (!paneFocused || style == 3) {
            // Four edges inside the cell, which stay sharp where a stroked outline would blur.
            canvas.drawRect(left, top, right, top + thin, fillPaint)
            canvas.drawRect(left, bottom - thin, right, bottom, fillPaint)
            canvas.drawRect(left, top + thin, left + thin, bottom - thin, fillPaint)
            canvas.drawRect(right - thin, top + thin, right, bottom - thin, fillPaint)
            return
        }
        if (blinkedOff) return
        when (style) {
            0 -> canvas.drawRect(left, top, left + bar, bottom, fillPaint)
            2 -> canvas.drawRect(left, bottom - bar, right, bottom, fillPaint)
            else -> {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                // The character under a block cursor, in the background's color.
                if (cp == 0 || cp == 0x20 || flags and FLAG_INVISIBLE != 0) return
                if (cp > 0 && BoxDrawing.handles(cp)) {
                    BoxDrawing.draw(canvas, cp, left, top, cellW, cellH, defaultBg)
                    return
                }
                val n = appendCell(cp)
                setTextStyle(flags, defaultBg)
                if (cp in 0x20..0x7E) {
                    canvas.drawText(chars, 0, n, left, top + baseline, textPaint)
                } else {
                    drawFitted(canvas, n, left, top + baseline, right - left)
                }
            }
        }
    }

    private fun drawHandles(canvas: Canvas, s: Float) {
        if (!selecting) return
        handlePaint.color = meta[GhosttyTerminal.META_FG]
        val r = HANDLE_RADIUS_DP * density / s
        val rows = meta[GhosttyTerminal.META_ROWS]
        val (sx, sy, ex, ey) = ordered()
        // An end that has scrolled out of view has no handle to hold.
        if (sy in 0 until rows) canvas.drawCircle(sx * cellW, sy * cellH, r, handlePaint)
        if (ey in 0 until rows) canvas.drawCircle((ex + 1) * cellW, (ey + 1) * cellH, r, handlePaint)
    }

    // The scroll position marker, which can be held and dragged while it shows.
    private var draggingBar = false
    private var barGrab = 0f
    private var barTop = 0f
    private var barHeight = 0f

    /** Works out where the marker is. False when there is no history to scroll through. */
    private fun placeBar(): Boolean {
        val total = meta[GhosttyTerminal.META_SCROLL_TOTAL]
        val len = meta[GhosttyTerminal.META_SCROLL_LEN]
        if (total <= len || total == 0) return false
        val h = height.toFloat()
        // Tall enough to put a finger on, however long the history is.
        barHeight = min(h, max(h * len / total, BAR_MIN_DP * density))
        barTop = (h - barHeight) * meta[GhosttyTerminal.META_SCROLL_OFFSET] / max(1, total - len)
        return true
    }

    private fun drawScrollbar(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (draggingBar) scrollbarVisibleUntil = now + 800
        if (now > scrollbarVisibleUntil || !placeBar()) return
        val w = (if (draggingBar) 5 else 3) * density
        val remaining = scrollbarVisibleUntil - now
        val strength = if (draggingBar) 0xC0 else 0x80
        val alpha = (strength * min(1f, remaining.toFloat() / SCROLLBAR_FADE_MS)).toInt()
        fillPaint.color = (meta[GhosttyTerminal.META_FG] and 0xFFFFFF) or (alpha shl 24)
        rect.set(width - w - 2 * density, barTop, width - 2 * density, barTop + barHeight)
        canvas.drawRoundRect(rect, w, w, fillPaint)
        if (draggingBar) return
        if (remaining > SCROLLBAR_FADE_MS) postInvalidateDelayed(remaining - SCROLLBAR_FADE_MS) else postInvalidateOnAnimation()
    }

    /** True for a touch that lands on the marker while it shows, with room around it for a finger. */
    private fun onBar(x: Float, y: Float): Boolean {
        if (appOwnsScroll || SystemClock.uptimeMillis() > scrollbarVisibleUntil || !placeBar()) return false
        val reach = 12 * density
        return x >= width - BAR_TOUCH_DP * density && y >= barTop - reach && y <= barTop + barHeight + reach
    }

    /** Moves through history as the marker is dragged: its place along the pane is the place in history. */
    private fun dragBarTo(y: Float) {
        val terminal = session?.terminal ?: return
        if (!placeBar()) return
        val range = meta[GhosttyTerminal.META_SCROLL_TOTAL] - meta[GhosttyTerminal.META_SCROLL_LEN]
        val track = height - barHeight
        val part = if (track > 0f) ((y - barGrab) / track).coerceIn(0f, 1f) else 1f
        val target = Math.round(part * range)
        val delta = target - meta[GhosttyTerminal.META_SCROLL_OFFSET]
        if (delta != 0) {
            terminal.scroll(delta)
            // The next frame brings the real position; until then this keeps the marker under the finger.
            meta[GhosttyTerminal.META_SCROLL_OFFSET] = target
            scrollFrac = 0f
        }
        invalidate()
    }

    // Input method.

    override fun onCheckIsTextEditor() = interactive

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password mode keeps keyboards from autocorrecting and composing what's typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return TerminalInputConnection()
    }

    fun showKeyboard() {
        if (!interactive) return
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString(), final = true)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString(), final = false)
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        /** Keyboards may rewrite composing text, so send only what differs from before. */
        private fun replaceComposing(new: String, final: Boolean) {
            val common = composing.commonPrefixWith(new).length
            repeat(composing.length - common) { sendPlainKey(RexKey.BACKSPACE) }
            val added = new.substring(common)
            if (added.isNotEmpty()) typed(added)
            composing = if (final) "" else new
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { sendPlainKey(RexKey.BACKSPACE) }
            repeat(afterLength) { sendPlainKey(RexKey("delete")) }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> this@TerminalView.onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> this@TerminalView.onKeyUp(event.keyCode, event)
            }
            return true
        }
    }

    /** Back to the newest line, which is where typing happens. */
    private fun toBottom() {
        scroller.forceFinished(true)
        scrollFrac = 0f
        session?.terminal?.scrollToBottom()
    }

    /** Call when something was typed: shows the newest line and the cursor, whatever its blink was doing. */
    private fun typing() {
        toBottom()
        blinkFrom = SystemClock.uptimeMillis()
    }

    private fun sendPlainKey(key: RexKey) {
        val h = host ?: return
        typing()
        val sticky = h.consumeStickyMods()
        h.sendKey(if (sticky == 0) key else key.copy(mods = RexKey.modsString(sticky), text = ""))
    }

    private fun typed(text: String) {
        val h = host ?: return
        typing()
        h.sendText(text)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val h = host ?: return super.onKeyDown(keyCode, event)
        if (!interactive) return super.onKeyDown(keyCode, event)
        val key = KeyMapper.fromEvent(event, 0, if (event.repeatCount > 0) "repeat" else "press")
            ?: return super.onKeyDown(keyCode, event)
        if (isModifierOnly(keyCode)) {
            h.sendKey(key)
            return true
        }
        typing()
        val sticky = h.consumeStickyMods()
        h.sendKey(if (sticky == 0) key else KeyMapper.fromEvent(event, sticky, "press") ?: key)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val h = host ?: return super.onKeyUp(keyCode, event)
        if (!interactive) return super.onKeyUp(keyCode, event)
        val key = KeyMapper.fromEvent(event, 0, "release") ?: return super.onKeyUp(keyCode, event)
        h.sendKey(key)
        return true
    }

    private fun isModifierOnly(keyCode: Int) = keyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
        keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT || keyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
        keyCode == KeyEvent.KEYCODE_CTRL_RIGHT || keyCode == KeyEvent.KEYCODE_ALT_LEFT ||
        keyCode == KeyEvent.KEYCODE_ALT_RIGHT || keyCode == KeyEvent.KEYCODE_META_LEFT ||
        keyCode == KeyEvent.KEYCODE_META_RIGHT

    // Touch.

    private fun cellX(px: Float): Int =
        floor((px - originX) / (cellW * drawScale)).toInt().coerceIn(0, max(0, meta[GhosttyTerminal.META_COLS] - 1))

    private fun cellY(py: Float): Int =
        floor(((py - originY) / drawScale - scrollFrac) / cellH).toInt().coerceIn(0, max(0, meta[GhosttyTerminal.META_ROWS] - 1))

    private val appOwnsScroll get() = meta[GhosttyTerminal.META_MOUSE] != 0 || meta[GhosttyTerminal.META_ALT] != 0

    /**
     * Scrolls by [dy] pixels, positive towards newer lines. False when there was nowhere to go,
     * which ends a fling at either end of the history.
     */
    private fun scrollByPixels(dy: Float, x: Float, y: Float): Boolean {
        if (appOwnsScroll) {
            scrollRemainder += dy / (cellH * drawScale)
            val lines = scrollRemainder.toInt()
            if (lines == 0) return true
            scrollRemainder -= lines
            if (meta[GhosttyTerminal.META_MOUSE] != 0) {
                host?.sendWheel(lines.toFloat(), cellX(x), cellY(y))
            } else {
                // Full-screen programs without mouse support, such as less, scroll with arrow keys.
                val key = if (lines > 0) RexKey.DOWN else RexKey.UP
                repeat(min(abs(lines), MAX_ARROWS_PER_MOVE)) { host?.sendKey(key) }
            }
            return true
        }
        val terminal = session?.terminal ?: return false
        // The text follows the finger exactly. Whole rows of the distance go to the engine, and
        // what is left over moves the drawing by part of a row.
        val before = scrollFrac
        scrollFrac -= dy / drawScale
        val above = meta[GhosttyTerminal.META_SCROLL_OFFSET]
        val wanted = floor(scrollFrac / cellH).toInt()
        val older = wanted.coerceIn(-rowsUp(), above)
        if (older != 0) {
            terminal.scroll(-older)
            scrollFrac -= older * cellH
            // The next frame brings the real position; until then this keeps further moves right.
            meta[GhosttyTerminal.META_SCROLL_OFFSET] = above - older
        }
        // At the oldest line there is no row above to show part of, and nothing newer than the newest.
        val limit = if (meta[GhosttyTerminal.META_SCROLL_OFFSET] > 0) cellH else 0f
        scrollFrac = scrollFrac.coerceIn(0f, max(0f, limit - 0.01f))
        scrollbarVisibleUntil = SystemClock.uptimeMillis() + 800
        invalidate()
        return older != 0 || scrollFrac != before
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selecting) {
                clearSelection()
                return true
            }
            val col = cellX(e.x)
            val row = cellY(e.y)
            if (meta[GhosttyTerminal.META_MOUSE] == 0) {
                val url = linkAt(col, row)
                if (url != null) {
                    host?.openUrl(url)
                    return true
                }
            }
            // Read before the tap is passed on, which may be what brings the keyboard up.
            val typingHere = paneFocused && keyboardShown()
            host?.onTapped()
            if (meta[GhosttyTerminal.META_MOUSE] != 0) {
                host?.sendMouse("press", "left", col, row)
                host?.sendMouse("release", "left", col, row)
            } else if (typingHere) {
                moveCursorTo(col, row)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling || selecting) return true
            if (keepSize && zoom > 1f) {
                panX += distanceX
                panY += distanceY
                clampPan()
                invalidate()
                return true
            }
            // Dragging up means moving towards newer lines, so the sign carries straight through.
            scrollByPixels(distanceY, e2.x, e2.y)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaling || selecting || appOwnsScroll || (keepSize && zoom > 1f)) return true
            lastFlingY = 0
            scroller.fling(0, 0, 0, velocityY.toInt(), 0, 0, -FLING_RANGE, FLING_RANGE)
            postOnAnimation(flingRunnable)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            startSelection(cellX(e.x), cellY(e.y))
        }

        /** A second tap selects the word under it. A program that takes the mouse gets the taps itself. */
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaling || meta[GhosttyTerminal.META_MOUSE] != 0) return false
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            startSelection(cellX(e.x), cellY(e.y))
            doubleTapAt = SystemClock.uptimeMillis()
            doubleTapRow = cellY(e.y)
            return true
        }
    })

    private var doubleTapAt = 0L
    private var doubleTapRow = -1
    /** True while the rest of a touch that already did its work is ignored. */
    private var swallowTouch = false

    /** Selects a whole row, for a third tap. */
    private fun selectLine(row: Int) {
        val terminal = session?.terminal ?: return
        terminal.selectRange(0, row, max(0, meta[GhosttyTerminal.META_COLS] - 1), row)
        selecting = true
        draggingHandle = 0
        readSelection()
        invalidate()
        updateActionMode()
    }

    private fun keyboardShown() = rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true

    /**
     * Moves the cursor to a tapped place on the line being typed, by sending the arrow keys a
     * keyboard would need. Only on the cursor's own line, which may be wrapped over several rows,
     * and never in a full-screen program, where arrow keys mean something else.
     */
    private fun moveCursorTo(col: Int, row: Int) {
        val h = host ?: return
        if (!look.tapMovesCursor || appOwnsScroll || meta[GhosttyTerminal.META_CURSOR_VISIBLE] == 0 || rowsUp() != 0) return
        val cols = meta[GhosttyTerminal.META_COLS]
        val rows = meta[GhosttyTerminal.META_ROWS]
        val cx = meta[GhosttyTerminal.META_CURSOR_X]
        val cy = meta[GhosttyTerminal.META_CURSOR_Y]
        if (cx !in 0 until cols || cy !in 0 until rows || row !in 0 until rows) return
        for (y in min(row, cy) until max(row, cy)) {
            if (cells[y * cols * 4 + 3] and GhosttyTerminal.FLAG_WRAPS == 0) return
        }
        // A tap past the end of the text goes to its end.
        var end = cols
        while (end > 0 && cells[(row * cols + end - 1) * 4] == 0) end--
        if (row == cy) end = max(end, cx)
        val from = cy * cols + cx
        val to = row * cols + min(col, min(end, cols - 1))
        if (to == from) return
        // One key for each character between, where a wide character is two cells.
        var steps = 0
        for (i in min(from, to) until max(from, to)) {
            if (cells[i * 4 + 3] and FLAG_SPACER == 0) steps++
        }
        val key = if (to > from) RexKey.RIGHT else RexKey.LEFT
        repeat(min(steps, MAX_CURSOR_STEPS)) { h.sendKey(key) }
        blinkFrom = SystemClock.uptimeMillis()
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val y = scroller.currY
            val moved = scrollByPixels((lastFlingY - y).toFloat(), width / 2f, height / 2f)
            lastFlingY = y
            if (moved || y == 0) postOnAnimation(this) else scroller.forceFinished(true)
            // The draw that follows lets a part row at the newest line slide away.
            invalidate()
        }
    }

    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (keepSize) {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                clampPan()
                invalidate()
                return true
            }
            val next = (fontSizeSp * detector.scaleFactor).coerceIn(MIN_FONT, MAX_FONT)
            if (abs(next - fontSizeSp) >= 0.25f) {
                fontSizeSp = next
                return true
            }
            return false
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (!keepSize) host?.onFontSizeChanged(fontSizeSp)
            post { scaling = false }
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        // Mice and trackpads report real buttons; pass those to programs that want them.
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && meta[GhosttyTerminal.META_MOUSE] != 0) {
            return handleRealMouse(event)
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                invalidate()
            }
        }
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (swallowTouch) {
            if (ended) swallowTouch = false
            return true
        }
        if (draggingBar) {
            if (ended) {
                draggingBar = false
                scrollbarVisibleUntil = SystemClock.uptimeMillis() + 800
                invalidate()
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                dragBarTo(event.y)
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (onBar(event.x, event.y)) {
                scroller.forceFinished(true)
                draggingBar = true
                barGrab = event.y - barTop
                performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_START)
                invalidate()
                return true
            }
            // A third tap, soon after the two that selected a word, takes the whole line.
            val row = cellY(event.y)
            if (selecting && row == doubleTapRow && SystemClock.uptimeMillis() - doubleTapAt < TRIPLE_TAP_MS) {
                doubleTapAt = 0L
                selectLine(row)
                swallowTouch = true
                cancelGestures(event)
                return true
            }
        }
        scaleDetector.onTouchEvent(event)
        if (selecting && handleSelectionDrag(event)) {
            // The detector saw this touch start and won't see it end. Left waiting, it would
            // call the still finger a long press a moment later and select all over again.
            if (ended) cancelGestures(event)
            return true
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scrollRemainder = 0f
            draggingHandle = 0
            if (selecting) updateActionMode()
        }
        return true
    }

    /** Tells the gesture detector that a touch it was following is over, so it stops its timers. */
    private fun cancelGestures(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        gestures.onTouchEvent(cancel)
        cancel.recycle()
    }

    private var mouseButton = "left"

    private fun handleRealMouse(event: MotionEvent): Boolean {
        val col = cellX(event.x)
        val row = cellY(event.y)
        val button = when {
            event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) -> "right"
            event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) -> "middle"
            else -> "left"
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mouseButton = button
                host?.sendMouse("press", button, col, row)
            }
            MotionEvent.ACTION_MOVE -> host?.sendMouse("motion", button, col, row)
            // No button is pressed any more by now, so release the one that went down.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host?.sendMouse("release", mouseButton, col, row)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (interactive && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val lines = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3
            scrollByPixels(lines * cellH * drawScale, event.x, event.y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Selection.

    private data class Ordered(val sx: Int, val sy: Int, val ex: Int, val ey: Int)

    private fun ordered(): Ordered =
        if (selStartY < selEndY || (selStartY == selEndY && selStartX <= selEndX)) {
            Ordered(selStartX, selStartY, selEndX, selEndY)
        } else {
            Ordered(selEndX, selEndY, selStartX, selStartY)
        }

    private val selectionBounds = IntArray(4)

    /** Reads where the selection's ends are now. A row above the screen is negative; one below is past its last. */
    private fun readSelection() {
        val terminal = session?.terminal ?: return
        if (!terminal.selectionBounds(selectionBounds)) return
        selStartX = selectionBounds[0]
        selStartY = selectionBounds[1]
        selEndX = selectionBounds[2]
        selEndY = selectionBounds[3]
    }

    private fun startSelection(x: Int, y: Int) {
        val terminal = session?.terminal ?: return
        selStartX = x
        selStartY = y
        selEndX = x
        selEndY = y
        terminal.selectRange(x, y, x, y)
        selecting = true
        // Grow to the word under the finger when there is one; otherwise a single cell is selected.
        terminal.selectWord(x, y)
        // The finger is still down, and dragging on from here moves the selection's end.
        terminal.grabSelection(first = false)
        readSelection()
        draggingHandle = 2
        invalidate()
        updateActionMode()
    }

    private var dragX = 0f
    private var dragY = 0f
    private var edgeDirection = 0
    private var edgeScrolling = false

    /** Moves the held end of the selection under the finger, and scrolls while the finger rests at an edge. */
    private fun dragSelectionTo(px: Float, py: Float) {
        val terminal = session?.terminal ?: return
        dragX = px
        dragY = py
        terminal.extendSelection(cellX(px), cellY(py))
        readSelection()
        invalidate()
        val top = max(0f, originY)
        val bottom = min(height.toFloat(), originY + meta[GhosttyTerminal.META_ROWS] * cellH * drawScale)
        val zone = cellH * drawScale * 1.2f
        edgeDirection = when {
            py < top + zone && meta[GhosttyTerminal.META_SCROLL_OFFSET] > 0 && !appOwnsScroll -> -1
            py > bottom - zone && rowsUp() > 0 && !appOwnsScroll -> 1
            else -> 0
        }
        if (edgeDirection != 0 && !edgeScrolling) {
            edgeScrolling = true
            postDelayed(edgeScroll, EDGE_SCROLL_MS)
        }
    }

    private val edgeScroll = object : Runnable {
        override fun run() {
            val terminal = session?.terminal
            if (terminal == null || draggingHandle == 0 || edgeDirection == 0) {
                edgeScrolling = false
                return
            }
            scrollFrac = 0f
            terminal.scroll(edgeDirection)
            terminal.extendSelection(cellX(dragX), cellY(dragY))
            scrollbarVisibleUntil = SystemClock.uptimeMillis() + 800
            invalidate()
            postDelayed(this, EDGE_SCROLL_MS)
        }
    }

    private fun handleSelectionDrag(event: MotionEvent): Boolean {
        val s = drawScale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (sx, sy, ex, ey) = ordered()
                val reach = HANDLE_TOUCH_DP * density
                val startPx = originX + sx * cellW * s
                val startPy = originY + (sy * cellH + scrollFrac) * s
                val endPx = originX + (ex + 1) * cellW * s
                val endPy = originY + ((ey + 1) * cellH + scrollFrac) * s
                val nearStart = abs(event.x - startPx) < reach && abs(event.y - startPy) < reach
                val nearEnd = abs(event.x - endPx) < reach && abs(event.y - endPy) < reach
                draggingHandle = when {
                    nearEnd -> 2
                    nearStart -> 1
                    else -> 0
                }
                if (draggingHandle != 0) {
                    // The opposite end stays on its text while this one follows the finger.
                    session?.terminal?.grabSelection(first = draggingHandle == 1)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle == 0) return false
                dragSelectionTo(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = draggingHandle
                draggingHandle = 0
                edgeDirection = 0
                updateActionMode()
                return was != 0
            }
        }
        return false
    }

    fun clearSelection() {
        selecting = false
        session?.terminal?.clearSelection()
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    private fun selectedText() = session?.terminal?.selectionText()

    private fun updateActionMode() {
        if (!selecting) return
        val mode = actionMode
        if (mode != null) {
            mode.invalidateContentRect()
            return
        }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, ID_COPY, 0, android.R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, ID_PASTE, 1, android.R.string.paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, ID_SHARE, 2, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, ID_OPEN, 2, "Open link").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                menu.add(0, ID_OUTPUT, 3, "Copy command output").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    ID_COPY -> selectedText()?.let { host?.copyText(it) }
                    ID_PASTE -> host?.clipboardText()?.let { host?.paste(it) }
                    ID_SHARE -> selectedText()?.takeIf { it.isNotEmpty() }?.let { host?.shareText(it) }
                    ID_OUTPUT -> {
                        val terminal = session?.terminal
                        val (sx, sy) = ordered()
                        val row = sy.coerceIn(0, max(0, meta[GhosttyTerminal.META_ROWS] - 1))
                        val text = if (terminal?.selectOutputAt(sx, row) == true) terminal.selectionText() else null
                        if (text.isNullOrEmpty()) {
                            host?.showMessage(NO_OUTPUT_MARKS)
                        } else {
                            host?.copyText(text)
                        }
                    }
                    ID_OPEN -> selectedText()?.trim()?.let { text ->
                        val url = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
                        host?.openUrl(url)
                    }
                }
                clearSelection()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                if (selecting) clearSelection()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                val s = drawScale
                val (sx, sy, ex, ey) = ordered()
                val left = originX
                val top = originY + scrollFrac * s
                // The part of the selection that is on screen, so the menu stays beside it.
                val last = max(0, meta[GhosttyTerminal.META_ROWS] - 1)
                outRect.set(
                    (left + sx * cellW * s).toInt(),
                    (top + sy.coerceIn(0, last) * cellH * s).toInt(),
                    (left + (ex + 1) * cellW * s).toInt(),
                    (top + (ey.coerceIn(0, last) + 1) * cellH * s).toInt(),
                )
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        session?.removeListener(this)
        session = null
        actionMode?.finish()
        removeCallbacks(blinkRunnable)
        removeCallbacks(flingRunnable)
        removeCallbacks(fitWaitOver)
        removeCallbacks(findLinks)
        removeCallbacks(edgeScroll)
        edgeScrolling = false
        linksPending = false
        scroller.forceFinished(true)
        rowNodes.clear()
        dropImages()
    }

    /**
     * The recordings of the rows on screen. A row found again in the next frame, in its old place
     * or on another line after scrolling, is drawn from its recording instead of being laid out again.
     */
    private class RowNodes {
        private var hashes = LongArray(0)
        private var nodes = arrayOfNulls<RenderNode>(0)
        private var oldHashes = LongArray(0)
        private var oldNodes = arrayOfNulls<RenderNode>(0)
        private val spare = ArrayList<RenderNode>()

        /** Starts a frame of [count] rows; the last frame's recordings stay at hand for it. */
        fun begin(count: Int) {
            val h = oldHashes
            val n = oldNodes
            oldHashes = hashes
            oldNodes = nodes
            hashes = if (h.size == count) h else LongArray(count)
            nodes = if (n.size == count) n else arrayOfNulls(count)
            hashes.fill(0L)
            nodes.fill(null)
        }

        /** The last frame's recording of a row with this fingerprint, looking at the same line first. */
        fun reuse(row: Int, hash: Long): RenderNode? {
            var found = -1
            if (row < oldHashes.size && oldHashes[row] == hash && oldNodes[row] != null) {
                found = row
            } else {
                for (i in oldHashes.indices) {
                    if (oldHashes[i] == hash && oldNodes[i] != null) {
                        found = i
                        break
                    }
                }
            }
            if (found < 0) return null
            val node = oldNodes[found]
            oldNodes[found] = null
            keep(row, hash, node!!)
            return node
        }

        /** A recording to draw a new row into. */
        fun blank(): RenderNode = spare.removeLastOrNull() ?: RenderNode("row").apply { clipToBounds = false }

        fun keep(row: Int, hash: Long, node: RenderNode) {
            hashes[row] = hash
            nodes[row] = node
        }

        /** Ends a frame: recordings no row used become spares for the rows that change next. */
        fun end() {
            for (i in oldNodes.indices) {
                val node = oldNodes[i] ?: continue
                oldNodes[i] = null
                if (spare.size < MAX_SPARE) spare += node else node.discardDisplayList()
            }
        }

        fun clear() {
            for (node in nodes) node?.discardDisplayList()
            for (node in oldNodes) node?.discardDisplayList()
            for (node in spare) node.discardDisplayList()
            hashes = LongArray(0)
            nodes = arrayOfNulls(0)
            oldHashes = LongArray(0)
            oldNodes = arrayOfNulls(0)
            spare.clear()
        }

        private companion object {
            const val MAX_SPARE = 64
        }
    }

    companion object {
        const val MIN_FONT = 6f
        const val MAX_FONT = 32f
        private const val MAX_ZOOM = 4f
        private const val SIZE_SETTLE_MS = 450L
        /** How long the text keeps its size while the server is asked to fit the terminal. */
        private const val FIT_WAIT_MS = 1500L
        private const val LINK_DELAY_MS = 120L
        /** The character a program writes where a picture goes; the picture is drawn, not this. */
        private const val IMAGE_PLACEHOLDER = 0x10EEEE
        private const val EDGE_SCROLL_MS = 45L
        private const val MAX_CURSOR_STEPS = 400
        private const val RULER = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"
        private const val HASH_SEED = -0x340d631b7bdddcdbL
        private const val HASH_PRIME = 0x100000001b3L
        private const val DECORATIONS = FLAG_UNDERLINE or FLAG_STRIKE or FLAG_OVERLINE
        /** What must match for two cells to be drawn in one run of text. */
        private const val RUN_STYLE = FLAG_BOLD or FLAG_ITALIC or FLAG_FAINT or DECORATIONS or UNDERLINE_MASK
        /** Flags that make even an empty cell draw something. */
        private const val DRAWN_WHEN_EMPTY = FLAG_INVERSE or FLAG_SELECTED or DECORATIONS
        private const val REFLOW_FADE_MS = 160L
        private const val SCROLLBAR_FADE_MS = 250L
        private const val MAX_ARROWS_PER_MOVE = 8
        private const val BLINK_MS = 530L
        private const val HANDLE_RADIUS_DP = 6f
        private const val HANDLE_TOUCH_DP = 28f
        private const val FLING_RANGE = 200_000
        private const val ID_COPY = 1
        private const val ID_PASTE = 2
        private const val ID_OPEN = 3
        private const val ID_OUTPUT = 4
        private const val ID_SHARE = 5
        private const val TRIPLE_TAP_MS = 450L
        private const val BAR_MIN_DP = 40f
        private const val BAR_TOUCH_DP = 32f
        private const val SEARCH_MATCH = 0x66F59E0B
        private const val SEARCH_SELECTED = 0xB3F97316.toInt()
        const val NO_OUTPUT_MARKS = "This shell doesn't mark command output. \"Set up prompt jumps\" in the command menu turns that on."
    }
}
