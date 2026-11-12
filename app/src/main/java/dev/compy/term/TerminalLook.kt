package dev.compy.term

/** How the person set terminals to look and behave. Only this device sees these. */
data class TerminalLook(
    /** Row height as a multiple of the font's own. */
    val lineHeight: Float = 1f,
    /** Extra room between letters, in dp. Negative brings them closer. */
    val letterSpacing: Float = 0f,
    /** `program` follows what runs in the terminal; otherwise `block`, `bar` or `underline`. */
    val cursorStyle: String = "program",
    /** Lets the cursor blink when the program asks for a blinking one. */
    val cursorBlink: Boolean = true,
    /** Thickness of a bar or underline cursor, in dp. */
    val cursorWidth: Float = 2f,
    /** Joins pairs such as `=>` and `!=` into one symbol, in fonts that have them. */
    val ligatures: Boolean = false,
    /** Moves text colors that are too close to their background until they can be read. */
    val improveContrast: Boolean = true,
    /** A tap on the line being typed moves the cursor there. */
    val tapMovesCursor: Boolean = true,
)
