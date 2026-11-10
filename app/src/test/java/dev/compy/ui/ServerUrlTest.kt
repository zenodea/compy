package dev.compy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test fun addsHttpsToABareHostName() {
        assertEquals("https://rex.tail1234.ts.net", normalizeServerUrl("rex.tail1234.ts.net/", false).getOrThrow())
    }

    @Test fun refusesPlainHttpInReleaseBuilds() {
        assertTrue(normalizeServerUrl("http://192.168.1.2:8080", false).isFailure)
    }

    @Test fun allowsPlainHttpInTestBuilds() {
        assertEquals("http://10.0.2.2:18765", normalizeServerUrl("http://10.0.2.2:18765", true).getOrThrow())
    }

    @Test fun refusesOtherSchemes() {
        assertTrue(normalizeServerUrl("unix:///tmp/rex.sock", true).isFailure)
    }

    @Test fun refusesASchemeWithNoHost() {
        assertTrue(normalizeServerUrl("https://", false).isFailure)
        assertTrue(normalizeServerUrl("  ", false).isFailure)
    }

    @Test fun lowersTheScheme() {
        assertEquals("https://Rex.ts.net", normalizeServerUrl("HTTPS://Rex.ts.net", false).getOrThrow())
    }

    @Test fun namesServersFromTheirHost() {
        assertEquals("rex", defaultLabel("https://rex.tail1234.ts.net"))
        assertEquals("10.0.2.2", defaultLabel("http://10.0.2.2:18765"))
    }
}
