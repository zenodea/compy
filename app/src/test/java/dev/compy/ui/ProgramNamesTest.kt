package dev.compy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramNamesTest {
    @Test fun statusAppComesFirst() {
        assertEquals(listOf("claude", "node"), ProgramNames.candidates("Claude", "node"))
    }

    @Test fun scriptBeatsItsInterpreter() {
        assertEquals(listOf("claude", "node"), ProgramNames.candidates(null, "node/claude.js"))
    }

    @Test fun loginShellDashIsIgnored() {
        assertEquals(listOf("zsh"), ProgramNames.candidates(null, "-zsh"))
    }

    @Test fun aliasesResolve() {
        assertEquals(listOf("neovim"), ProgramNames.candidates(null, "nvim"))
        assertEquals("nodedotjs", ProgramNames.slug("node"))
    }

    @Test fun nothingKnownIsEmpty() {
        assertEquals(emptyList<String>(), ProgramNames.candidates(null, null))
    }
}
