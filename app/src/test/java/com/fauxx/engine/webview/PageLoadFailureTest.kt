package com.fauxx.engine.webview

import android.webkit.WebViewClient
import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.HealthFailure
import com.fauxx.data.crawllist.UrlHealthStore
import com.fauxx.data.crawllist.UrlHealthTracker
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.dns.CustomDns
import com.fauxx.support.FakeClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * #353: how a main-frame failure is classified, how the latest navigation decides, and which
 * failures [CrawlHealthReporter] holds against a page.
 */
class PageLoadFailureTest {

    // --- LoadFailureClassifier -------------------------------------------------------------------

    @Test
    fun `HTTP statuses map to dead pages, bot walls and transient errors`() {
        assertEquals(LoadFailureKind.DEAD, LoadFailureClassifier.fromHttp(404).kind)
        assertEquals(LoadFailureKind.DEAD, LoadFailureClassifier.fromHttp(410).kind)
        for (status in listOf(401, 403, 429, 451, 999)) {
            assertEquals("$status", LoadFailureKind.BOT_WALL, LoadFailureClassifier.fromHttp(status).kind)
        }
        assertEquals(LoadFailureKind.TRANSIENT, LoadFailureClassifier.fromHttp(500).kind)
        assertEquals(LoadFailureKind.TRANSIENT, LoadFailureClassifier.fromHttp(503).kind)
    }

    @Test
    fun `a challenge header makes any status a bot wall`() {
        assertEquals(LoadFailureKind.BOT_WALL, LoadFailureClassifier.fromHttp(503, mapOf("CF-Mitigated" to "challenge")).kind)
        assertEquals(LoadFailureKind.BOT_WALL, LoadFailureClassifier.fromHttp(404, mapOf("X-DataDome" to "protected")).kind)
    }

    @Test
    fun `network errors are classified by their Chromium name`() {
        fun kind(code: Int, name: String) = LoadFailureClassifier.fromNetwork(code, name).kind
        val nx = LoadFailureClassifier.fromNetwork(WebViewClient.ERROR_HOST_LOOKUP, "net::ERR_NAME_NOT_RESOLVED")
        assertEquals(LoadFailureKind.DEAD, nx.kind)
        assertTrue("NXDOMAIN needs a DNS check before it counts", nx.nameNotResolved)
        // Same WebView code as NXDOMAIN, but it is the device that is offline.
        assertEquals(LoadFailureKind.LOCAL, kind(WebViewClient.ERROR_HOST_LOOKUP, "net::ERR_INTERNET_DISCONNECTED"))
        assertEquals(LoadFailureKind.LOCAL, kind(WebViewClient.ERROR_HOST_LOOKUP, "net::ERR_ADDRESS_INVALID"))
        // Fauxx's own loopback DNS proxy (#227) answers every failed CONNECT this way.
        assertEquals(LoadFailureKind.LOCAL, kind(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, "net::ERR_TUNNEL_CONNECTION_FAILED"))
        assertEquals(LoadFailureKind.LOCAL, kind(WebViewClient.ERROR_PROXY_AUTHENTICATION, "net::ERR_PROXY_CONNECTION_FAILED"))
        assertEquals(LoadFailureKind.TRANSIENT, kind(WebViewClient.ERROR_TIMEOUT, "net::ERR_TIMED_OUT"))
        assertEquals(LoadFailureKind.TRANSIENT, kind(WebViewClient.ERROR_CONNECT, "net::ERR_CONNECTION_RESET"))
        val refused = LoadFailureClassifier.fromNetwork(WebViewClient.ERROR_CONNECT, "net::ERR_CONNECTION_REFUSED")
        assertEquals(LoadFailureKind.TRANSIENT, refused.kind)
        assertTrue(refused.connectionRefused)
        assertEquals(LoadFailureKind.DEAD, kind(WebViewClient.ERROR_UNSAFE_RESOURCE, "net::ERR_BLOCKED_BY_CLIENT"))
        assertEquals(LoadFailureKind.DEAD, kind(WebViewClient.ERROR_UNKNOWN, "net::ERR_CLEARTEXT_NOT_PERMITTED"))
        assertEquals(LoadFailureKind.TRANSIENT, kind(WebViewClient.ERROR_UNKNOWN, ""))
    }

