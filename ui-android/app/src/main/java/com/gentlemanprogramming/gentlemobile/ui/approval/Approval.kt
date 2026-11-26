package com.gentlemanprogramming.gentlemobile.ui.approval

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto

/**
 * The approval board's pure rules (T31, S32b). A waiting agent's pane text
 * reads as a question with numbered options and an exact command — the
 * card the board renders as content, never as a dialog. Everything here is
 * decided without a UI runtime; the composable in MainActivity only paints
 * these decisions.
 *
 * The honesty rule the board lives by (S32b-5): a pane that cannot be read
 * as a question with options is never faked into one. It renders as
 * [ApprovalParser.Parse.Honest] — the pane's own lines and the keys to
 * press — because a wrong approval is worse than a manual one.
 */
object ApprovalParser {

    /** One numbered option exactly as the pane shows it: the pane's own
     *  number (never renumbered by the app), the label text after it, and
     *  whether the pane marks it as the current selection (`❯` / `>`). */
    data class Option(val number: Int, val label: String, val selected: Boolean)

    sealed interface Parse {
        /** A readable approval: the question line, the exact command line
         *  (the pane's own text after its `Command:` marker, `null` when the
         *  pane shows none), and the numbered options. */
        data class Question(
            val question: String,
            val command: String?,
            val options: List<Option>,
        ) : Parse

        /** Not readable as an approval, and said so: which case it is, plus
         *  the pane's lines for the human to answer by hand. */
        data class Honest(val lines: List<String>, val reason: Reason) : Parse
    }

    enum class Reason {
        /** No visible lines to read. */
        EMPTY,

        /** Lines, but no numbered option block. */
        NO_OPTIONS,

        /** An option block, but no question line before it. */
        NO_QUESTION,
    }

    private val OPTION = Regex("^(\\u276F|>)?\\s*([0-9]+)[.)]\\s*(\\S.*?)\\s*$")
    private val COMMAND = Regex("^\\s*(?:command|cmd)\\s*[:]\\s*(.+)$", RegexOption.IGNORE_CASE)

    /**
     * The dialog lives at the bottom of the pane, so the option block is
     * the last run of consecutive option lines; the question is the last
     * non-blank line before it, and the command is the `Command:` line
     * before the options. Nothing above the dialog is invented into it.
     */
    fun parse(text: String): Parse {
        val lines = text.split('\n').map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        if (lines.none { it.isNotBlank() }) return Parse.Honest(emptyList(), Reason.EMPTY)

        val matched = lines.mapIndexedNotNull { index, line ->
            OPTION.matchEntire(line)?.let { match ->
                index to Option(
                    number = match.groupValues[2].toInt(),
                    label = match.groupValues[3].trim(),
                    selected = match.groupValues[1].isNotEmpty(),
                )
            }
        }
        // Runs of consecutive option lines; the live dialog is the last one
        // with at least two options — never a stray numbered list further up.
        val runs = mutableListOf<MutableList<Pair<Int, Option>>>()
        for (entry in matched) {
            val last = runs.lastOrNull()
            if (last != null && entry.first == last.last().first + 1) {
                last.add(entry)
            } else {
                runs.add(mutableListOf(entry))
            }
        }
        val block = runs.lastOrNull()?.takeIf { it.size >= 2 }
            ?: return Parse.Honest(lines, Reason.NO_OPTIONS)
        val blockStart = block.first().first
        val before = lines.subList(0, blockStart)
        val command = before.lastOrNull { COMMAND.matchEntire(it) != null }
            ?.let { COMMAND.matchEntire(it)!!.groupValues[1].trim() }
        val commandIndex = before.indexOfLast { COMMAND.matchEntire(it) != null }
        val question = before
            .mapIndexedNotNull { index, line -> if (index == commandIndex) null else line }
            .lastOrNull { it.isNotBlank() }
            ?.trimStart(' ', '\u276F', '>', '?')?.trim()
            ?: return Parse.Honest(lines, Reason.NO_QUESTION)
        return Parse.Question(question, command, block.map { it.second })
    }
}

/**
 * Which answers must be held (tap twice) instead of sent on the first tap
 * (S32b-3): anything that widens the blast radius — a push, a delete, a
 * destructive or publishing command, "don't ask again".
 */
object ApprovalRisk {

    /** The wording that permanently widens the radius: answering once is
     *  not the risk, never being asked again is. */
    private val ASK_NEVER = listOf(
        "don't ask again", "do not ask again", "dont ask again",
        "never ask", "always allow", "always permit", "remember this choice",
    )

    /** The refusals: they can only shrink the blast radius, so they never
     *  carry the warning glyph. */
    private val DECLINE = listOf(
        "no", "n", "cancel", "deny", "reject", "stop", "abort", "skip", "dismiss",
    )

    /** The words that make an action one whose approval is risky: pushes,
     *  deletions, destruction, publishing, and things that rewrite state
     *  behind an agent (migrations, force flags, cleaning). */
    private val RISKY = Regex(
        "(\\bpush\\b|\\bpublish\\b|\\bdeploy\\b|\\brm\\b|\\brmdir\\b|\\bdelete\\b|\\bdrop\\b|" +
            "\\btruncate\\b|\\bforce\\b|--force|\\bformat\\b|\\breset --hard\\b|\\bclean\\b|" +
            "\\bwipe\\b|\\boverwrite\\b|\\bmigrate\\b|\\bmigration\\b|\\breinstall\\b|\\bshutdown\\b)",
        RegexOption.IGNORE_CASE,
    )

