package com.fauxx.engine.webview

import androidx.annotation.VisibleForTesting
import com.fauxx.engine.EngineState
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.dns.NetworkChanges
import com.fauxx.network.dns.DnsHealth
import com.fauxx.util.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Whether Fauxx's requests to trackers are being blocked, all signals combined (#341). */
data class TrackerBlockVerdict(
    val blocked: Boolean,
    /** How they are blocked, when [blocked]: [TrackerBlockKind.DNS] if any DNS-level evidence. */
    val kind: TrackerBlockKind?,
    /** How Fauxx's traffic is routed now; also read while nothing is blocked (the DuckDuckGo hint). */
    val vpn: VpnState,
) {
    companion object {
        val CLEAR = TrackerBlockVerdict(blocked = false, kind = null, vpn = VpnState.NONE)
    }
}

/** The dashboard's one input for the blocked-trackers line (#341). */
interface TrackerBlockSource {
    val verdict: Flow<TrackerBlockVerdict>

    /**
     * The dashboard came back into view: re-read the VPN state, and ask for a probe if the last
     * result is missing or old. Off the main thread (the VPN read is a binder call).
     */
    suspend fun refresh() {}

    companion object {
        /** Never blocked; for tests and fakes. */
        val NONE: TrackerBlockSource = object : TrackerBlockSource {
            override val verdict: Flow<TrackerBlockVerdict> = flowOf(TrackerBlockVerdict.CLEAR)
        }
    }
}

/** The engine's hook into the tracker checks (#341): runs for the life of an engine session. */
fun interface TrackerWatch {
    /** Returns only when cancelled; the engine cancels it on stop. */
    suspend fun watch(engineState: StateFlow<EngineState>)

    companion object {
        val NONE = TrackerWatch { }
    }
}

/**
 * Owns both halves of #341 and combines them into one [verdict]: the passive count of failed
 * tracker requests ([TrackerReachability]) and the active DNS check ([TrackerDnsProbe]).
 *
 * Lifecycle ([watch], one per engine session, cancelled by the engine's stop):
 * - On start, forget the previous session's samples and probe result, so turning the engine off
 *   and on never resurrects an old verdict.
 * - Forget both again whenever the VPN state changes (on or off, or its DNS servers), and whenever
 *   custom DNS is switched on or off or changes between working and degraded: evidence gathered
 *   over another route says nothing about the current one.
 * - Probe [FIRST_PROBE_DELAY_MS] after the engine first runs, then whenever the last probe is
 *   [PROBE_INTERVAL_MS] old, [NETWORK_SETTLE_MS] after a network or DNS change, and when the
 *   dashboard resumes with a result missing or older than [RESUME_STALE_MS]; never within
 *   [MIN_PROBE_GAP_MS] of the last one. Time is measured on [Clock.elapsedRealtime], which keeps
 *   counting in deep sleep, in waits of at most [TICK_MS], so a long sleep cannot leave the result
 *   older than the schedule promises.
 * - Only while the engine is actually running ([EngineState.ACTIVE] or rate-limited), never while
 *   paused for the network, battery or quiet hours. Never in strict custom DNS mode (#333), where
 *   Fauxx must not use the device's resolver at all, nor while custom DNS is working, when Fauxx's
 *   own lookups bypass the system resolver and a probe would only send tracker names to the ISP
 *   for nothing.
 * - A BLOCKED probe is confirmed by a second one [CONFIRM_DELAY_MS] later before it is published,
 *   so one unlucky probe (a packet lost near the timeout, a stalled main looper delivering the
 *   answer late) cannot put a false line up for hours.
 * - A result is kept until a newer probe replaces it or the route changes; it does not expire by
 *   age, so a probe delayed by deep sleep does not make a real block flicker off.
 *
 * Combining (see [verdictFor]): blocked when the passive window is flagged, or the latest probe
 * says blocked and still applies (taken over the current VPN state, and custom DNS not working).
 */
