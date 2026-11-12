package dev.compy.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramNameTest {
    @Test
    fun aPlainProgramGoesByItsName() {
        assertEquals("zsh", programName("zsh", "-zsh", "/bin/zsh", null).substringBefore('/'))
        assertEquals("codex", programName("codex", "codex", "/Users/me/.local/bin/codex", null))
    }

    @Test
    fun aScriptIsNamedAfterItsInterpreter() {
        assertEquals("node/server.js", programName("node", "node", "/opt/homebrew/bin/node", "/work/server.js"))
    }

    @Test
    fun aProgramThatRenamedItselfIsKnownByThatName() {
        // Pi runs under node and sets its own title, which is all that says what it is.
        assertEquals("node/pi", programName("node", "pi", "/opt/homebrew/bin/node", null))
    }

    @Test
    fun aVersionNamedBinaryGoesByWhatItWasCalled() {
        assertEquals("claude", programName("2.1.319", "claude", "/Users/me/.local/bin/claude", null))
    }
}