    @Test
    fun `the description keeps the raw cause the action log always showed`() {
        assertEquals("dead page, http(404)", LoadFailureClassifier.fromHttp(404).describe())
        assertEquals(
            "network error, net(-8): net::ERR_TIMED_OUT",
            LoadFailureClassifier.fromNetwork(WebViewClient.ERROR_TIMEOUT, "net::ERR_TIMED_OUT").describe(),
        )
    }

    // --- MainFrameLoadTracker --------------------------------------------------------------------

    @Test
    fun `the tracker follows the latest navigation and ignores reports from before it`() {
        val tracker = MainFrameLoadTracker()
        val bot = LoadFailureClassifier.fromHttp(403)
        tracker.onFailure(bot)
        assertNull("nothing started yet: a leftover from the previous page", tracker.current())

        tracker.onNavigationStart()
        tracker.onFailure(bot)
        tracker.onFailure(LoadFailureClassifier.fromHttp(500))
        assertEquals(bot, tracker.current())

        tracker.onNavigationStart()
        assertNull(tracker.current())

        tracker.reset()
        tracker.onFailure(bot)
        assertNull(tracker.current())
    }

    // --- CrawlHealthReporter ---------------------------------------------------------------------

    private val entry = CrawlEntry("https://www.site.example/page", "www.site.example", CategoryPool.MEDICAL)
    private val clock = FakeClock()

