package dev.compy.system

import dev.compy.rex.ProgramStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlertRulesTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private fun status(state: String, at: String? = "2026-11-05T11:59:00Z") = ProgramStatus(state, null, "claude", null, updatedAt = at)

    @Test fun onlyBlockedErrorAndDoneAlert() {
        assertEquals(AlertKind.NeedsYou, alertKind(status("blocked")))
        assertEquals(AlertKind.Failed, alertKind(status("error")))
        assertEquals(AlertKind.Finished, alertKind(status("done")))
        assertEquals(null, alertKind(status("working")))
        assertEquals(null, alertKind(status("idle")))
    }

    @Test fun somethingWaitingAlwaysAlerts() {
        assertTrue(shouldAlert(null, status("blocked", "2026-11-01T00:00:00Z"), now))
    }

    @Test fun finishingWhileWatchedAlerts() {
        assertTrue(shouldAlert(status("working"), status("done", "2026-11-01T00:00:00Z"), now))
    }

    @Test fun recentFinishFoundOnReconnectAlerts() {
        assertTrue(shouldAlert(null, status("done"), now))
    }

    @Test fun oldFinishFoundOnReconnectStaysQuiet() {
        assertFalse(shouldAlert(null, status("done", "2026-11-05T10:00:00Z"), now))
    }

    @Test fun workingNeverAlerts() {
        assertFalse(shouldAlert(null, status("working"), now))
    }
}
