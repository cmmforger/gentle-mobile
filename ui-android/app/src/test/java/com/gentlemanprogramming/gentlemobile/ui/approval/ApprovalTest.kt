package com.gentlemanprogramming.gentlemobile.ui.approval

import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval board's pure rules (T31, S32b), one test per rule. Each test
 * asserts the observable decision — which answers are offered, which are
 * marked risky, which tap sends, which asks again — never the internal
 * storage. The Compose view code is deliberately untested.
 */
class ApprovalTest {

    // --- The parser: a dialog-shaped pane reads as question + command + options ----

    @Test
    fun `a dialog-shaped pane parses into question, exact command and options`() {
        val pane = """
            │May I run the migration on staging?
            │Command: make migrate ENV=staging
            │  1. Yes
            │  2. Yes, don't ask again
            │❯ 3. No
        """.trimMargin("│")
        val parsed = ApprovalParser.parse(pane) as ApprovalParser.Parse.Question
        assertEquals("May I run the migration on staging?", parsed.question)
        assertEquals("make migrate ENV=staging", parsed.command)
        assertEquals(3, parsed.options.size)
        assertEquals(ApprovalParser.Option(1, "Yes", false), parsed.options[0])
        assertEquals(ApprovalParser.Option(2, "Yes, don't ask again", false), parsed.options[1])
        assertEquals(ApprovalParser.Option(3, "No", true), parsed.options[2])
    }

    @Test
    fun `the command is the pane's own text, never prettified`() {
        val pane = """
            │Push this branch?
            │Command: git push origin DECK-412 --force-with-lease
            │  1. Yes
            │  2. No
        """.trimMargin("│")
        val parsed = ApprovalParser.parse(pane) as ApprovalParser.Parse.Question
        assertEquals("git push origin DECK-412 --force-with-lease", parsed.command)
        // The pane's own numbering stands, whatever it is.
        assertEquals(listOf(1, 2), parsed.options.map { it.number })
    }

    @Test
    fun `a dialog without a command line still parses, showing only the question`() {
        val pane = """
            │Push bump-deps to origin?
            │  1. Yes
            │  2. No
        """.trimMargin("│")
        val parsed = ApprovalParser.parse(pane) as ApprovalParser.Parse.Question
        assertEquals("Push bump-deps to origin?", parsed.question)
        assertEquals(null, parsed.command)
    }

    // --- Honesty where parsing fails: no fake approval block -----------------------

    @Test
    fun `a pane without numbered options is named NO_OPTIONS and keeps its lines`() {
        val pane = "Party trick output\nnoise line\nmore noise"
        val parsed = ApprovalParser.parse(pane) as ApprovalParser.Parse.Honest
        assertEquals(ApprovalParser.Reason.NO_OPTIONS, parsed.reason)
        assertEquals(listOf("Party trick output", "noise line", "more noise"), parsed.lines)
    }

    @Test
    fun `a blank pane is EMPTY, never a question`() {
        val parsed = ApprovalParser.parse("   \n   ") as ApprovalParser.Parse.Honest
        assertEquals(ApprovalParser.Reason.EMPTY, parsed.reason)
    }

    @Test
    fun `options without a question line say so`() {
        val pane = """
            │  1. Yes
            │  2. No
        """.trimMargin("│")
        val parsed = ApprovalParser.parse(pane) as ApprovalParser.Parse.Honest
        assertEquals(ApprovalParser.Reason.NO_QUESTION, parsed.reason)
    }

    // --- The board: groups in the order needs you, working, done, idle -------------

    private fun agent(name: String, status: AgentStatusDto, paneId: String = name) = AgentDto(
        name = name,
        kind = null,
        status = status,
        cwd = "/work/$name",
        focused = false,
        paneId = paneId,
        workspaceId = "ws1",
    )