    private fun ip(vararg bytes: Int) = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })

    private class Harness(
        val tracker: UrlHealthTracker,
        val reporter: CrawlHealthReporter,
        val lookups: MutableList<String>,
    )

    private fun harness(
        online: Boolean = true,
        siteAnswer: SystemLookupResult = SystemLookupResult.Failed,
        controlAnswer: SystemLookupResult = SystemLookupResult.Resolved(listOf(ip(208, 80, 154, 224))),
        dnsBlocker: Boolean = false,
        systemLookupsAllowed: Boolean = true,
        store: UrlHealthStore = UrlHealthStore.inMemory(),
    ): Harness {
        val lookups = mutableListOf<String>()
        val lookup = SystemDnsLookup { host, _ ->
            lookups += host
            if (host == TrackerDnsProbe.CONTROL_HOST) controlAnswer else siteAnswer
        }
        val verdict = if (dnsBlocker) {
            TrackerBlockVerdict(blocked = true, kind = TrackerBlockKind.DNS, vpn = VpnState.NONE)
        } else {
            TrackerBlockVerdict.CLEAR
        }
        val blockSource = object : TrackerBlockSource { override val verdict = flowOf(verdict) }
        val customDns: CustomDns = mockk(relaxed = true) { every { systemLookupsAllowed() } returns systemLookupsAllowed }
        val tracker = UrlHealthTracker(store, clock)
        return Harness(tracker, CrawlHealthReporter(tracker, { online }, lookup, blockSource, customDns), lookups)
    }

    @Test
    fun `local failures and failures while offline are never held against the page`() = runTest {
        val h = harness(online = false)
        repeat(5) { h.reporter.report(entry, LoadFailureClassifier.fromHttp(404)) }
        assertFalse(h.tracker.isSuppressed(entry.url, entry.domain))

        val online = harness()
        repeat(5) {
            online.reporter.report(entry, LoadFailureClassifier.fromNetwork(-6, "net::ERR_TUNNEL_CONNECTION_FAILED"))
        }
        assertFalse(online.tracker.isSuppressed(entry.url, entry.domain))
    }

    @Test
    fun `a 404 holds the URL back at once, and a success clears the domain`() = runTest {
        val h = harness()
        h.reporter.report(entry, LoadFailureClassifier.fromHttp(404))
        assertTrue(h.tracker.isSuppressed(entry.url, entry.domain))
        assertFalse(h.tracker.isSuppressed("https://www.site.example/other", entry.domain))

        val other = entry.copy(url = "https://www.site.example/other")
        h.reporter.report(other, LoadFailureClassifier.fromHttp(403))
        h.reporter.report(other, LoadFailureClassifier.fromHttp(403))
        assertTrue("two bot walls hold the domain back", h.tracker.isSuppressed("https://www.site.example/third", entry.domain))
        h.reporter.report(other, null)
        assertFalse(h.tracker.isSuppressed("https://www.site.example/third", entry.domain))
    }

    @Test
    fun `a missing name counts as dead only when the control resolves and no DNS blocker is seen`() = runTest {
        val nx = LoadFailureClassifier.fromNetwork(-2, "net::ERR_NAME_NOT_RESOLVED")

        val confirmed = harness()
        assertEquals(HealthFailure.DEAD_HOST, confirmed.reporter.healthFailureFor(entry, nx))
        assertEquals(listOf(entry.domain, TrackerDnsProbe.CONTROL_HOST), confirmed.lookups)

        assertNull("the resolver is down, not the site", harness(controlAnswer = SystemLookupResult.Failed).reporter.healthFailureFor(entry, nx))
        assertNull("a blocker answering NXDOMAIN looks the same", harness(dnsBlocker = true).reporter.healthFailureFor(entry, nx))
        assertNull("sinkholed by a DNS blocker", harness(siteAnswer = SystemLookupResult.Resolved(listOf(ip(0, 0, 0, 0)))).reporter.healthFailureFor(entry, nx))
        assertEquals(
            "it resolves now: a passing resolver hiccup",
            HealthFailure.TRANSIENT,
            harness(siteAnswer = SystemLookupResult.Resolved(listOf(ip(93, 184, 215, 14)))).reporter.healthFailureFor(entry, nx),
        )
        assertNull(harness(siteAnswer = SystemLookupResult.Unavailable).reporter.healthFailureFor(entry, nx))

        val strict = harness(systemLookupsAllowed = false)
        assertNull("strict custom DNS: no lookup through the device's resolver", strict.reporter.healthFailureFor(entry, nx))
        assertTrue(strict.lookups.isEmpty())
    }

    @Test
    fun `a refused connection to a sinkholed name is a DNS blocker, not the site`() = runTest {
        val refused = LoadFailureClassifier.fromNetwork(-6, "net::ERR_CONNECTION_REFUSED")
        assertNull(harness(siteAnswer = SystemLookupResult.Resolved(listOf(ip(127, 0, 0, 1)))).reporter.healthFailureFor(entry, refused))
        assertEquals(
            HealthFailure.TRANSIENT,
            harness(siteAnswer = SystemLookupResult.Resolved(listOf(ip(93, 184, 215, 14)))).reporter.healthFailureFor(entry, refused),
        )
    }

    @Test
    fun `kinds map onto the health memory`() = runTest {
        val h = harness()
        assertEquals(HealthFailure.DEAD_URL, h.reporter.healthFailureFor(entry, LoadFailureClassifier.fromHttp(410)))
        assertEquals(HealthFailure.BOT_WALL, h.reporter.healthFailureFor(entry, LoadFailureClassifier.fromHttp(429)))
        assertEquals(HealthFailure.TRANSIENT, h.reporter.healthFailureFor(entry, LoadFailureClassifier.fromHttp(502)))
        assertTrue("no lookups for failures that need none", h.lookups.isEmpty())
    }

    @Test
    fun `a broken store never fails the action`() = runTest {
        val broken = object : UrlHealthStore {
            override fun load(): Map<String, com.fauxx.data.crawllist.HealthRecord> = error("disk gone")
            override fun save(records: Map<String, com.fauxx.data.crawllist.HealthRecord>) = error("disk gone")
            override fun clear() = Unit
        }
        harness(store = broken).reporter.report(entry, LoadFailureClassifier.fromHttp(404))
    }
}
