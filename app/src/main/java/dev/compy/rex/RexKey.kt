package dev.compy.rex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A key as Rex's client.key and block.event expect it. */
data class RexKey(
    val key: String,
    val mods: String = "",
    val consumedMods: String = "",
    val text: String = "",
    val unshifted: Int = 0,
    val action: String = "press",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("mods", mods)
        put("consumed_mods", consumedMods)
        put("text", text)
        put("unshifted_codepoint", unshifted)
        put("composing", false)
        put("action", action)
    }

    companion object {
        const val SHIFT = 1
        const val CTRL = 2
        const val ALT = 4
        const val SUPER = 8

        fun modsString(mods: Int): String = buildList {
            if (mods and SHIFT != 0) add("shift")
            if (mods and CTRL != 0) add("ctrl")
            if (mods and ALT != 0) add("alt")
            if (mods and SUPER != 0) add("super")
        }.joinToString("+")

        /**
         * Builds the key for a typed character on a US layout. Characters with no key, such as
         * emoji, come back with an unidentified key and only their text.
         */
        fun forChar(c: Int, mods: Int = 0): RexKey {
            val base = BASE_KEYS[c]
            val shifted = SHIFTED_KEYS[c]
            val text = String(Character.toChars(c))
            return when {
                c in 'a'.code..'z'.code -> key("key_${c.toChar()}", c, mods, text, false)
                c in 'A'.code..'Z'.code -> key("key_${c.toChar().lowercaseChar()}", c + 32, mods or SHIFT, text, true)
                c in '0'.code..'9'.code -> key("digit_${c.toChar()}", c, mods, text, false)
                base != null -> key(base, c, mods, text, false)
                shifted != null -> key(shifted.first, shifted.second.code, mods or SHIFT, text, true)
                else -> RexKey("unidentified", modsString(mods), "", if (mods and (CTRL or ALT or SUPER) != 0) "" else text, 0)
            }
        }

        private fun key(name: String, unshifted: Int, mods: Int, text: String, shift: Boolean): RexKey {
            // Control and Alt combinations type nothing themselves; the server encodes them.
            val typed = if (mods and (CTRL or ALT or SUPER) != 0) "" else text
            return RexKey(name, modsString(mods), if (shift) "shift" else "", typed, unshifted)
        }

        private val BASE_KEYS = mapOf(
            ' '.code to "space", '-'.code to "minus", '='.code to "equal", '['.code to "bracket_left",
            ']'.code to "bracket_right", '\\'.code to "backslash", ';'.code to "semicolon", '\''.code to "quote",
            '`'.code to "backquote", ','.code to "comma", '.'.code to "period", '/'.code to "slash",
        )

        private val SHIFTED_KEYS = mapOf(
            '!'.code to ("digit_1" to '1'), '@'.code to ("digit_2" to '2'), '#'.code to ("digit_3" to '3'),
            '$'.code to ("digit_4" to '4'), '%'.code to ("digit_5" to '5'), '^'.code to ("digit_6" to '6'),
            '&'.code to ("digit_7" to '7'), '*'.code to ("digit_8" to '8'), '('.code to ("digit_9" to '9'),
            ')'.code to ("digit_0" to '0'), '_'.code to ("minus" to '-'), '+'.code to ("equal" to '='),
            '{'.code to ("bracket_left" to '['), '}'.code to ("bracket_right" to ']'), '|'.code to ("backslash" to '\\'),
            ':'.code to ("semicolon" to ';'), '"'.code to ("quote" to '\''), '~'.code to ("backquote" to '`'),
            '<'.code to ("comma" to ','), '>'.code to ("period" to '.'), '?'.code to ("slash" to '/'),
        )

        val ENTER = RexKey("enter", text = "\r")
        val TAB = RexKey("tab", text = "\t")
        val BACKSPACE = RexKey("backspace")
        val ESCAPE = RexKey("escape")
        val UP = RexKey("arrow_up")
        val DOWN = RexKey("arrow_down")
        val LEFT = RexKey("arrow_left")
        val RIGHT = RexKey("arrow_right")
    }
}
