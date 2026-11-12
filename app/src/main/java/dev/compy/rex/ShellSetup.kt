package dev.compy.rex

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * A shell command that makes the shell it is pasted into mark its prompts and each command's
 * output, which "jump to prompt" and "copy command output" need. It runs [script], Compy's
 * prompt-marks.sh, which writes one small file for zsh, bash or fish and adds a line to that
 * shell's startup file.
 *
 * Like the chat setup command it uses only what sh, bash, zsh and fish share, and carries the
 * script as gzipped base64 in short lines, so pasting it into a terminal can't change it.
 */
fun shellSetupCommand(script: ByteArray): String {
    val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(script) } }.toByteArray()
    val payload = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(zipped)
    return "echo '$payload' | base64 -d | gunzip | sh"
}
