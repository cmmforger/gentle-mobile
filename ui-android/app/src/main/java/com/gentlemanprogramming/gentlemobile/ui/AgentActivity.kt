package com.gentlemanprogramming.gentlemobile.ui

import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.notifications.TrackedAgent

/**
 * Where the agent row's relative last-used time comes from (S24-8): the
 * host's snapshot carries no timestamp, so the app times what it can
 * honestly observe — the agent's own status changes and the prompts this
 * phone sends. The first sighting of a connection is the baseline: the row
 * never invents an older history than this connection has seen. Pure and
 * clock-injected, like the alert tracker it mirrors.
 */
class AgentActivity {
    private val lastUsedMs = HashMap<String, Long>()
    private val statuses = HashMap<String, AgentStatusDto>()

    /** Feed one snapshot. A status change stamps `nowMs`; a status that
     *  stays put keeps the earlier stamp. An agent that vanished from the
     *  snapshot loses its record — if it comes back, it is a new baseline. */
    fun onSnapshot(agents: List<TrackedAgent>, nowMs: Long) {
        agents.forEach { agent ->
            val previous = statuses[agent.paneId]
            statuses[agent.paneId] = agent.status
            if (previous != agent.status) {
                lastUsedMs[agent.paneId] = nowMs
            }
        }
        lastUsedMs.keys.retainAll(agents.mapTo(HashSet()) { it.paneId })
        statuses.keys.retainAll(agents.mapTo(HashSet()) { it.paneId })
    }

    /** A prompt sent from this phone is activity too. */
    fun onPrompted(paneId: String, nowMs: Long) {
        if (lastUsedMs.containsKey(paneId)) {
            lastUsedMs[paneId] = nowMs
        }
    }

    /** The epoch ms of the last observed activity, or `null` when this
     *  connection has seen none for that pane (the row omits the time). */
    fun lastUsed(paneId: String): Long? = lastUsedMs[paneId]

    /** A new connection is a fresh baseline: nothing is carried over. */
    fun reset() {
        lastUsedMs.clear()
        statuses.clear()
    }
}
