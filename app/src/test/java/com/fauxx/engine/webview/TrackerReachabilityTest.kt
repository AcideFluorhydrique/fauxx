package com.fauxx.engine.webview

import com.fauxx.engine.webview.TrackerReachabilityState.BLOCKED_CONNECTION
import com.fauxx.engine.webview.TrackerReachabilityState.BLOCKED_DNS
import com.fauxx.engine.webview.TrackerReachabilityState.NOT_ENOUGH_DATA
import com.fauxx.engine.webview.TrackerReachabilityState.OK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rolling window, minimums and hysteresis behind the blocked-trackers line (#341). */
class TrackerReachabilityTest {

    /** The production thresholds: 200-request window, 50 requests from 5 visits, 60% / 30%. */
    private val reach = TrackerReachability()

    private fun visit(attempted: Int, dns: Int = 0, connection: Int = 0, mainFrameFailed: Boolean = false) =
        reach.recordVisit(TrackerVisitCounts(attempted, dns, connection, mainFrameFailed))

    @Test
    fun `starts with not enough data`() {
        assertEquals(NOT_ENOUGH_DATA, reach.state.value)
    }

    @Test
    fun `needs enough tracker requests before judging`() {
        repeat(5) { visit(attempted = 9, dns = 9) }
        assertEquals("45 requests is below the 50 minimum", NOT_ENOUGH_DATA, reach.state.value)
        visit(attempted = 5, dns = 5)
        assertEquals(BLOCKED_DNS, reach.state.value)
    }

    @Test
    fun `needs enough distinct visits before judging`() {
        repeat(4) { visit(attempted = 40, dns = 40) }
        assertEquals("160 requests but only 4 visits", NOT_ENOUGH_DATA, reach.state.value)
        visit(attempted = 1, dns = 1)
        assertEquals(BLOCKED_DNS, reach.state.value)
    }

    @Test
    fun `one heavy visit can neither decide alone nor empty the window of visits`() {
        repeat(5) { visit(attempted = 20) }
        assertEquals(OK, reach.state.value)
        // 500 blocked requests get at most 200 / 5 = 40 slots: 40 of 140 is under the flag.
        visit(attempted = 500, connection = 500)
        assertEquals(OK, reach.state.value)

        val flagged = TrackerReachability()
        repeat(10) { flagged.recordVisit(TrackerVisitCounts(attempted = 20, dnsBlocked = 20)) }
        assertEquals(BLOCKED_DNS, flagged.state.value)
        // Heavy clean visits replace the window, but at 40 slots each it still holds five visits,
        // so the verdict moves on to OK instead of falling back to NOT_ENOUGH_DATA.
        repeat(5) { flagged.recordVisit(TrackerVisitCounts(attempted = 1_000)) }
        assertEquals(OK, flagged.state.value)
    }

    @Test
    fun `reset forgets the samples and the flag`() {
        repeat(10) { visit(attempted = 20, dns = 20) }
        assertEquals(BLOCKED_DNS, reach.state.value)
        reach.reset()
        assertEquals(NOT_ENOUGH_DATA, reach.state.value)
        // 50% after the reset: the old flag must not carry over through hysteresis.
        repeat(5) { visit(attempted = 20, dns = 10) }
        assertEquals(OK, reach.state.value)
    }

    @Test
    fun `visits without trackers or whose page failed add nothing`() {
        repeat(10) { visit(attempted = 0) }
        repeat(10) { visit(attempted = 20, dns = 20, mainFrameFailed = true) }
        assertEquals(NOT_ENOUGH_DATA, reach.state.value)
    }

    @Test
    fun `mostly reachable trackers read as OK`() {
        repeat(5) { visit(attempted = 20, dns = 5) }
        assertEquals(OK, reach.state.value)
    }

    @Test
    fun `flags at sixty percent blocked, not below`() {
        // 59 blocked of 100.
        repeat(4) { visit(attempted = 20, connection = 12) }
        visit(attempted = 20, connection = 11)
        assertEquals(OK, reach.state.value)

        // 60 of 100.
        val atThreshold = TrackerReachability()
        repeat(5) { atThreshold.recordVisit(TrackerVisitCounts(attempted = 20, connectionBlocked = 12)) }
        assertEquals(BLOCKED_CONNECTION, atThreshold.state.value)
    }

    @Test
    fun `hysteresis keeps the flag until the share drops below thirty percent`() {
        repeat(10) { visit(attempted = 20, dns = 20) }
        assertEquals("200 of 200 blocked", BLOCKED_DNS, reach.state.value)

        // Fill the window with 50% blocked visits: below 60% but not below 30%, so the flag holds.
        repeat(10) { visit(attempted = 20, dns = 10) }
        assertEquals(BLOCKED_DNS, reach.state.value)

        // 25% blocked clears it.
        repeat(10) { visit(attempted = 20, dns = 5) }
        assertEquals(OK, reach.state.value)

        // And from OK, 50% is not enough to flag again.
        repeat(10) { visit(attempted = 20, dns = 10) }
        assertEquals(OK, reach.state.value)
    }

    @Test
    fun `the kind follows whichever block is the majority`() {
        repeat(5) { visit(attempted = 20, dns = 11, connection = 9) }
        assertEquals(BLOCKED_DNS, reach.state.value)
        repeat(10) { visit(attempted = 20, dns = 9, connection = 11) }
        assertEquals(BLOCKED_CONNECTION, reach.state.value)
        repeat(10) { visit(attempted = 20, dns = 10, connection = 10) }
        assertEquals("a tie is not a DNS majority", BLOCKED_CONNECTION, reach.state.value)
    }

    @Test
    fun `old visits roll out of the window`() {
        repeat(10) { visit(attempted = 20) }
        assertEquals(OK, reach.state.value)
        repeat(10) { visit(attempted = 20, connection = 20) }
        assertEquals("the reachable visits have rolled out", BLOCKED_CONNECTION, reach.state.value)
    }

    @Test
    fun `thresholds and window are configurable`() {
        val small = TrackerReachability(windowSize = 4, minAttempts = 2, minVisits = 2, flagAt = 0.5, clearBelow = 0.25)
        small.recordVisit(TrackerVisitCounts(attempted = 1, dnsBlocked = 1))
        assertEquals(NOT_ENOUGH_DATA, small.state.value)
        small.recordVisit(TrackerVisitCounts(attempted = 1))
        assertEquals("1 of 2 is 50%", BLOCKED_DNS, small.state.value)
        small.recordVisit(TrackerVisitCounts(attempted = 2))
        assertEquals("1 of 4 is not below 25%", BLOCKED_DNS, small.state.value)
        small.recordVisit(TrackerVisitCounts(attempted = 1))
        assertEquals("the blocked one rolled out", OK, small.state.value)
    }

    @Test
    fun `inconsistent counts are clamped rather than overflowing the window`() {
        repeat(5) { visit(attempted = 10, dns = 50, connection = 50) }
        assertEquals(BLOCKED_DNS, reach.state.value)
    }

    @Test
    fun `blocked is true only for the blocked states`() {
        assertTrue(BLOCKED_DNS.blocked)
        assertTrue(BLOCKED_CONNECTION.blocked)
        assertFalse(OK.blocked)
        assertFalse(NOT_ENOUGH_DATA.blocked)
    }
}
