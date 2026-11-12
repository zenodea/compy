package dev.compy.rex

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the command the app copies in real shells, each with a home folder of its own. */
class ShellSetupTest {
    private val command = shellSetupCommand(File("../rex/prompt-marks.sh").readBytes())

    private class Result(val status: Int, val output: String)

    private fun run(home: File, loginShell: String, with: String, command: String, vararg args: String): Result {
        val process = ProcessBuilder(with, *args, command).redirectErrorStream(true)
        process.environment().apply {
            put("HOME", home.path)
            put("SHELL", loginShell)
            put("PATH", "/usr/bin:/bin")
            remove("ZDOTDIR")
        }
        val started = process.start()
        val output = started.inputStream.bufferedReader().readText()
        assertTrue("the command didn't finish", started.waitFor(30, TimeUnit.SECONDS))
        return Result(started.exitValue(), output)
    }

    private fun home(test: (File) -> Unit) {
        assumeTrue(File("/bin/sh").canExecute())
        val home = Files.createTempDirectory("compy-shell").toFile()
        try { test(home) } finally { home.deleteRecursively() }
    }

    @Test fun setsUpZshAndAddsItsLineOnce() = home { home ->
        File(home, ".zshrc").writeText("export EDITOR=vi")
        for (shell in listOf("/bin/sh", "/bin/bash", "/bin/zsh")) {
            if (!File(shell).canExecute()) continue
            val result = run(home, "/bin/zsh", shell, command, "-c")
            assertEquals("$shell said: ${result.output}", 0, result.status)
        }
        val rc = File(home, ".zshrc").readLines()
        assertEquals("export EDITOR=vi", rc.first())
        assertEquals(1, rc.count { "compy/prompt-marks" in it })
        assertTrue(File(home, ".config/compy/prompt-marks.zsh").readText().contains("133;A"))
    }

    @Test fun setsUpBash() = home { home ->
        val result = run(home, "/bin/bash", "/bin/sh", command, "-c")
        assertEquals(result.output, 0, result.status)
        assertEquals(1, File(home, ".bashrc").readLines().count { "compy/prompt-marks" in it })
        assertTrue(File(home, ".config/compy/prompt-marks.bash").exists())
    }

    @Test fun setsUpFishWithoutTouchingAStartupFile() = home { home ->
        val result = run(home, "/usr/local/bin/fish", "/bin/sh", command, "-c")
        assertEquals(result.output, 0, result.status)
        assertTrue(File(home, ".config/fish/conf.d/compy-prompt-marks.fish").readText().contains("fish_preexec"))
        assertFalse(File(home, ".config/fish/config.fish").exists())
    }

    @Test fun leavesAnUnknownShellAlone() = home { home ->
        val result = run(home, "/bin/tcsh", "/bin/sh", command, "-c")
        assertEquals(1, result.status)
        assertTrue(result.output.contains("tcsh"))
        assertEquals(listOf(".config"), home.list()?.toList())
    }

    /** The files it writes must be ones their shells accept, since they run at every start. */
    @Test fun writesFilesTheirShellsCanRead() = home { home ->
        run(home, "/bin/zsh", "/bin/sh", command, "-c")
        run(home, "/bin/bash", "/bin/sh", command, "-c")
        if (File("/bin/zsh").canExecute()) {
            val zsh = run(home, "/bin/zsh", "/bin/zsh", File(home, ".config/compy/prompt-marks.zsh").path, "-n")
            assertEquals(zsh.output, 0, zsh.status)
            // An interactive zsh that loads it shows the marks in its prompt and runs both hooks.
            val shown = run(
                home, "/bin/zsh", "/bin/zsh",
                "source ~/.config/compy/prompt-marks.zsh; _compy_precmd; print -r -- \$PS1; print -l \$precmd_functions \$preexec_functions",
                "-f", "-i", "-c",
            )
            assertEquals(shown.output, 0, shown.status)
            assertTrue(shown.output, shown.output.contains("133;A") && shown.output.contains("133;B"))
            assertTrue(shown.output, shown.output.contains("_compy_precmd") && shown.output.contains("_compy_preexec"))
        }
        if (File("/bin/bash").canExecute()) {
            val bash = run(home, "/bin/bash", "/bin/bash", File(home, ".config/compy/prompt-marks.bash").path, "-n")
            assertEquals(bash.output, 0, bash.status)
        }
    }
}