    @Test
    fun `groups come out as needs you, done, working, idle — each with its agents only`() {
        val agents = listOf(
            agent("a", AgentStatusDto.WORKING),
            agent("b", AgentStatusDto.BLOCKED),
            agent("c", AgentStatusDto.IDLE, paneId = "c"),
            agent("d", AgentStatusDto.DONE),
            agent("e", AgentStatusDto.BLOCKED),
        )
        val groups = ApprovalBoardModel.groups(agents, waitingMs = emptyMap())
        assertEquals(
            listOf(
                ApprovalBoardModel.Group.NEEDS_YOU,
                ApprovalBoardModel.Group.DONE,
                ApprovalBoardModel.Group.WORKING,
                ApprovalBoardModel.Group.IDLE,
            ),
            groups.map { it.group },
        )
        assertEquals(listOf("b", "e"), groups.first().agents.map { it.name })
    }

    @Test
    fun `an unknown-status agent keeps its own honest group, last`() {
        val agents = listOf(
            agent("a", AgentStatusDto.UNKNOWN),
            agent("b", AgentStatusDto.BLOCKED),
        )
        val groups = ApprovalBoardModel.groups(agents, waitingMs = emptyMap())
        assertEquals(listOf(ApprovalBoardModel.Group.NEEDS_YOU, ApprovalBoardModel.Group.UNKNOWN), groups.map { it.group })
    }

    @Test
    fun `empty groups do not render`() {
        val agents = listOf(agent("a", AgentStatusDto.IDLE))
        val groups = ApprovalBoardModel.groups(agents, waitingMs = emptyMap())
        assertEquals(listOf(ApprovalBoardModel.Group.IDLE), groups.map { it.group })
    }

    // --- The waiting order: longest waiting first inside needs you -----------------

    @Test
    fun `the waiting agents sort longest waiting first`() {
        val agents = listOf(
            agent("late", AgentStatusDto.BLOCKED),
            agent("early", AgentStatusDto.BLOCKED),
            agent("justNow", AgentStatusDto.BLOCKED),
        )
        val groups = ApprovalBoardModel.groups(agents, waitingMs = mapOf("late" to 400_000L, "early" to 120_000L))
        assertEquals(listOf("late", "early", "justNow"), groups.first().agents.map { it.name })
    }

    @Test
    fun `the wait tracker counts from the first blocked observation`() {
        val tracker = ApprovalWaitTracker()
        tracker.onStatuses(mapOf("p1" to AgentStatusDto.BLOCKED), nowMs = 0)
        tracker.onStatuses(mapOf("p1" to AgentStatusDto.BLOCKED), nowMs = 30_000)
        // Still the first observation: the wait keeps growing, it never restarts.
        assertEquals(0L, tracker.blockedSince("p1"))
    }

    @Test
    fun `an agent that stops waiting loses its wait`() {
        val tracker = ApprovalWaitTracker()
        tracker.onStatuses(mapOf("p1" to AgentStatusDto.BLOCKED), nowMs = 0)
        tracker.onStatuses(mapOf("p1" to AgentStatusDto.WORKING), nowMs = 5_000)
        tracker.onStatuses(mapOf("p1" to AgentStatusDto.BLOCKED), nowMs = 9_000)
        // Blocked again is a new wait, counted from now — not the old one.
        assertEquals(9_000L, tracker.blockedSince("p1"))
    }

    // --- The one needs-you count --------------------------------------------------

    @Test
    fun `the needs-you count is the number of blocked agents`() {
        val agents = listOf(
            agent("a", AgentStatusDto.BLOCKED),
            agent("b", AgentStatusDto.BLOCKED),
            agent("c", AgentStatusDto.WORKING),
            agent("d", AgentStatusDto.DONE),
        )
        assertEquals(2, ApprovalBoardModel.needsYouCount(agents))
        assertEquals(0, ApprovalBoardModel.needsYouCount(emptyList()))
    }

    // --- The risk rules: which answer is held ---------------------------------------

