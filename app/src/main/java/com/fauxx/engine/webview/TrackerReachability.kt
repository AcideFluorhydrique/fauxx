package com.fauxx.engine.webview

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Whether Fauxx's requests to trackers are getting through, judged over recent visits (#341). */
enum class TrackerReachabilityState {
    /** Too few tracker requests, or too few visits, to judge yet. */
    NOT_ENOUGH_DATA,

    /** Most tracker requests get through. */
    OK,

    /** Most tracker requests are blocked, mostly because their names do not resolve. */
    BLOCKED_DNS,

    /** Most tracker requests are blocked, mostly at the connection. */
    BLOCKED_CONNECTION;

    val blocked: Boolean get() = this == BLOCKED_DNS || this == BLOCKED_CONNECTION
}

/**
 * Notices when something on the phone is silently discarding Fauxx's effect (#341).
 *
 * The decoy signal travels in the requests a page makes to ad networks and analytics companies.
 * Tracker blockers (DuckDuckGo App Tracking Protection, AdGuard, Rethink, Pi-hole, NextDNS...)
 * stop exactly those, while the pages themselves still load, so the action log shows successes.
 * In #306 one such blocker had stopped 1,445 of Fauxx's tracker requests before anyone noticed.
 *
 * [PhantomWebViewPool] hands over each visit's [TrackerVisitCounts] when the WebView is released.
 * This keeps the outcomes of the last [windowSize] tracker requests in memory (nothing is
 * persisted; a process restart starts over) and judges only once the window holds at least
 * [minAttempts] of them from at least [minVisits] different visits, so one odd page cannot decide
 * it. The verdict has hysteresis so the dashboard line does not flicker: it flags when the blocked
 * share reaches [flagAt] and clears only when it falls below [clearBelow]. Some trackers fail on
 * their own, which is why neither threshold is near zero.
 *
 * Thread-safe; visits arrive from whichever thread releases a WebView.
 */
@Singleton
class TrackerReachability @VisibleForTesting internal constructor(
    private val windowSize: Int,
    private val minAttempts: Int,
    private val minVisits: Int,
    private val flagAt: Double,
    private val clearBelow: Double,
) {
    @Inject
    constructor() : this(
        windowSize = WINDOW_SIZE,
        minAttempts = MIN_ATTEMPTS,
        minVisits = MIN_VISITS,
        flagAt = FLAG_AT,
        clearBelow = CLEAR_BELOW,
    )

    init {
        require(windowSize > 0 && minAttempts <= windowSize) { "minAttempts must fit in the window" }
        require(clearBelow <= flagAt) { "clearBelow must not exceed flagAt" }
    }

    private val lock = Any()

    private val maxPerVisit = maxOf(1, windowSize / maxOf(1, minVisits))

    // Ring buffer of the last windowSize attempts: the outcome and the visit it came from.
    private val outcomes = ByteArray(windowSize)
    private val visitIds = LongArray(windowSize)
    private var size = 0
    private var next = 0
    private var nextVisitId = 0L

    // Kept apart from the published state so hysteresis survives a spell of NOT_ENOUGH_DATA.
    private var flagged = false

    private val _state = MutableStateFlow(TrackerReachabilityState.NOT_ENOUGH_DATA)
    val state: StateFlow<TrackerReachabilityState> = _state.asStateFlow()

    /**
     * Add one visit's tracker requests to the window. A visit with no tracker requests, or whose
     * page failed to load at all (offline, or the site itself blocked), adds nothing.
     */
    fun recordVisit(counts: TrackerVisitCounts) {
        if (counts.attempted <= 0 || counts.mainFrameFailed) return
        synchronized(lock) {
            // A visit gets at most windowSize / minVisits slots, scaled down keeping its blocked
            // shares, so one heavy page can neither decide the verdict alone nor push the other
            // visits out of the window.
            val total = counts.attempted
            val n = minOf(total, maxPerVisit)
            val dnsInVisit = counts.dnsBlocked.coerceIn(0, total)
            val connectionInVisit = counts.connectionBlocked.coerceIn(0, total - dnsInVisit)
            val dns = scale(dnsInVisit, n, total)
            val connection = scale(connectionInVisit, n, total).coerceAtMost(n - dns)
            val visitId = nextVisitId++
            repeat(n) { i ->
                push(
                    when {
                        i < dns -> OUTCOME_DNS
                        i < dns + connection -> OUTCOME_CONNECTION
                        else -> OUTCOME_OK
                    },
                    visitId,
                )
            }
            evaluate()
        }
    }

    /**
     * Forget every sample and the hysteresis flag. Called when the engine starts and when custom
     * DNS is switched on or off or recovers: samples taken over a different DNS path say nothing
     * about the current one, and keeping them would show a stale line (a Pi-hole user who turns
     * on Custom DNS would otherwise be told for another hundred-odd requests that it isn't working).
     */
    fun reset() {
        synchronized(lock) {
            size = 0
            next = 0
            flagged = false
            if (_state.value != TrackerReachabilityState.NOT_ENOUGH_DATA) {
                Timber.i("Tracker reachability %s -> %s: reset", _state.value, TrackerReachabilityState.NOT_ENOUGH_DATA)
                _state.value = TrackerReachabilityState.NOT_ENOUGH_DATA
            }
        }
    }

    private fun scale(count: Int, n: Int, total: Int): Int =
        if (n == total) count else (count.toDouble() * n / total).roundToInt()

    private fun push(outcome: Byte, visitId: Long) {
        outcomes[next] = outcome
        visitIds[next] = visitId
        next = (next + 1) % windowSize
        if (size < windowSize) size++
    }

    /** Caller holds [lock]. */
    private fun evaluate() {
        var dns = 0
        var connection = 0
        val visits = HashSet<Long>()
        for (i in 0 until size) {
            when (outcomes[i]) {
                OUTCOME_DNS -> dns++
                OUTCOME_CONNECTION -> connection++
            }
            visits.add(visitIds[i])
        }
        val blocked = dns + connection
        val newState = if (size < minAttempts || visits.size < minVisits) {
            TrackerReachabilityState.NOT_ENOUGH_DATA
        } else {
            val share = blocked.toDouble() / size
            flagged = if (flagged) share >= clearBelow else share >= flagAt
            when {
                !flagged -> TrackerReachabilityState.OK
                dns * 2 > blocked -> TrackerReachabilityState.BLOCKED_DNS
                else -> TrackerReachabilityState.BLOCKED_CONNECTION
            }
        }
        val previous = _state.value
        if (newState != previous) {
            // Counts only, never hosts or URLs.
            Timber.i(
                "Tracker reachability %s -> %s: %d of %d tracker requests blocked (%d DNS, %d connection) over %d visits",
                previous, newState, blocked, size, dns, connection, visits.size,
            )
            _state.value = newState
        }
    }

    private companion object {
        const val WINDOW_SIZE = 200
        const val MIN_ATTEMPTS = 50
        const val MIN_VISITS = 5
        const val FLAG_AT = 0.60
        const val CLEAR_BELOW = 0.30

        const val OUTCOME_OK: Byte = 0
        const val OUTCOME_DNS: Byte = 1
        const val OUTCOME_CONNECTION: Byte = 2
    }
}
