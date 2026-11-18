package com.gentlemanprogramming.gentlemobile.notifications

import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15 (S11): the alert brain, unit-tested as pure transitions.
 *
 * Rules under test:
 * * the first snapshot of a connection is the baseline — an agent already
 *   sitting in `needs you` when this phone connects never alerts;
 * * only a **transition into** `needs you` (or `done`) alerts; a status that
 *   stays put never does (debounce by transition, not by timer);
 * * an agent whose session is on screen is not alerted for;
 * * the notification identity is stable per agent pane, so a second alert
 *   for the same agent updates one notification instead of stacking two;
 * * the Settings row states the permission honestly, never silently.
 */
class AgentAlertsTest {

    private fun agent(
        paneId: String,
        name: String = "review-payments",
        cwd: String? = "/srv/work",
        status: AgentStatusDto,
    ) = TrackedAgent(paneId = paneId, name = name, cwd = cwd, status = status)

    @Test
    fun `an agent already in needs you on the first snapshot never alerts`() {
        val tracker = AgentAlertTracker()
        val alerts = tracker.onSnapshot(
            listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)),
            showingPaneId = null,
        )
        assertTrue(
            "the first snapshot is the baseline; no transition happened yet",
            alerts.isEmpty(),
        )
        // And the following snapshots do not replay it either: the status
        // never changed after the baseline.
        assertTrue(
            tracker.onSnapshot(
                listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)),
                showingPaneId = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `a transition into needs you alerts with the agent name and working dir`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        val alerts = tracker.onSnapshot(
            listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)),
            null,
        )
        assertEquals(1, alerts.size)
        val alert = alerts.single()
        assertEquals("w1:p1", alert.paneId)
        assertEquals("review-payments", alert.name)
        assertEquals("/srv/work", alert.cwd)
        assertEquals(AlertKind.NEEDS_YOU, alert.kind)
    }

    @Test
    fun `a transition into done alerts separately`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        val alerts = tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.DONE)), null)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.DONE, alerts.single().kind)
    }

    @Test
    fun `a status that stays put never re-alerts`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)), null)
        // Two more snapshots with the agent still in needs you: debounce —
        // one notification per transition, not one per snapshot.
        repeat(2) {
            assertTrue(
                tracker.onSnapshot(
                    listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)),
                    null,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `leaving and re-entering needs you alerts again on the same notification`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)), null)
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        val again = tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)), null)
        assertEquals(1, again.size)
        assertEquals(AlertKind.NEEDS_YOU, again.single().kind)
        // Same pane, same notification identity: the shade updates the one
        // notification instead of stacking a duplicate.
        assertEquals(
            "the second transition must update, not duplicate",
            agentNotificationId("w1:p1"),
            agentNotificationId("w1:p1"),
        )
    }

    @Test
    fun `different agents carry different notification identities`() {
        assertNotEquals(
            agentNotificationId("w1:p1"),
            agentNotificationId("w1:p2"),
        )
    }

    @Test
    fun `the agent on screen is never alerted for`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(
            listOf(
                agent("w1:p1", status = AgentStatusDto.WORKING),
                agent("w1:p2", status = AgentStatusDto.WORKING),
            ),
            null,
        )
        // w1:p2's session is open on screen: its transition is seen, recorded
        // — and not notified. The other agent still alerts.
        val alerts = tracker.onSnapshot(
            listOf(
                agent("w1:p1", status = AgentStatusDto.BLOCKED),
                agent("w1:p2", status = AgentStatusDto.BLOCKED),
            ),
            showingPaneId = "w1:p2",
        )
        assertEquals(1, alerts.size)
        assertEquals("w1:p1", alerts.single().paneId)
        // And the suppressed agent does not replay its transition later.
        assertTrue(
            tracker.onSnapshot(listOf(agent("w1:p2", status = AgentStatusDto.BLOCKED)), null)
                .isEmpty(),
        )
    }

    @Test
    fun `an agent that vanishes and comes back needs you again alerts`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        assertTrue(tracker.onSnapshot(emptyList(), null).isEmpty())
        val alerts = tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)), null)
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.NEEDS_YOU, alerts.single().kind)
    }

    @Test
    fun `a new connection re-primes the baseline`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        tracker.reset()
        // Fresh connection: the agent now sits in needs you before this
        // phone's first snapshot — baseline, not a transition.
        assertTrue(
            tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.BLOCKED)), null)
                .isEmpty(),
        )
    }

    @Test
    fun `unknown status never alerts`() {
        val tracker = AgentAlertTracker()
        tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.WORKING)), null)
        assertTrue(
            tracker.onSnapshot(listOf(agent("w1:p1", status = AgentStatusDto.UNKNOWN)), null)
                .isEmpty(),
        )
    }

    @Test
    fun `the settings row states the permission honestly`() {
        assertEquals("On", notificationsRowWord(true))
        assertEquals("Off", notificationsRowWord(false))
        assertEquals(
            "the app keeps working without it, and Settings says why",
            "Off — notifications were denied, so the phone stays quiet while an agent " +
                "needs you. Enable them in Android settings.",
            notificationsRowDetail(false),
        )
        // Below Android 13 there is no runtime permission to deny: no row may
        // claim a denial that never happened.
        assertEquals("On", notificationsRowWord(null))
        assertEquals(
            "This Android version does not ask — notifications are the system's to allow.",
            notificationsRowDetail(null),
        )
    }
}
