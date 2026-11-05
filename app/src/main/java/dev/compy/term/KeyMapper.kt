package dev.compy.term

import android.view.KeyEvent
import dev.compy.rex.RexKey

/** Translates Android hardware key events into Rex's key names. */
object KeyMapper {
    private val NAMED: Map<Int, String> = buildMap {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, "key_${'a' + i}")
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, "digit_$i")
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, "f${i + 1}")
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, "numpad_$i")
        put(KeyEvent.KEYCODE_ENTER, "enter")
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, "numpad_enter")
        put(KeyEvent.KEYCODE_TAB, "tab")
        put(KeyEvent.KEYCODE_SPACE, "space")
        put(KeyEvent.KEYCODE_DEL, "backspace")
        put(KeyEvent.KEYCODE_FORWARD_DEL, "delete")
        put(KeyEvent.KEYCODE_ESCAPE, "escape")
        put(KeyEvent.KEYCODE_DPAD_UP, "arrow_up")
        put(KeyEvent.KEYCODE_DPAD_DOWN, "arrow_down")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "arrow_left")
        put(KeyEvent.KEYCODE_DPAD_RIGHT, "arrow_right")
        put(KeyEvent.KEYCODE_MOVE_HOME, "home")
        put(KeyEvent.KEYCODE_MOVE_END, "end")
        put(KeyEvent.KEYCODE_PAGE_UP, "page_up")
        put(KeyEvent.KEYCODE_PAGE_DOWN, "page_down")
        put(KeyEvent.KEYCODE_INSERT, "insert")
        put(KeyEvent.KEYCODE_GRAVE, "backquote")
        put(KeyEvent.KEYCODE_MINUS, "minus")
        put(KeyEvent.KEYCODE_EQUALS, "equal")
        put(KeyEvent.KEYCODE_LEFT_BRACKET, "bracket_left")
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, "bracket_right")
        put(KeyEvent.KEYCODE_BACKSLASH, "backslash")
        put(KeyEvent.KEYCODE_SEMICOLON, "semicolon")
        put(KeyEvent.KEYCODE_APOSTROPHE, "quote")
        put(KeyEvent.KEYCODE_COMMA, "comma")
        put(KeyEvent.KEYCODE_PERIOD, "period")
        put(KeyEvent.KEYCODE_SLASH, "slash")
        put(KeyEvent.KEYCODE_NUMPAD_ADD, "numpad_add")
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "numpad_subtract")
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "numpad_multiply")
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "numpad_divide")
        put(KeyEvent.KEYCODE_NUMPAD_DOT, "numpad_decimal")
        put(KeyEvent.KEYCODE_SHIFT_LEFT, "shift_left")
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, "shift_right")
        put(KeyEvent.KEYCODE_CTRL_LEFT, "control_left")
        put(KeyEvent.KEYCODE_CTRL_RIGHT, "control_right")
        put(KeyEvent.KEYCODE_ALT_LEFT, "alt_left")
        put(KeyEvent.KEYCODE_ALT_RIGHT, "alt_right")
        put(KeyEvent.KEYCODE_META_LEFT, "meta_left")
        put(KeyEvent.KEYCODE_META_RIGHT, "meta_right")
    }

    /** Null for keys Rex has no name for, such as volume, which Android should keep. */
    fun fromEvent(event: KeyEvent, extraMods: Int, action: String): RexKey? {
        val name = NAMED[event.keyCode] ?: return null
        var mods = extraMods
        if (event.isShiftPressed) mods = mods or RexKey.SHIFT
        if (event.isCtrlPressed) mods = mods or RexKey.CTRL
        if (event.isAltPressed) mods = mods or RexKey.ALT
        if (event.isMetaPressed) mods = mods or RexKey.SUPER
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        val unshifted = event.getUnicodeChar(0).takeIf { it > 0 && it and KeyCharacterMapCombining == 0 } ?: 0
        val typesText = mods and (RexKey.CTRL or RexKey.ALT or RexKey.SUPER) == 0
        val text = when {
            !typesText -> ""
            event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            event.keyCode == KeyEvent.KEYCODE_TAB -> "\t"
            event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_ESCAPE -> ""
            unicode > 0 && unicode and KeyCharacterMapCombining == 0 -> String(Character.toChars(unicode))
            else -> ""
        }
        return RexKey(
            key = name,
            mods = RexKey.modsString(mods),
            consumedMods = if (text.isNotEmpty() && mods and RexKey.SHIFT != 0) "shift" else "",
            text = if (action == "release") "" else text,
            unshifted = unshifted,
            action = action,
        )
    }

    private const val KeyCharacterMapCombining = Int.MIN_VALUE
}
