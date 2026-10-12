package com.fauxx.engine.webview

import com.fauxx.engine.webview.TrackerDnsProbe.Companion.CONTROL_HOST
import com.fauxx.engine.webview.TrackerDnsProbe.Companion.TRACKER_HOSTS
import com.fauxx.engine.webview.TrackerDnsProbe.Companion.verdictFor
import com.fauxx.support.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** The active DNS check's verdict (#341). Every lookup here is faked: no test touches real DNS. */
class TrackerDnsProbeTest {

    private fun ip(vararg bytes: Int) = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })
    private fun resolved(vararg addresses: InetAddress) = SystemLookupResult.Resolved(addresses.toList())

    private val real = resolved(ip(142, 250, 1, 1))
    private val realV6 = resolved(ip(0x26, 0x07, 0xf8, 0xb0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x20, 0x0e))
    private val failed = SystemLookupResult.Failed
    private val unavailable = SystemLookupResult.Unavailable

    @Test
    fun `no verdict when the control does not resolve`() {
        assertNull("offline is never blocked", verdictFor(failed, listOf(failed, failed, failed)))
        assertNull(verdictFor(failed, listOf(real, real, real)))
        assertNull("a sinkholed control means something odd, not a tracker blocker", verdictFor(resolved(ip(0, 0, 0, 0)), listOf(failed, failed, failed)))
        assertNull(verdictFor(real, emptyList()))
    }

    @Test
    fun `a lookup that could not be made voids the verdict`() {
        assertNull(verdictFor(real, listOf(failed, failed, unavailable)))
        assertNull(verdictFor(real, listOf(real, real, unavailable)))
        assertNull(verdictFor(unavailable, listOf(failed, failed, failed)))
    }

    @Test
    fun `blocked when at least two of three trackers fail`() {
        assertEquals(TrackerProbeVerdict.BLOCKED, verdictFor(real, listOf(failed, failed, failed)))
        assertEquals(TrackerProbeVerdict.BLOCKED, verdictFor(real, listOf(failed, real, failed)))
        assertEquals(TrackerProbeVerdict.BLOCKED, verdictFor(realV6, listOf(real, failed, failed)))
        // One lost lookup is not a blocker.
        assertEquals(TrackerProbeVerdict.OK, verdictFor(real, listOf(failed, real, real)))
        assertEquals(TrackerProbeVerdict.OK, verdictFor(real, listOf(real, real, real)))
    }

    @Test
    fun `sinkhole answers count as blocked`() {
        val sinkholes = listOf(
            ip(0, 0, 0, 0),
            ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ip(127, 0, 0, 1),
            ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            ip(192, 168, 1, 2),
            ip(10, 0, 0, 1),
            ip(169, 254, 1, 1),
        )
        for (sinkhole in sinkholes) {
            assertEquals("$sinkhole", TrackerProbeVerdict.BLOCKED, verdictFor(real, listOf(resolved(sinkhole), failed, real)))
        }
        // A real address among sinkholes is a real answer.
        assertEquals(TrackerProbeVerdict.OK, verdictFor(real, listOf(resolved(ip(0, 0, 0, 0), ip(142, 250, 1, 2)), failed, real)))
    }

    @Test
    fun `a probe looks up the control and the trackers, with a timeout, and records the route`() = runTest {
        val asked = mutableListOf<Pair<String, Long>>()
        val route = VpnState(active = true, declaresDns = true)
        val probe = TrackerDnsProbe(
            lookup = { host, timeout -> synchronized(asked) { asked += host to timeout }; if (host == CONTROL_HOST) real else failed },
            vpn = { route },
            clock = FakeClock(1_234L),
        )
        assertEquals(TrackerProbeResult(TrackerProbeVerdict.BLOCKED, route, atElapsedMs = 1_234L), probe.probe())
        assertEquals((TRACKER_HOSTS + CONTROL_HOST).toSet(), asked.map { it.first }.toSet())
        assertTrue("every lookup is bounded", asked.all { it.second == TrackerDnsProbe.TIMEOUT_MS })
    }

    @Test
    fun `no verdict is recorded when the control fails`() = runTest {
        val probe = TrackerDnsProbe(lookup = { _, _ -> failed }, vpn = { VpnState.NONE }, clock = FakeClock())
        assertNull(probe.probe())
    }

    @Test
    fun `only one probe runs at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val probe = TrackerDnsProbe(
            lookup = { _, _ -> gate.await(); real },
            vpn = { VpnState.NONE },
            clock = FakeClock(),
        )
        val first = async { probe.probe() }
        testScheduler.runCurrent()
        assertNull("a second probe while one is in flight gets no verdict", probe.probe())
        gate.complete(Unit)
        assertEquals(TrackerProbeVerdict.OK, first.await()?.verdict)
    }

    @Test
    fun `on API 26-28 a full thread pool makes the lookup unavailable, not failed`() = runBlocking {
        val rejecting = object : AbstractExecutorService() {
            override fun execute(command: Runnable) = throw RejectedExecutionException("full")
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        val lookup = AndroidSystemDnsLookup(sdkInt = 28, fallbackPool = rejecting)
        assertEquals(SystemLookupResult.Unavailable, lookup.lookup("example.invalid", 1_000))
    }

    @Test
    fun `the probe hosts are trackers on the bundled list and the control is not`() {
        val bundled = TrackerHosts { TrackerHosts.parse(File("src/main/assets/${TrackerHosts.ASSET}").readText()) }
        for (host in TRACKER_HOSTS) assertTrue("$host must stay in the tracker list", bundled.isTracker(host))
        assertFalse(bundled.isTracker(CONTROL_HOST))
        val companies = TRACKER_HOSTS.map { it.split('.').takeLast(2).joinToString(".") }.toSet()
        assertEquals("three hosts from three different companies", 3, companies.size)
    }
}
