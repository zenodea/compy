package dev.compy.rex

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * The line in a host's init.lua that loads compy.lua. It is a pcall because a config whose dofile
 * fails loses every action it defines, which matters when one init.lua is shared between machines
 * and only some of them have compy.lua.
 */
const val COMPY_LUA_LINE = """pcall(dofile, os.getenv("HOME") .. "/.config/rex/compy.lua")"""

/**
 * A shell command that sets a host up for agent chat. It writes [lua] to ~/.config/rex/compy.lua,
 * adds [COMPY_LUA_LINE] to init.lua unless that already names compy.lua, and reloads Rex's config.
 * Running it again replaces compy.lua and changes nothing else.
 *
 * It uses only what sh, bash, zsh and fish share. The file travels as gzipped base64 in short
 * lines, so pasting it into a terminal can't change it.
 */
fun hostSetupCommand(lua: ByteArray): String {
    val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(lua) } }.toByteArray()
    val payload = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(zipped)
    return listOf(
        "mkdir -p ~/.config/rex",
        "echo '$payload' | base64 -d | gunzip > ~/.config/rex/compy.lua",
        // The blank line first keeps the new line off a last line that has no newline of its own.
        """grep -q 'compy\.lua' ~/.config/rex/init.lua 2>/dev/null || printf '\n%s\n' '$COMPY_LUA_LINE' >> ~/.config/rex/init.lua""",
        "rex config reload",
    ).joinToString("\n")
}