    /** Whether the action being approved widens the blast radius: the
     *  command when the pane showed one, the question when it did not. */
    fun impactRisky(command: String?, question: String): Boolean {
        val text = (command?.takeIf { it.isNotBlank() } ?: question).lowercase()
        return RISKY.containsMatchIn(text)
    }

    /**
     * Whether the option with this label must be held: "don't ask again"
     * wording first (risky regardless), then the refusals (never marked),
     * then everything else — an approving or ambiguous answer inherits the
     * action's own risk. The safe option gets no mark.
     */
    fun isRisky(label: String, command: String?, question: String): Boolean {
        val text = label.trim().lowercase().trimEnd('.', '!')
        if (ASK_NEVER.any { text.contains(it) }) return true
        if (text in DECLINE || text.startsWith("no ") || text.startsWith("no,")) return false
        return impactRisky(command, question)
    }
}

/**
 * The stale-question rule (S32b-4): on tap, the pane is read again and the
 * answer is sent only when the question is still the one that was shown.
 * The human must never answer something they did not see.
 */
object ApprovalGate {

    sealed interface Verdict {
        /** The freshly read pane still asks the shown question: send. */
        data object Send : Verdict

        /** The pane changed (or failed to re-read): send nothing — the
         *  fresh view replaces the shown one. */
        data object Stale : Verdict
    }

    fun decide(shown: ApprovalParser.Parse.Question, fresh: ApprovalParser.Parse?): Verdict =
        when (fresh) {
            null -> Verdict.Stale
            is ApprovalParser.Parse.Honest -> Verdict.Stale
            is ApprovalParser.Parse.Question -> if (same(shown, fresh)) Verdict.Send else Verdict.Stale
        }

    /** Same question and same options (numbers and labels): the answer the
     *  human saw is the answer the pane is still asking for. The pane's own
     *  selection mark may move without changing what is being asked. */
    private fun same(shown: ApprovalParser.Parse.Question, fresh: ApprovalParser.Parse.Question): Boolean =
        shown.question == fresh.question &&
            shown.command == fresh.command &&
            shown.options.map { it.number to it.label } == fresh.options.map { it.number to it.label }
}

/**
 * One pane's latest approval read: what the parser decided and when the
 * pane was read. The board never invents an older read as fresh.
 */
data class ApprovalRead(val parse: ApprovalParser.Parse, val readAtMs: Long)

/** An answer waiting for its confirmation tap (S32b-3): one pane, one
 *  option. The first tap on a risky answer only holds it. */
data class ApprovalHold(val paneId: String, val optionNumber: Int)

/**
 * The board's grouping (S32b-1): agents grouped by what they need, in the
 * order needs you, done, working, idle — the order the design plan fixed
 * (`odd/tasks/gentle-mobile.md`, Inbox), with the honest `unknown` group
 * keeping its own place last, never lying as idle. Only non-empty groups
 * render.
 */
object ApprovalBoardModel {

    enum class Group { NEEDS_YOU, WORKING, DONE, IDLE, UNKNOWN }

    data class Ordered(val group: Group, val agents: List<AgentDto>)

    private val ORDER = listOf(
        Group.NEEDS_YOU,
        Group.DONE,
        Group.WORKING,
        Group.IDLE,
        Group.UNKNOWN,
    )

    private fun groupOf(status: AgentStatusDto): Group = when (status) {
        AgentStatusDto.BLOCKED -> Group.NEEDS_YOU
        AgentStatusDto.WORKING -> Group.WORKING
        AgentStatusDto.DONE -> Group.DONE
        AgentStatusDto.IDLE -> Group.IDLE
        AgentStatusDto.UNKNOWN -> Group.UNKNOWN
    }

    /**
     * The board's groups in their fixed order. Inside needs you, the
     * longest-waiting agent leads ([waitingMs] carries how long each pane
     * has been blocked; a pane not in the map has just been observed, zero).
     * Ties keep the snapshot's order.
     */
    fun groups(agents: List<AgentDto>, waitingMs: Map<String, Long>): List<Ordered> =
        ORDER.map { group ->
            val members = agents.filter { groupOf(it.status) == group }
            if (members.isEmpty()) {
                null
            } else if (group == Group.NEEDS_YOU) {
                Ordered(
                    group,
                    members.sortedByDescending { waitingMs[it.paneId] ?: 0L },
                )
            } else {
                Ordered(group, members)
            }
        }.filterNotNull()

    /** The one needs-you count: the number of blocked agents on this board. */
    fun needsYouCount(agents: List<AgentDto>): Int =
        agents.count { it.status == AgentStatusDto.BLOCKED }
}

/**
 * How long each pane has been blocked, from this connection's own
 * observations. Nothing is invented: the wait counts from the first
 * blocked status the phone saw, resets when the status leaves BLOCKED, and
 * a pane the phone has not observed yet has no wait at all.
 */
class ApprovalWaitTracker {

    private val since = mutableMapOf<String, Long>()

    /** Feed one snapshot's statuses. A pane that is blocked keeps its first
     *  observation; one that stopped waiting is dropped; a newly blocked
     *  pane starts counting from now. */
    fun onStatuses(statuses: Map<String, AgentStatusDto>, nowMs: Long) {
        for ((paneId, status) in statuses) {
            if (status == AgentStatusDto.BLOCKED) {
                since.putIfAbsent(paneId, nowMs)
            } else {
                since.remove(paneId)
            }
        }
    }

    /** The first blocked observation per pane, in epoch ms. */
    fun blockedSince(paneId: String): Long? = since[paneId]

    /** Every waited pane, for tests and sorting. */
    fun snapshot(): Map<String, Long> = since.toMap()

    /** A new connection observes nothing yet. */
    fun reset() = since.clear()
}
