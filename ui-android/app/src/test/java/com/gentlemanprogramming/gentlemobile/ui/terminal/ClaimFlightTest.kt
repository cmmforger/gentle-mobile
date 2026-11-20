package com.gentlemanprogramming.gentlemobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The claim single-flight (T26 rule 5). Activation and resize are two wire
 * calls made from effect firings — keyboard open, keyboard close — that can
 * overlap. The rule: the wire order is the decision order, and a claim
 * confirmed after a newer one was decided can never overwrite its
 * bookkeeping.
 *
 * Pure gates, unit-tested without a UI runtime (ClaimFlightTest).
 */
class ClaimFlightTest {

    // --- decision order --------------------------------------------------------

    @Test
    fun `decisions are sequenced in the order they are decided`() {
        val flight = ClaimFlight()
        val first = flight.decide()
        val second = flight.decide()
        assertTrue("sequences increase monotonically", second > first)
    }

    @Test
    fun `the newest decision applies and an older one does not`() {
        val flight = ClaimFlight()
        val first = flight.decide()
        val second = flight.decide()
        assertTrue("the newest claim may apply its bookkeeping", flight.apply(second))
        assertFalse(
            "a claim confirmed after a newer decision can never overwrite it",
            flight.apply(first),
        )
    }

    @Test
    fun `a claim whose confirmation is applied first still applies`() {
        val flight = ClaimFlight()
        val first = flight.decide()
        val second = flight.decide()
        assertTrue(flight.apply(first))
        assertTrue("the newest decision supersedes whatever landed before", flight.apply(second))
        assertFalse(flight.apply(first))
    }

    // --- wire order is decision order ------------------------------------------

    @Test
    fun `claims submitted to one worker run in submission order`() {
        // The single worker is what serializes the two calls of one claim
        // (activation, resize) and the overlapping firings: whatever runs on
        // it is written to the wire in the order the UI decided.
        val flight = ClaimFlight()
        val worker = Executors.newSingleThreadExecutor()
        val order = StringBuilder()
        for (n in 1..3) {
            val seq = flight.decide()
            worker.execute {
                synchronized(order) { order.append(seq) }
            }
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("123", order.toString())
    }
}