    @Test
    fun `a risky command makes the affirming answers risky and the refusal safe`() {
        val command = "make migrate ENV=staging"
        assertTrue(ApprovalRisk.isRisky("Yes", command, "May I run the migration on staging?"))
        assertTrue(ApprovalRisk.isRisky("Yes, don't ask again", command, "May I run the migration on staging?"))
        assertFalse(ApprovalRisk.isRisky("No", command, "May I run the migration on staging?"))
    }

    @Test
    fun `a push question makes Yes risky even without a command line`() {
        assertTrue(ApprovalRisk.isRisky("Yes", null, "Push bump-deps to origin?"))
        assertFalse(ApprovalRisk.isRisky("No", null, "Push bump-deps to origin?"))
    }

    @Test
    fun `don't ask again is risky on its own, widening every future blast radius`() {
        assertTrue(ApprovalRisk.isRisky("Yes, don't ask again", "use light theme", "use light theme?"))
        assertTrue(ApprovalRisk.isRisky("Always allow", "use light theme", "use light theme?"))
    }

    @Test
    fun `a safe question keeps every answer unheld`() {
        val question = "Shall I use the light theme next run?"
        assertFalse(ApprovalRisk.isRisky("Yes", "settings theme light", question))
        assertFalse(ApprovalRisk.isRisky("No", "settings theme light", question))
    }

    @Test
    fun `deleting is risky`() {
        val command = "rm -rf build/"
        assertTrue(ApprovalRisk.impactRisky(command, "Remove the build directory?"))
        assertTrue(ApprovalRisk.isRisky("Yes", command, "Remove the build directory?"))
        assertFalse(ApprovalRisk.isRisky("No", command, "Remove the build directory?"))
    }

    // --- The stale-question rule: re-read before sending -----------------------------

    private val shown = ApprovalParser.Parse.Question(
        question = "May I run the migration on staging?",
        command = "make migrate ENV=staging",
        options = listOf(
            ApprovalParser.Option(1, "Yes", false),
            ApprovalParser.Option(2, "No", true),
        ),
    )

    @Test
    fun `the same question still sends`() {
        val fresh = ApprovalParser.parse(
            """
            │May I run the migration on staging?
            │Command: make migrate ENV=staging
            │  1. Yes
            │❯ 2. No
            """.trimMargin("│"),
        )
        assertEquals(ApprovalGate.Verdict.Send, ApprovalGate.decide(shown, fresh))
    }

    @Test
    fun `a changed question does not send — the fresh one is shown instead`() {
        val fresh = ApprovalParser.parse(
            """
            │May I run the migration on production?
            │Command: make migrate ENV=production
            │  1. Yes
            │❯ 2. No
            """.trimMargin("│"),
        )
        assertEquals(ApprovalGate.Verdict.Stale, ApprovalGate.decide(shown, fresh))
    }

    @Test
    fun `changed options are a different question — never answered from the old view`() {
        val fresh = ApprovalParser.parse(
            """
            │May I run the migration on staging?
            │Command: make migrate ENV=staging
            │  1. Yes
            │  2. Yes, and also seed
            │❯ 3. No
            """.trimMargin("│"),
        )
        assertEquals(ApprovalGate.Verdict.Stale, ApprovalGate.decide(shown, fresh))
    }

    @Test
    fun `a pane that stopped being a question is never answered — the honest view is shown`() {
        val fresh = ApprovalParser.parse("done running the migration\nall good")
        assertEquals(ApprovalGate.Verdict.Stale, ApprovalGate.decide(shown, fresh))
    }

    @Test
    fun `a failed re-read never sends`() {
        assertEquals(ApprovalGate.Verdict.Stale, ApprovalGate.decide(shown, fresh = null))
    }

    // --- The answer keys the pane's own numbering -----------------------------------

    @Test
    fun `the answer rides the pane's own number`() {
        val pane = """
            │Push bump-deps to origin?
            │  1. Yes
            │  2. No
        """.trimMargin("│")
        val parsed = ApprovalParser.parse(pane) as ApprovalParser.Parse.Question
        // The number the row renders is the number the pane expects back,
        // not a re-numbered list made by the app.
        assertEquals(2, parsed.options[1].number)
    }
}