@Singleton
class TrackerBlockMonitor @Inject constructor(
    private val reachability: TrackerReachability,
    private val probe: TrackerDnsProbe,
    private val customDns: CustomDns,
    private val networkChanges: NetworkChanges,
    private val vpn: VpnStatus,
    private val clock: Clock,
) : TrackerWatch, TrackerBlockSource {

    private val latestProbe = MutableStateFlow<TrackerProbeResult?>(null)

    /** The published probe result; for tests. */
    @VisibleForTesting
    internal val lastProbe: StateFlow<TrackerProbeResult?> get() = latestProbe
    private val vpnState = MutableStateFlow(VpnState.NONE)
    private val sessions = AtomicLong(0)

    // Requests for the running watch loop, and the signal that wakes it.
    private val resumeRequested = AtomicBoolean(false)
    @Volatile private var networkDueAt: Long? = null
    @Volatile private var signal: Channel<Unit>? = null

    override val verdict: Flow<TrackerBlockVerdict> =
        combine(reachability.state, latestProbe, customDns.health, vpnState) { passive, probe, health, route ->
            verdictFor(passive, probe, health, route)
        }.distinctUntilChanged()

    override suspend fun refresh() {
        updateVpnState()
        resumeRequested.set(true)
        signal?.trySend(Unit)
    }

    override suspend fun watch(engineState: StateFlow<EngineState>) {
        val session = sessions.incrementAndGet()
        val wake = Channel<Unit>(Channel.CONFLATED)
        signal = wake
        reachability.reset()
        latestProbe.value = null
        vpnState.value = vpn.current()
        networkDueAt = null
        resumeRequested.set(false)
        try {
            coroutineScope {
                launch { resetOnDnsPathChange(wake) }
                launch {
                    networkChanges.changes.collect {
                        updateVpnState()
                        networkDueAt = clock.elapsedRealtime() + NETWORK_SETTLE_MS
                        wake.trySend(Unit)
                    }
                }
                // Counted from when the engine first runs, by which time strict DNS has been applied.
                engineState.first { it in PROBING_STATES }
                delay(FIRST_PROBE_DELAY_MS)
                runSchedule(session, engineState, wake)
            }
        } finally {
            // A newer session may already be running; only the latest one owns the result.
            if (sessions.get() == session) {
                latestProbe.value = null
                signal = null
            }
        }
    }

    private suspend fun runSchedule(session: Long, engineState: StateFlow<EngineState>, wake: Channel<Unit>) {
        var lastProbeAt: Long? = null // last probe actually sent
        var lastCheckAt: Long? = null // last time a probe was due, sent or skipped
        while (true) {
            engineState.first { it in PROBING_STATES }
            val now = clock.elapsedRealtime()
            val sinceCheck = lastCheckAt?.let { now - it }
            val sinceProbe = lastProbeAt?.let { now - it }
            val networkDue = networkDueAt?.let { now >= it } == true
            val resumeDue = resumeRequested.get() &&
                (latestProbe.value == null || sinceProbe == null || sinceProbe >= RESUME_STALE_MS)
            val due = sinceCheck == null || sinceCheck >= PROBE_INTERVAL_MS || networkDue || resumeDue
            if (!due) resumeRequested.set(false)
            val gapLeft = sinceProbe?.let { MIN_PROBE_GAP_MS - it } ?: 0L
            if (due && gapLeft <= 0L) {
                networkDueAt = null
                resumeRequested.set(false)
                lastCheckAt = now
                if (canProbe()) {
                    lastProbeAt = now
                    probeAndPublish(session)
                }
                continue
            }
            val untilDue = listOfNotNull(
                lastCheckAt?.let { it + PROBE_INTERVAL_MS - now },
                networkDueAt?.let { it - now },
            ).minOrNull() ?: TICK_MS
            val waitMs = (if (due) gapLeft else minOf(untilDue, TICK_MS)).coerceIn(1L, TICK_MS)
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    private fun canProbe(): Boolean =
        customDns.systemLookupsAllowed() && customDns.health.value !is DnsHealth.Healthy

    /** Probe; confirm a BLOCKED result with a second probe before publishing it. */
    private suspend fun probeAndPublish(session: Long) {
        val first = probe.probe() ?: return
        if (first.verdict == TrackerProbeVerdict.OK) return publish(session, first)
        delay(CONFIRM_DELAY_MS)
        val second = probe.probe() ?: return
        // Whatever the second says is the newer truth, as long as the route did not change between.
        if (second.vpn == first.vpn) publish(session, second)
    }

    private suspend fun publish(session: Long, result: TrackerProbeResult) {
        currentCoroutineContext().ensureActive()
        if (sessions.get() != session) return
        if (result.vpn != vpnState.value) return
        latestProbe.value = result
    }

    /** Re-read the VPN state; on a change, forget the evidence gathered over the old route. */
    private fun updateVpnState() {
        val now = vpn.current()
        val before = vpnState.value
        if (now == before) return
        vpnState.value = now
        Timber.i("VPN for Fauxx is now %s (dns=%s); starting the tracker checks over", now.active, now.declaresDns)
        reachability.reset()
        latestProbe.value = null
    }

    private suspend fun resetOnDnsPathChange(wake: Channel<Unit>) {
        customDns.health
            .map { dnsPath(it) }
            .distinctUntilChanged()
            .drop(1)
            .collect { path ->
                Timber.i("Custom DNS is now %s; starting the tracker checks over", path)
                reachability.reset()
                latestProbe.value = null
                networkDueAt = clock.elapsedRealtime() + NETWORK_SETTLE_MS
                wake.trySend(Unit)
            }
    }

    internal companion object {
        val PROBING_STATES = setOf(EngineState.ACTIVE, EngineState.PAUSED_RATE_LIMIT)

        const val FIRST_PROBE_DELAY_MS = 30_000L
        const val PROBE_INTERVAL_MS = 2 * 60 * 60 * 1000L
        const val RESUME_STALE_MS = 30 * 60 * 1000L
        const val NETWORK_SETTLE_MS = 15_000L
        const val MIN_PROBE_GAP_MS = 2 * 60 * 1000L
        const val CONFIRM_DELAY_MS = 60_000L
        const val TICK_MS = 10 * 60 * 1000L

        private fun dnsPath(health: DnsHealth): String = when (health) {
            DnsHealth.Off -> "off"
            DnsHealth.Healthy -> "working"
            is DnsHealth.Degraded -> "degraded"
        }

        /** See the class KDoc. */
        fun verdictFor(
            passive: TrackerReachabilityState,
            probe: TrackerProbeResult?,
            health: DnsHealth,
            vpn: VpnState,
        ): TrackerBlockVerdict {
            val probeApplies = probe != null && probe.vpn == vpn && health !is DnsHealth.Healthy
            val probeBlocked = probeApplies && probe?.verdict == TrackerProbeVerdict.BLOCKED
            val blocked = passive.blocked || probeBlocked
            val kind = when {
                !blocked -> null
                probeBlocked || passive == TrackerReachabilityState.BLOCKED_DNS -> TrackerBlockKind.DNS
                else -> TrackerBlockKind.CONNECTION
            }
            return TrackerBlockVerdict(blocked, kind, vpn)
        }
    }
}
