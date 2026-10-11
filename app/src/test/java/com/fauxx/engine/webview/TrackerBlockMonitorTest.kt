package com.fauxx.engine.webview

import com.fauxx.engine.EngineState
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.dns.NetworkChanges
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.CONFIRM_DELAY_MS
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.FIRST_PROBE_DELAY_MS
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.MIN_PROBE_GAP_MS
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.NETWORK_SETTLE_MS
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.PROBE_INTERVAL_MS
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.TICK_MS
import com.fauxx.engine.webview.TrackerBlockMonitor.Companion.verdictFor
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.HostResolver
import com.fauxx.util.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Scheduling, resets and the combined verdict of the #341 tracker checks. Runs on virtual time with
 * a fake resolver: no test here waits on a real clock or touches real DNS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerBlockMonitorTest {

    private val real = SystemLookupResult.Resolved(listOf(InetAddress.getByAddress(byteArrayOf(142.toByte(), 250.toByte(), 1, 1))))

    private class FakeDns : CustomDns {
        override val health = MutableStateFlow<DnsHealth>(DnsHealth.Off)
        var systemAllowed = true
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun noiseResolver(): HostResolver? = null
        override fun systemLookupsAllowed(): Boolean = systemAllowed
    }

    private inner class Rig(scope: TestScope) {
        val dns = FakeDns()
        val network = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val engine = MutableStateFlow(EngineState.ACTIVE)
        val reachability = TrackerReachability(windowSize = 10, minAttempts = 1, minVisits = 1, flagAt = 0.6, clearBelow = 0.3)
        var trackersBlocked = false
        var route = VpnState.NONE
        var lookups = 0

        /** Suspends every lookup until completed, when set; survives cancellation like a blocking thread. */
        var gate: CompletableDeferred<Unit>? = null

        /** Time the device spent in deep sleep: elapsedRealtime counts it, coroutine delays do not. */
        var sleptMs = 0L
        val clock = object : Clock {
            override fun currentTimeMillis(): Long = scope.testScheduler.currentTime + sleptMs
            override fun elapsedRealtime(): Long = scope.testScheduler.currentTime + sleptMs
        }
        val verdicts = mutableListOf<TrackerBlockVerdict>()
        val monitor = TrackerBlockMonitor(
            reachability,
            TrackerDnsProbe(
                lookup = { host, _ ->
                    lookups++
                    gate?.let { g -> withContext(NonCancellable) { g.await() } }
                    if (host == TrackerDnsProbe.CONTROL_HOST || !trackersBlocked) real else SystemLookupResult.Failed
                },
                vpn = { route },
                clock = clock,
            ),
            dns,
            object : NetworkChanges {
                override val changes = network
            },
            { route },
            clock,
        )

        val probes get() = lookups / (TrackerDnsProbe.TRACKER_HOSTS.size + 1)
        val blocked get() = verdicts.lastOrNull()?.blocked == true
    }

    /** Runs [body] with the monitor watching and its verdict collected, and always stops both. */
    private suspend fun TestScope.watching(rig: Rig, body: suspend () -> Unit) {
        val collector = launch { rig.monitor.verdict.collect { rig.verdicts += it } }
        val job = launch { rig.monitor.watch(rig.engine) }
        try {
            body()
        } finally {
            job.cancel()
            collector.cancel()
        }
    }

    @Test
    fun `starting forgets the previous session's samples`() = runTest {
        val rig = Rig(this)
        rig.reachability.recordVisit(TrackerVisitCounts(attempted = 5, dnsBlocked = 5))
        assertTrue(rig.reachability.state.value.blocked)
        watching(rig) {
            testScheduler.runCurrent()
            assertEquals(TrackerReachabilityState.NOT_ENOUGH_DATA, rig.reachability.state.value)
        }
    }

    @Test
    fun `probes after start, then on schedule, and soon after a network change`() = runTest {
        val rig = Rig(this)
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS - 1)
            assertEquals("not before the first-probe delay", 0, rig.probes)
            testScheduler.advanceTimeBy(2)
            assertEquals(1, rig.probes)

            testScheduler.advanceTimeBy(PROBE_INTERVAL_MS - 10)
            assertEquals(1, rig.probes)
            testScheduler.advanceTimeBy(TICK_MS)
            assertEquals("again once the interval has passed", 2, rig.probes)

            // A network change well after the last probe: probe again once it settles.
            testScheduler.advanceTimeBy(10 * 60_000L)
            rig.network.emit(Unit)
            testScheduler.advanceTimeBy(NETWORK_SETTLE_MS - 1)
            assertEquals(2, rig.probes)
            testScheduler.advanceTimeBy(2)
            assertEquals(3, rig.probes)

            // A burst of changes right after a probe waits out the minimum gap, then probes once.
            repeat(5) { rig.network.emit(Unit); testScheduler.advanceTimeBy(1_000) }
            assertEquals(3, rig.probes)
            testScheduler.advanceTimeBy(MIN_PROBE_GAP_MS)
            assertEquals(4, rig.probes)
        }
    }

    @Test
    fun `the schedule follows elapsed time, deep sleep included`() = runTest {
        val rig = Rig(this)
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + 1)
            assertEquals(1, rig.probes)
            // Two hours of deep sleep: no coroutine time passes, but the next short wait sees it.
            rig.sleptMs += PROBE_INTERVAL_MS
            testScheduler.advanceTimeBy(TICK_MS + 1)
            assertEquals(2, rig.probes)
        }
    }

    @Test
    fun `never probes while the engine is paused, in strict mode, or with custom DNS working`() = runTest {
        val rig = Rig(this)
        rig.engine.value = EngineState.PAUSED_WIFI
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS * 4)
            assertEquals(0, rig.probes)
            for (paused in listOf(EngineState.PAUSED_BATTERY, EngineState.PAUSED_NETWORK_BLOCKED, EngineState.PAUSED_QUIET_HOURS)) {
                rig.engine.value = paused
                testScheduler.advanceTimeBy(60_000)
                assertEquals(paused.name, 0, rig.probes)
            }
            rig.dns.systemAllowed = false
            rig.engine.value = EngineState.ACTIVE
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + PROBE_INTERVAL_MS + TICK_MS)
            assertEquals("strict mode: never the device's resolver", 0, rig.probes)
        }

        val healthy = Rig(this)
        healthy.dns.health.value = DnsHealth.Healthy
        watching(healthy) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + PROBE_INTERVAL_MS + TICK_MS)
            assertEquals("custom DNS working: tracker names never go to the ISP", 0, healthy.probes)
            // Custom DNS off again: probe once things settle.
            healthy.dns.health.value = DnsHealth.Off
            testScheduler.advanceTimeBy(NETWORK_SETTLE_MS + 1)
            assertEquals(1, healthy.probes)
        }

        val resumed = Rig(this)
        resumed.engine.value = EngineState.PAUSED_BATTERY
        watching(resumed) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS * 3)
            assertEquals(0, resumed.probes)
            resumed.engine.value = EngineState.ACTIVE
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + 1)
            assertEquals("probes once the engine runs, after the start delay", 1, resumed.probes)
        }
    }

    @Test
    fun `a blocked probe is published only once a second one agrees`() = runTest {
        val rig = Rig(this)
        rig.trackersBlocked = true
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + 1)
            assertEquals(1, rig.probes)
            assertFalse("not published on one probe", rig.blocked)
            testScheduler.advanceTimeBy(CONFIRM_DELAY_MS)
            assertEquals(2, rig.probes)
            assertTrue(rig.blocked)
        }

        val flaky = Rig(this)
        flaky.trackersBlocked = true
        watching(flaky) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + 1)
            flaky.trackersBlocked = false
            testScheduler.advanceTimeBy(CONFIRM_DELAY_MS)
            assertEquals(2, flaky.probes)
            assertFalse("the second probe disagreed", flaky.blocked)
        }
    }

    @Test
    fun `a custom DNS change starts the checks over`() = runTest {
        val rig = Rig(this)
        rig.trackersBlocked = true
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + CONFIRM_DELAY_MS + 1)
            assertTrue(rig.blocked)
            rig.reachability.recordVisit(TrackerVisitCounts(attempted = 5, dnsBlocked = 5))
            rig.dns.health.value = DnsHealth.Degraded(0L, "down")
            testScheduler.runCurrent()
            assertEquals(TrackerReachabilityState.NOT_ENOUGH_DATA, rig.reachability.state.value)
            assertFalse("the old probe result is dropped too", rig.blocked)

            rig.reachability.recordVisit(TrackerVisitCounts(attempted = 5, dnsBlocked = 5))
            rig.dns.health.value = DnsHealth.Healthy
            testScheduler.runCurrent()
            assertEquals("recovering counts too", TrackerReachabilityState.NOT_ENOUGH_DATA, rig.reachability.state.value)
        }
    }

    @Test
    fun `a VPN change starts the checks over`() = runTest {
        val rig = Rig(this)
        rig.trackersBlocked = true
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + CONFIRM_DELAY_MS + 1)
            assertTrue(rig.blocked)
            rig.reachability.recordVisit(TrackerVisitCounts(attempted = 5, connectionBlocked = 5))

            // A VPN comes up: the network callback fires.
            rig.route = VpnState(active = true, declaresDns = false)
            rig.network.emit(Unit)
            testScheduler.runCurrent()
            assertEquals(TrackerReachabilityState.NOT_ENOUGH_DATA, rig.reachability.state.value)
            assertFalse(rig.blocked)
            assertEquals(rig.route, rig.verdicts.last().vpn)

            // Its DNS servers change, noticed when the dashboard resumes.
            rig.reachability.recordVisit(TrackerVisitCounts(attempted = 5, connectionBlocked = 5))
            rig.route = VpnState(active = true, declaresDns = true)
            rig.monitor.refresh()
            testScheduler.runCurrent()
            assertEquals(TrackerReachabilityState.NOT_ENOUGH_DATA, rig.reachability.state.value)
        }
    }

    @Test
    fun `resuming the dashboard probes only when the result is missing or old`() = runTest {
        val rig = Rig(this)
        watching(rig) {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + 1)
            assertEquals(1, rig.probes)
            testScheduler.advanceTimeBy(MIN_PROBE_GAP_MS)
            rig.monitor.refresh()
            testScheduler.advanceTimeBy(1_000)
            assertEquals("a fresh result is kept", 1, rig.probes)

            testScheduler.advanceTimeBy(TrackerBlockMonitor.RESUME_STALE_MS)
            rig.monitor.refresh()
            testScheduler.advanceTimeBy(1_000)
            assertEquals("an old one is checked again", 2, rig.probes)
        }
    }

    @Test
    fun `a probe still running when the engine stops never writes its result`() = runTest {
        val rig = Rig(this)
        rig.trackersBlocked = false
        val gate = CompletableDeferred<Unit>()
        rig.gate = gate
        val collector = launch { rig.monitor.verdict.collect { rig.verdicts += it } }
        val first = launch { rig.monitor.watch(rig.engine) }
        try {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + 1)
            assertTrue("the probe is in flight", rig.lookups > 0)
            first.cancel()
            // Engine started again while the old lookups are still out.
            rig.trackersBlocked = true
            val second = launch { rig.monitor.watch(rig.engine) }
            try {
                gate.complete(Unit)
                testScheduler.runCurrent()
                assertNull("the stopped session's result is not published", rig.monitor.lastProbe.value)
                rig.gate = null
                testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + CONFIRM_DELAY_MS + 1)
                assertTrue("the new session probes normally", rig.blocked)
            } finally {
                second.cancel()
            }
        } finally {
            first.cancel()
            collector.cancel()
        }
    }

    @Test
    fun `stopping clears the probe result`() = runTest {
        val rig = Rig(this)
        rig.trackersBlocked = true
        val collector = launch { rig.monitor.verdict.collect { rig.verdicts += it } }
        val job = launch { rig.monitor.watch(rig.engine) }
        try {
            testScheduler.advanceTimeBy(FIRST_PROBE_DELAY_MS + CONFIRM_DELAY_MS + 1)
            assertTrue(rig.blocked)
            job.cancel()
            testScheduler.runCurrent()
            assertFalse(rig.blocked)
        } finally {
            job.cancel()
            collector.cancel()
        }
    }

    // --- verdictFor --------------------------------------------------------------------------

    private val noVpn = VpnState.NONE
    private val vpnDns = VpnState(active = true, declaresDns = true)
    private fun probe(verdict: TrackerProbeVerdict, route: VpnState) = TrackerProbeResult(verdict, route, 0L)

    @Test
    fun `the passive window alone can flag it, with its kind`() {
        val dns = verdictFor(TrackerReachabilityState.BLOCKED_DNS, null, DnsHealth.Off, noVpn)
        assertTrue(dns.blocked)
        assertEquals(TrackerBlockKind.DNS, dns.kind)
        val connection = verdictFor(TrackerReachabilityState.BLOCKED_CONNECTION, probe(TrackerProbeVerdict.OK, noVpn), DnsHealth.Healthy, noVpn)
        assertTrue(connection.blocked)
        assertEquals(TrackerBlockKind.CONNECTION, connection.kind)
        assertFalse(verdictFor(TrackerReachabilityState.OK, null, DnsHealth.Off, noVpn).blocked)
        assertNull(verdictFor(TrackerReachabilityState.NOT_ENOUGH_DATA, null, DnsHealth.Off, noVpn).kind)
    }

    @Test
    fun `a blocked probe flags it as DNS-level, while it still applies`() {
        val passive = TrackerReachabilityState.NOT_ENOUGH_DATA
        val off = verdictFor(passive, probe(TrackerProbeVerdict.BLOCKED, noVpn), DnsHealth.Off, noVpn)
        assertTrue(off.blocked)
        assertEquals(TrackerBlockKind.DNS, off.kind)
        assertTrue(verdictFor(passive, probe(TrackerProbeVerdict.BLOCKED, noVpn), DnsHealth.Degraded(0L, "down"), noVpn).blocked)
        // A probe taken over another route says nothing about this one.
        assertFalse(verdictFor(passive, probe(TrackerProbeVerdict.BLOCKED, noVpn), DnsHealth.Off, vpnDns).blocked)
        // Custom DNS working: Fauxx's lookups bypass the system resolver, VPN or not.
        assertFalse(verdictFor(passive, probe(TrackerProbeVerdict.BLOCKED, noVpn), DnsHealth.Healthy, noVpn).blocked)
        assertFalse(verdictFor(passive, probe(TrackerProbeVerdict.BLOCKED, vpnDns), DnsHealth.Healthy, vpnDns).blocked)
        assertFalse(verdictFor(passive, probe(TrackerProbeVerdict.OK, vpnDns), DnsHealth.Off, vpnDns).blocked)
        // A connection-level passive verdict plus a blocked probe has DNS-level evidence.
        assertEquals(
            TrackerBlockKind.DNS,
            verdictFor(TrackerReachabilityState.BLOCKED_CONNECTION, probe(TrackerProbeVerdict.BLOCKED, noVpn), DnsHealth.Off, noVpn).kind,
        )
    }

    @Test
    fun `the verdict carries the current route even when nothing is blocked`() {
        assertEquals(vpnDns, verdictFor(TrackerReachabilityState.OK, null, DnsHealth.Off, vpnDns).vpn)
    }
}
