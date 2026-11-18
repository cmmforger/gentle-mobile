package com.gentlemanprogramming.gentlemobile.notifications

import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto

/**
 * T15 (S11), pure alert brain: which agent transitions deserve a
 * notification, decided from snapshots the app already receives — no extra
 * polling, no timer, no relay (S11: local notifications only).
 *
 * Rules, each unit-tested:
 *
 * * the **first snapshot of a connection is the baseline** — an agent
 *   already sitting in `needs you` when this phone connects was there
 *   before, so it never alerts;
 * * only a **transition into** `needs you` (Blocked) or `done` (Done)
 *   alerts, and only once per transition: statuses that stay put never
 *   re-alert (debounce by transition, not by repetition);
 * * an agent whose session is currently on screen is **not alerted for** —
 *   the transition is still recorded, so no replay happens later;
 * * a new connection re-primes the baseline ([reset]).
 */
data class TrackedAgent(
    val paneId: String,
    val name: String,
    val cwd: String?,
    val status: AgentStatusDto,
)

enum class AlertKind(val word: String) {
    NEEDS_YOU("needs you"),
    DONE("done"),
}

data class AgentAlert(
    val paneId: String,
    val name: String,
    val cwd: String?,
    val kind: AlertKind,
)

/** Stable notification identity per agent pane: notifying again for the
 *  same pane updates the one notification in the shade instead of stacking
 *  a duplicate. Distinct panes get distinct identities. */
fun agentNotificationId(paneId: String): Int = "gentle-agent-$paneId".hashCode()

class AgentAlertTracker {
    private var primed = false
    private val statuses = HashMap<String, AgentStatusDto>()

    /** Reset for a new connection: the next snapshot is a fresh baseline. */
    fun reset() {
        primed = false
        statuses.clear()
    }

    /** Feed one snapshot; returns the alerts to post now. `showingPaneId` is
     *  the pane whose session screen is currently displayed, or `null` when
     *  the app shows no session (background included). */
    fun onSnapshot(
        agents: List<TrackedAgent>,
        showingPaneId: String?,
    ): List<AgentAlert> {
        if (!primed) {
            primed = true
            agents.forEach { statuses[it.paneId] = it.status }
            return emptyList()
        }
        val alerts = mutableListOf<AgentAlert>()
        agents.forEach { agent ->
            val previous = statuses[agent.paneId]
            statuses[agent.paneId] = agent.status
            val kind = when (agent.status) {
                AgentStatusDto.BLOCKED ->
                    if (previous != AgentStatusDto.BLOCKED) AlertKind.NEEDS_YOU else null
                AgentStatusDto.DONE ->
                    if (previous != AgentStatusDto.DONE) AlertKind.DONE else null
                else -> null
            }
            if (kind != null && agent.paneId != showingPaneId) {
                alerts += AgentAlert(agent.paneId, agent.name, agent.cwd, kind)
            }
        }
        // An agent that vanished from the snapshot is gone for this tracker:
        // if it comes back in `needs you`, that is a transition again.
        statuses.keys.retainAll(agents.mapTo(HashSet()) { it.paneId })
        return alerts
    }
}

/**
 * Permission honesty (T15): the Settings row's word and its reason, pure so
 * the copy is exactly what was reviewed. `null` means this Android does not
 * ask (below 13): the row must never claim a denial that never happened.
 */
fun notificationsRowWord(granted: Boolean?): String = when (granted) {
    false -> "Off"
    else -> "On"
}

fun notificationsRowDetail(granted: Boolean?): String = when (granted) {
    true -> "The phone will tell you when an agent needs you."
    false ->
        "Off — notifications were denied, so the phone stays quiet while an agent " +
            "needs you. Enable them in Android settings."
    null -> "This Android version does not ask — notifications are the system's to allow."
}
