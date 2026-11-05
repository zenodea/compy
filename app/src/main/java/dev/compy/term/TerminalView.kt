package dev.compy.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import kotlin.math.abs
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
    }

    var host: Host? = null
    var interactive = true
        set(value) {
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        fontFeatureSettings = "'liga' 0, 'calt' 0"
    }
    private val fillPaint = Paint()
    private val rect = RectF()
    private val path = Path()

    private var cellW = 8f
    private var cellH = 16f
    private var baseline = 12f

    private val meta = IntArray(GhosttyTerminal.META_COUNT)
    private var cells = IntArray(0)
    private val extras = IntArray(8192)
    private var haveFrame = false
    private var chars = CharArray(512)

    /** Keeps text clear of the pane's rounded corners. */
    private val padding = 6 * density

    // Selection, in viewport cells.
    private var selecting = false
    private var selStartX = 0
    private var selStartY = 0
    private var selEndX = 0
    private var selEndY = 0
    private var draggingHandle = 0
    private var actionMode: ActionMode? = null

    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private var scrollbarVisibleUntil = 0L
    private var blinkOn = true

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
        newSession?.addListener(this)
        invalidate()
    }

    override fun onTerminalChanged() {
        postInvalidateOnAnimation()
    }

    private fun measureCell() {
        textPaint.typeface = fonts().regular
        textPaint.textSize = fontSizeSp * scaledDensity
        val fm = textPaint.fontMetrics
        cellW = textPaint.measureText("M")
        cellH = Math.ceil((fm.descent - fm.ascent).toDouble()).toFloat()
        baseline = -fm.ascent
    }

    /** Call after the font changes. */
    fun fontsChanged() {
        measureCell()
        reportGrid()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
    }

    private fun reportGrid() {
        if (width <= 0 || height <= 0 || !interactive) return
        val avail = width - padding * 2
        val availH = height - padding * 2
        val cols = max(2, floor(avail / cellW).toInt())
        val rows = max(1, floor(availH / cellH).toInt())
        host?.onGridMeasured(cols, rows, Math.round(cellW), Math.round(cellH))
    }

    private fun scale(cols: Int, rows: Int): Float {
        if (cols <= 0 || rows <= 0 || width <= 0 || height <= 0) return 1f
        val w = cols * cellW + padding * 2
        val h = rows * cellH + padding * 2
        val fit = min(width / w, height / h)
        return min(1f, fit)
    }

    override fun onDraw(canvas: Canvas) {
        val terminal = session?.terminal
        if (terminal == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val isNew = terminal !== boundTerminal
        boundTerminal = terminal
        val changed = synchronized(terminal.lock) {
            val updated = terminal.render(isNew || !haveFrame)
            if (updated) {
                terminal.meta.copyInto(meta)
                val needed = meta[GhosttyTerminal.META_COLS] * meta[GhosttyTerminal.META_ROWS] * 4
                if (cells.size < needed) cells = IntArray(needed)
                terminal.cells.copyInto(cells, 0, 0, min(needed, terminal.cells.size))
                terminal.extras.copyInto(extras, 0, 0, meta[GhosttyTerminal.META_EXTRAS_LEN])
                haveFrame = true
            }
            updated
        }
        if (!haveFrame) {
            canvas.drawColor(Color.BLACK)
            return
        }
        if (changed && selecting) updateActionMode()
        val cols = meta[GhosttyTerminal.META_COLS]
        val rows = meta[GhosttyTerminal.META_ROWS]
        val defaultBg = meta[GhosttyTerminal.META_BG]
        val defaultFg = meta[GhosttyTerminal.META_FG]
        canvas.drawColor(defaultBg)
        val s = scale(cols, rows)
        canvas.save()
        canvas.translate(padding, padding)
        canvas.scale(s, s)
        for (y in 0 until rows) drawRow(canvas, y, cols, defaultFg, defaultBg)
        drawCursor(canvas, cols, rows, defaultFg, defaultBg)
        drawHandles(canvas, s)
        canvas.restore()
        drawScrollbar(canvas)
        val blinking = meta[GhosttyTerminal.META_CURSOR_BLINK] != 0 && paneFocused && interactive
        if (blinking) postOnAnimationDelayed(blinkRunnable, BLINK_MS)
        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    private val blinkRunnable = Runnable {
        blinkOn = (SystemClock.uptimeMillis() / BLINK_MS) % 2 == 0L
        invalidate()
    }

    private fun drawRow(canvas: Canvas, y: Int, cols: Int, defaultFg: Int, defaultBg: Int) {
        val top = y * cellH
        val rowOffset = y * cols * 4
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
                rect.set(snap(x * cellW), snap(top), snap(end * cellW), snap(top + cellH))
                canvas.drawRect(rect, fillPaint)
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
            if (cp == 0 || flags and FLAG_SPACER != 0 || flags and FLAG_INVISIBLE != 0) {
                x++
                continue
            }
            val fg = effectiveFg(cells[i + 1], cells[i + 2], flags, defaultFg, defaultBg)
            if (cp > 0 && BoxDrawing.handles(cp)) {
                fillPaint.color = fg
                BoxDrawing.draw(canvas, cp, x * cellW, top, cellW, cellH, fillPaint, rect, path)
                drawDecorations(canvas, x, 1, top, flags, fg)
                x++
                continue
            }
            // Group plain ASCII of one style into a run so it draws in a single call.
            val styleMask = FLAG_BOLD or FLAG_ITALIC or FLAG_FAINT
            var n = 0
            var end = x
            if (cp in 0x20..0x7E) {
                while (end < cols) {
                    val j = rowOffset + end * 4
                    val c = cells[j]
                    if (c !in 0x20..0x7E) break
                    val f = cells[j + 3]
                    if (f and styleMask != flags and styleMask || f and (FLAG_UNDERLINE or FLAG_STRIKE or FLAG_OVERLINE) != flags and (FLAG_UNDERLINE or FLAG_STRIKE or FLAG_OVERLINE)) break
                    if (effectiveFg(cells[j + 1], cells[j + 2], f, defaultFg, defaultBg) != fg) break
                    if (n >= chars.size) chars = chars.copyOf(chars.size * 2)
                    chars[n++] = c.toChar()
                    end++
                }
            } else {
                end = x + if (flags and FLAG_WIDE != 0) 2 else 1
                n = appendCell(cp)
            }
            setTextStyle(flags, fg)
            canvas.drawText(chars, 0, n, x * cellW, top + baseline, textPaint)
            drawDecorations(canvas, x, end - x, top, flags, fg)
            x = end
        }
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

    private fun setTextStyle(flags: Int, fg: Int) {
        textPaint.typeface = fonts().pick(flags and FLAG_BOLD != 0, flags and FLAG_ITALIC != 0)
        textPaint.color = if (flags and FLAG_FAINT != 0) (fg and 0xFFFFFF) or (0x99 shl 24) else fg
    }

    private fun drawDecorations(canvas: Canvas, x: Int, count: Int, top: Float, flags: Int, fg: Int) {
        if (flags and (FLAG_UNDERLINE or FLAG_STRIKE or FLAG_OVERLINE) == 0) return
        fillPaint.color = fg
        val thin = max(1f, density)
        val left = x * cellW
        val right = (x + count) * cellW
        if (flags and FLAG_UNDERLINE != 0) canvas.drawRect(left, top + baseline + thin, right, top + baseline + thin * 2, fillPaint)
        if (flags and FLAG_STRIKE != 0) canvas.drawRect(left, top + cellH * 0.55f, right, top + cellH * 0.55f + thin, fillPaint)
        if (flags and FLAG_OVERLINE != 0) canvas.drawRect(left, top, right, top + thin, fillPaint)
    }

    private fun effectiveBg(fg: Int, bg: Int, flags: Int, defaultFg: Int, defaultBg: Int): Int {
        if (flags and FLAG_SELECTED != 0) return selectionBg(defaultFg, defaultBg)
        return if (flags and FLAG_INVERSE != 0) (if (fg != 0) fg else defaultFg) else if (bg != 0) bg else defaultBg
    }

    private fun effectiveFg(fg: Int, bg: Int, flags: Int, defaultFg: Int, defaultBg: Int): Int {
        if (flags and FLAG_SELECTED != 0) return selectionFg(defaultBg)
        return if (flags and FLAG_INVERSE != 0) (if (bg != 0) bg else defaultBg) else if (fg != 0) fg else defaultFg
    }

    private fun selectionBg(defaultFg: Int, defaultBg: Int) = defaultFg
    private fun selectionFg(defaultBg: Int) = defaultBg

    private fun drawCursor(canvas: Canvas, cols: Int, rows: Int, defaultFg: Int, defaultBg: Int) {
        if (!interactive || meta[GhosttyTerminal.META_CURSOR_VISIBLE] == 0) return
        val x = meta[GhosttyTerminal.META_CURSOR_X]
        val y = meta[GhosttyTerminal.META_CURSOR_Y]
        if (x !in 0 until cols || y !in 0 until rows) return
        val color = meta[GhosttyTerminal.META_CURSOR_COLOR].takeIf { it != 0 } ?: defaultFg
        val left = x * cellW
        val top = y * cellH
        val right = left + cellW
        val bottom = top + cellH
        val thin = max(1f, density * 1.25f)
        fillPaint.color = color
        val blinkedOff = meta[GhosttyTerminal.META_CURSOR_BLINK] != 0 && paneFocused && !blinkOn
        if (!paneFocused || meta[GhosttyTerminal.META_CURSOR_STYLE] == 3) {
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = thin
            canvas.drawRect(left + thin / 2, top + thin / 2, right - thin / 2, bottom - thin / 2, fillPaint)
            fillPaint.style = Paint.Style.FILL
            return
        }
        if (blinkedOff) return
        when (meta[GhosttyTerminal.META_CURSOR_STYLE]) {
            0 -> canvas.drawRect(left, top, left + thin * 1.5f, bottom, fillPaint)
            2 -> canvas.drawRect(left, bottom - thin * 1.5f, right, bottom, fillPaint)
            else -> {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                val i = (y * cols + x) * 4
                val cp = cells[i]
                if (cp != 0 && !(cp > 0 && BoxDrawing.handles(cp))) {
                    val n = appendCell(cp)
                    setTextStyle(cells[i + 3] and FLAG_FAINT.inv(), defaultBg)
                    canvas.drawText(chars, 0, n, left, top + baseline, textPaint)
                }
            }
        }
    }

    private fun drawHandles(canvas: Canvas, s: Float) {
        if (!selecting) return
        fillPaint.color = meta[GhosttyTerminal.META_FG]
        val r = HANDLE_RADIUS_DP * density / s
        val (sx, sy, ex, ey) = ordered()
        canvas.drawCircle(sx * cellW, sy * cellH, r, fillPaint)
        canvas.drawCircle((ex + 1) * cellW, (ey + 1) * cellH, r, fillPaint)
    }

    private fun drawScrollbar(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now > scrollbarVisibleUntil) return
        val total = meta[GhosttyTerminal.META_SCROLL_TOTAL]
        val len = meta[GhosttyTerminal.META_SCROLL_LEN]
        if (total <= len || total == 0) return
        val offset = meta[GhosttyTerminal.META_SCROLL_OFFSET]
        val h = height.toFloat()
        val barH = max(h * len / total, 24 * density)
        val top = (h - barH) * offset / max(1, total - len)
        val w = 3 * density
        fillPaint.color = (meta[GhosttyTerminal.META_FG] and 0xFFFFFF) or (0x80 shl 24)
        rect.set(width - w - 2 * density, top, width - 2 * density, top + barH)
        canvas.drawRoundRect(rect, w, w, fillPaint)
        postInvalidateDelayed(scrollbarVisibleUntil - now + 16)
    }

    private fun snap(v: Float) = Math.round(v).toFloat()

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

    private fun sendPlainKey(key: RexKey) {
        val h = host ?: return
        session?.terminal?.scrollToBottom()
        val sticky = h.consumeStickyMods()
        h.sendKey(if (sticky == 0) key else key.copy(mods = RexKey.modsString(sticky), text = ""))
    }

    private fun typed(text: String) {
        val h = host ?: return
        session?.terminal?.scrollToBottom()
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
        session?.terminal?.scrollToBottom()
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

    private fun cellX(px: Float): Int {
        val s = scale(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS])
        return floor((px - padding) / (cellW * s)).toInt().coerceIn(0, max(0, meta[GhosttyTerminal.META_COLS] - 1))
    }

    private fun cellY(py: Float): Int {
        val s = scale(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS])
        return floor((py - padding) / (cellH * s)).toInt().coerceIn(0, max(0, meta[GhosttyTerminal.META_ROWS] - 1))
    }

    private val appOwnsScroll get() = meta[GhosttyTerminal.META_MOUSE] != 0 || meta[GhosttyTerminal.META_ALT] != 0

    private fun scrollByPixels(dy: Float, x: Float, y: Float) {
        val s = scale(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS])
        scrollRemainder += dy / (cellH * s)
        val lines = scrollRemainder.toInt()
        if (lines == 0) return
        scrollRemainder -= lines
        if (appOwnsScroll) {
            host?.sendWheel(lines.toFloat(), cellX(x), cellY(y))
        } else {
            session?.terminal?.scroll(lines)
            scrollbarVisibleUntil = SystemClock.uptimeMillis() + 800
            invalidate()
        }
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
            host?.onTapped()
            if (meta[GhosttyTerminal.META_MOUSE] != 0) {
                val col = cellX(e.x)
                val row = cellY(e.y)
                host?.sendMouse("press", "left", col, row)
                host?.sendMouse("release", "left", col, row)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling || selecting) return true
            // Dragging up means moving towards newer lines, so the sign carries straight through.
            scrollByPixels(distanceY, e2.x, e2.y)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaling || selecting || appOwnsScroll) return true
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
    })

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val y = scroller.currY
            scrollByPixels((lastFlingY - y).toFloat(), width / 2f, height / 2f)
            lastFlingY = y
            postOnAnimation(this)
        }
    }

    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (fontSizeSp * detector.scaleFactor).coerceIn(MIN_FONT, MAX_FONT)
            if (abs(next - fontSizeSp) >= 0.25f) {
                fontSizeSp = next
                return true
            }
            return false
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            host?.onFontSizeChanged(fontSizeSp)
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
        scaleDetector.onTouchEvent(event)
        if (selecting && handleSelectionDrag(event)) return true
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scrollRemainder = 0f
            draggingHandle = 0
            if (selecting) updateActionMode()
        }
        return true
    }

    private fun handleRealMouse(event: MotionEvent): Boolean {
        val col = cellX(event.x)
        val row = cellY(event.y)
        val button = when {
            event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) -> "right"
            event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) -> "middle"
            else -> "left"
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> host?.sendMouse("press", button, col, row)
            MotionEvent.ACTION_MOVE -> host?.sendMouse("motion", button, col, row)
            MotionEvent.ACTION_UP -> host?.sendMouse("release", "left", col, row)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (interactive && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val lines = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3
            scrollByPixels(lines * cellH, event.x, event.y)
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

    private fun startSelection(x: Int, y: Int) {
        val terminal = session?.terminal ?: return
        selStartX = x
        selStartY = y
        selEndX = x
        selEndY = y
        terminal.selectRange(x, y, x, y)
        selecting = true
        // Grow to the word under the finger when there is one; otherwise a single cell is selected.
        if (terminal.selectWord(x, y)) expandToWord(x, y)
        draggingHandle = 2
        invalidate()
        updateActionMode()
    }

    /** Finds the selected span on this row from the frame the engine just produced. */
    private fun expandToWord(x: Int, y: Int) {
        val terminal = session?.terminal ?: return
        synchronized(terminal.lock) {
            terminal.render(true)
            val cols = terminal.meta[GhosttyTerminal.META_COLS]
            if (y >= terminal.meta[GhosttyTerminal.META_ROWS]) return
            var left = x
            var right = x
            val all = terminal.cells
            fun selected(c: Int) = all[(y * cols + c) * 4 + 3] and FLAG_SELECTED != 0
            while (left > 0 && selected(left - 1)) left--
            while (right < cols - 1 && selected(right + 1)) right++
            selStartX = left
            selEndX = right
        }
    }

    private fun handleSelectionDrag(event: MotionEvent): Boolean {
        val s = scale(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS])
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (sx, sy, ex, ey) = ordered()
                val reach = HANDLE_TOUCH_DP * density
                val startPx = padding + sx * cellW * s
                val startPy = padding + sy * cellH * s
                val endPx = padding + (ex + 1) * cellW * s
                val endPy = padding + (ey + 1) * cellH * s
                val nearStart = abs(event.x - startPx) < reach && abs(event.y - startPy) < reach
                val nearEnd = abs(event.x - endPx) < reach && abs(event.y - endPy) < reach
                draggingHandle = when {
                    nearEnd -> 2
                    nearStart -> 1
                    else -> 0
                }
                if (draggingHandle != 0) {
                    // Keep the opposite end fixed while this one follows the finger.
                    selStartX = sx; selStartY = sy; selEndX = ex; selEndY = ey
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle == 0) return false
                val x = cellX(event.x)
                val y = cellY(event.y)
                if (draggingHandle == 1) {
                    selStartX = x
                    selStartY = y
                } else {
                    selEndX = x
                    selEndY = y
                }
                session?.terminal?.selectRange(selStartX, selStartY, selEndX, selEndY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = draggingHandle
                draggingHandle = 0
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
                menu.add(0, ID_OPEN, 2, "Open link").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    ID_COPY -> selectedText()?.let { host?.copyText(it) }
                    ID_PASTE -> host?.clipboardText()?.let { host?.paste(it) }
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
                val s = scale(meta[GhosttyTerminal.META_COLS], meta[GhosttyTerminal.META_ROWS])
                val (sx, sy, ex, ey) = ordered()
                outRect.set(
                    (sx * cellW * s).toInt(),
                    (sy * cellH * s).toInt(),
                    ((ex + 1) * cellW * s).toInt(),
                    ((ey + 1) * cellH * s).toInt(),
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
    }

    companion object {
        const val MIN_FONT = 6f
        const val MAX_FONT = 32f
        private const val BLINK_MS = 530L
        private const val HANDLE_RADIUS_DP = 6f
        private const val HANDLE_TOUCH_DP = 28f
        private const val FLING_RANGE = 200_000
        private const val ID_COPY = 1
        private const val ID_PASTE = 2
        private const val ID_OPEN = 3
    }
}
