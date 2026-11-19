package com.gentlemanprogramming.gentlemobile.ui

import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.notifications.TrackedAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the agent row's relative last-used time comes from (S24-8): the
 * host's snapshot carries **no** timestamp, so the app times what it can
 * observe — the agent's own status changes and the prompts this phone sends
 * it. Pure and clock-injected: unit-tested without Android.
 */
class AgentActivityTest {

    private fun agent(
        paneId: String = "p1",
        status: AgentStatusDto = AgentStatusDto.WORKING,
    ) = TrackedAgent(paneId, "pi", "/work/erp-web", status)

    @Test
    fun `the first sighting is the baseline`() {
        val activity = AgentActivity()
        activity.onSnapshot(listOf(agent()), nowMs = 1_000)
        assertEquals(1_000L, activity.lastUsed("p1"))
    }

    @Test
    fun `a status change marks the agent as used`() {
        val activity = AgentActivity()
        activity.onSnapshot(listOf(agent(status = AgentStatusDto.IDLE)), nowMs = 1_000)
        activity.onSnapshot(listOf(agent(status = AgentStatusDto.WORKING)), nowMs = 5_000)
        assertEquals(5_000L, activity.lastUsed("p1"))
    }

    @Test
    fun `a status that stays put keeps the earlier time`() {
        val activity = AgentActivity()
        activity.onSnapshot(listOf(agent(status = AgentStatusDto.IDLE)), nowMs = 1_000)
        activity.onSnapshot(listOf(agent(status = AgentStatusDto.IDLE)), nowMs = 9_000)
        assertEquals(1_000L, activity.lastUsed("p1"))
    }

    @Test
    fun `a prompt sent from this phone is activity`() {
        val activity = AgentActivity()
        activity.onSnapshot(listOf(agent(status = AgentStatusDto.IDLE)), nowMs = 1_000)
        activity.onPrompted("p1", nowMs = 7_000)
        assertEquals(7_000L, activity.lastUsed("p1"))
    }

    @Test
    fun `an unknown pane has no time and the row omits it`() {
        assertNull(AgentActivity().lastUsed("p1"))
    }

    @Test
    fun `an agent that vanished loses its record`() {
        val activity = AgentActivity()
        activity.onSnapshot(listOf(agent()), nowMs = 1_000)
        activity.onSnapshot(emptyList(), nowMs = 2_000)
        assertNull(activity.lastUsed("p1"))
    }

    @Test
    fun `a new connection re-primes the baseline`() {
        val activity = AgentActivity()
        activity.onSnapshot(listOf(agent()), nowMs = 1_000)
        activity.reset()
        assertNull(activity.lastUsed("p1"))
        activity.onSnapshot(listOf(agent()), nowMs = 9_000)
        assertEquals(9_000L, activity.lastUsed("p1"))
    }

    @Test
    fun `agents are tracked per pane`() {
        val activity = AgentActivity()
        activity.onSnapshot(
            listOf(
                agent(paneId = "p1", status = AgentStatusDto.IDLE),
                agent(paneId = "p2", status = AgentStatusDto.WORKING),
            ),
            nowMs = 1_000,
        )
        activity.onSnapshot(
            listOf(
                agent(paneId = "p1", status = AgentStatusDto.IDLE),
                agent(paneId = "p2", status = AgentStatusDto.IDLE),
            ),
            nowMs = 4_000,
        )
        assertEquals(1_000L, activity.lastUsed("p1"))
        assertEquals(4_000L, activity.lastUsed("p2"))
    }
}
