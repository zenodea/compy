package dev.compy.rex

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the command the app copies in a real shell, with a home folder and a rex of its own. */
class HostSetupTest {
    private val lua = File("../rex/compy.lua").readBytes()
    private val command = hostSetupCommand(lua)

    private class Host {
        val home: File = Files.createTempDirectory("compy-host").toFile()
        val config = File(home, ".config/rex")
        private val bin = File(home, "bin").apply { mkdirs() }
        private val calls = File(home, "rex-calls")

        init {
            File(bin, "rex").apply {
                writeText("#!/bin/sh\necho \"\$@\" >> '${calls.path}'\n")
                setExecutable(true)
            }
        }

        fun run(shell: String, command: String) {
            val process = ProcessBuilder(shell, "-c", command).redirectErrorStream(true)
            process.environment().apply {
                put("HOME", home.path)
                put("PATH", "${bin.path}:/usr/bin:/bin")
            }
            val started = process.start()
            val output = started.inputStream.bufferedReader().readText()
            assertTrue("the command didn't finish", started.waitFor(30, TimeUnit.SECONDS))
            assertEquals("$shell said: $output", 0, started.exitValue())
        }

        fun init(): String = File(config, "init.lua").readText()
        fun rexCalls(): List<String> = if (calls.exists()) calls.readLines() else emptyList()
        fun delete() = home.deleteRecursively()
    }

    private fun host(test: Host.() -> Unit) {
        assumeTrue(File("/bin/sh").canExecute())
        val host = Host()
        try { host.test() } finally { host.delete() }
    }

    @Test fun setsUpAHostThatHasNoConfig() = host {
        run("/bin/sh", command)
        assertArrayEquals(lua, File(config, "compy.lua").readBytes())
        assertEquals("\n$COMPY_LUA_LINE\n", init())
        assertEquals(listOf("config reload"), rexCalls())
    }

    @Test fun keepsTheConfigAndAddsTheLineOnce() = host {
        config.mkdirs()
        // No newline at the end, as an editor may leave it.
        File(config, "init.lua").writeText("rex.bind(\"ctrl+a>x\", \"pane.close\")")
        run("/bin/sh", command)
        run("/bin/sh", command)
        assertEquals("rex.bind(\"ctrl+a>x\", \"pane.close\")\n$COMPY_LUA_LINE\n", init())
        assertEquals(listOf("config reload", "config reload"), rexCalls())
    }

    @Test fun updatesCompyLuaWithoutTouchingAnOlderLine() = host {
        config.mkdirs()
        val before = "dofile(os.getenv(\"HOME\") .. \"/.config/rex/compy.lua\")\n"
        File(config, "init.lua").writeText(before)
        File(config, "compy.lua").writeText("-- an older copy")
        run("/bin/sh", command)
        assertArrayEquals(lua, File(config, "compy.lua").readBytes())
        assertEquals(before, init())
    }

    @Test fun runsInTheShellsPeopleUse() {
        for (shell in listOf("/bin/bash", "/bin/zsh", "/usr/bin/fish", "/opt/homebrew/bin/fish")) {
            if (!File(shell).canExecute()) continue
            host {
                run(shell, command)
                assertArrayEquals(shell, lua, File(config, "compy.lua").readBytes())
                assertEquals(shell, "\n$COMPY_LUA_LINE\n", init())
            }
        }
    }

    @Test fun fitsInLinesATerminalTakes() {
        assertTrue(command.lines().all { it.length <= 200 })
    }
}
