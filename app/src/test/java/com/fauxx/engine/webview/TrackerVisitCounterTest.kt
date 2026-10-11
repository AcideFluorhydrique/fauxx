package com.fauxx.engine.webview

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Error-code classification and per-visit tracker bookkeeping (#341). */
class TrackerVisitCounterTest {

    private val hosts = TrackerHosts { setOf("doubleclick.net", "adnxs.com") }

    private val dnsFail = "net::ERR_NAME_NOT_RESOLVED"

    // --- TrackerErrorClassifier ---------------------------------------------------------------

    private fun classify(name: String, code: Int = WebViewClient.ERROR_UNKNOWN) =
        TrackerErrorClassifier.classify(code, "net::$name")

    @Test
    fun `names that mean the tracker's name did not resolve are DNS blocks`() {
        for (name in listOf("ERR_NAME_NOT_RESOLVED", "ERR_NAME_RESOLUTION_FAILED", "ERR_ADDRESS_INVALID", "ERR_ADDRESS_UNREACHABLE")) {
            assertEquals(name, TrackerBlockKind.DNS, classify(name))
        }
    }

    @Test
    fun `refused, reset, timed out and cut-off connections are connection blocks`() {
        for (name in listOf(
            "ERR_CONNECTION_REFUSED", "ERR_CONNECTION_RESET", "ERR_CONNECTION_ABORTED", "ERR_CONNECTION_FAILED",
            "ERR_CONNECTION_CLOSED", "ERR_TIMED_OUT", "ERR_CONNECTION_TIMED_OUT", "ERR_TUNNEL_CONNECTION_FAILED",
        )) {
            assertEquals(name, TrackerBlockKind.CONNECTION, classify(name))
        }
    }

    @Test
    fun `the name decides even when the code would say otherwise`() {
        // ERR_INTERNET_DISCONNECTED reaches apps as ERROR_HOST_LOOKUP: no internet is not a blocker.
        assertNull(classify("ERR_INTERNET_DISCONNECTED", WebViewClient.ERROR_HOST_LOOKUP))
        assertNull(classify("ERR_NETWORK_CHANGED", WebViewClient.ERROR_CONNECT))
    }

    @Test
    fun `names that are not a blocker at work are left out`() {
        for (name in listOf(
            "ERR_INTERNET_DISCONNECTED", "ERR_NETWORK_CHANGED", "ERR_BLOCKED_BY_CLIENT", "ERR_BLOCKED_BY_RESPONSE",
            "ERR_BLOCKED_BY_ORB", "ERR_EMPTY_RESPONSE", "ERR_HTTP2_PROTOCOL_ERROR", "ERR_QUIC_PROTOCOL_ERROR",
            "ERR_ABORTED", "ERR_FAILED", "ERR_SOMETHING_NEW",
        )) {
            assertNull(name, classify(name))
        }
    }

    @Test
    fun `without a usable name the code decides, conservatively`() {
        for (description in listOf(null, "", "something went wrong")) {
            assertEquals(TrackerBlockKind.DNS, TrackerErrorClassifier.classify(WebViewClient.ERROR_HOST_LOOKUP, description))
            for (code in listOf(
                WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO,
                WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            )) {
                assertEquals("code $code", TrackerBlockKind.CONNECTION, TrackerErrorClassifier.classify(code, description))
            }
            for (code in listOf(
                WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME,
                WebViewClient.ERROR_AUTHENTICATION, WebViewClient.ERROR_PROXY_AUTHENTICATION,
                WebViewClient.ERROR_REDIRECT_LOOP, WebViewClient.ERROR_UNSUPPORTED_SCHEME,
                WebViewClient.ERROR_BAD_URL, WebViewClient.ERROR_FILE, WebViewClient.ERROR_FILE_NOT_FOUND,
                WebViewClient.ERROR_TOO_MANY_REQUESTS, WebViewClient.ERROR_UNSAFE_RESOURCE, 0, -999,
            )) {
                assertNull("code $code", TrackerErrorClassifier.classify(code, description))
            }
        }
    }

    @Test
    fun `attempts are remembered by hash, not by URL`() {
        val a = TrackerVisitCounter.urlHash("https://doubleclick.net/a?u=https%3A%2F%2Fnews.example%2F")
        assertEquals(a, TrackerVisitCounter.urlHash("https://doubleclick.net/a?u=https%3A%2F%2Fnews.example%2F"))
        assertTrue(a != TrackerVisitCounter.urlHash("https://doubleclick.net/b?u=https%3A%2F%2Fnews.example%2F"))
    }

    // --- TrackerVisitCounter ------------------------------------------------------------------

    @Test
    fun `only tracker hosts count as attempts`() {
        val counter = TrackerVisitCounter(hosts)
        assertTrue(counter.recordAttempt("https://stats.g.doubleclick.net/a", "stats.g.doubleclick.net"))
        assertFalse(counter.recordAttempt("https://news.example/app.js", "news.example"))
        assertEquals(1, counter.snapshot().attempted)
    }

    @Test
    fun `an error counts only for a URL that was counted as an attempt`() {
        val counter = TrackerVisitCounter(hosts)
        counter.recordAttempt("https://ib.adnxs.com/ut", "ib.adnxs.com")

        // Same tracker host, different URL: a request Fauxx refused itself never became an attempt.
        assertNull(counter.recordError("https://ib.adnxs.com/other", WebViewClient.ERROR_HOST_LOOKUP, dnsFail))
        assertEquals(TrackerBlockKind.DNS, counter.recordError("https://ib.adnxs.com/ut", WebViewClient.ERROR_HOST_LOOKUP, dnsFail))
        // Its outcome is in; a second error for the same single request is not counted again.
        assertNull(counter.recordError("https://ib.adnxs.com/ut", WebViewClient.ERROR_HOST_LOOKUP, dnsFail))

        assertEquals(TrackerVisitCounts(attempted = 1, dnsBlocked = 1), counter.snapshot())
    }

    @Test
    fun `the same URL requested twice can fail twice`() {
        val counter = TrackerVisitCounter(hosts)
        val url = "https://doubleclick.net/px"
        counter.recordAttempt(url, "doubleclick.net")
        counter.recordAttempt(url, "doubleclick.net")
        counter.recordError(url, WebViewClient.ERROR_CONNECT, "net::ERR_CONNECTION_RESET")
        counter.recordError(url, WebViewClient.ERROR_TIMEOUT, "net::ERR_TIMED_OUT")
        assertNull(counter.recordError(url, WebViewClient.ERROR_TIMEOUT, "net::ERR_TIMED_OUT"))
        assertEquals(TrackerVisitCounts(attempted = 2, connectionBlocked = 2), counter.snapshot())
    }

    @Test
    fun `a non-blocking error consumes the attempt without counting it as blocked`() {
        val counter = TrackerVisitCounter(hosts)
        val url = "https://doubleclick.net/redirect"
        counter.recordAttempt(url, "doubleclick.net")
        assertNull(counter.recordError(url, WebViewClient.ERROR_REDIRECT_LOOP, "net::ERR_TOO_MANY_REDIRECTS"))
        assertNull(counter.recordError(url, WebViewClient.ERROR_HOST_LOOKUP, dnsFail))
        assertEquals(TrackerVisitCounts(attempted = 1), counter.snapshot())
    }

    @Test
    fun `a visit past the pending cap stops counting new attempts`() {
        val counter = TrackerVisitCounter(hosts, maxPending = 3)
        repeat(5) { counter.recordAttempt("https://doubleclick.net/$it", "doubleclick.net") }
        assertEquals(3, counter.snapshot().attempted)
        // A URL already pending still counts.
        assertTrue(counter.recordAttempt("https://doubleclick.net/0", "doubleclick.net"))
        assertEquals(4, counter.snapshot().attempted)
    }

    @Test
    fun `reset starts a new visit`() {
        val counter = TrackerVisitCounter(hosts)
        counter.recordAttempt("https://doubleclick.net/a", "doubleclick.net")
        counter.markMainFrameFailed()
        counter.reset()
        assertEquals(TrackerVisitCounts(), counter.snapshot())
        counter.markMainFrameFailed()
        counter.clearMainFrameFailed()
        assertFalse("a new navigation starts with a clean main frame", counter.snapshot().mainFrameFailed)
        // An error for the previous visit's request is not counted against the new one.
        assertNull(counter.recordError("https://doubleclick.net/a", WebViewClient.ERROR_HOST_LOOKUP, dnsFail))
    }

    @Test
    fun `attempts from a background thread and errors from another agree`() {
        val counter = TrackerVisitCounter(hosts, maxPending = 10_000)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val done = CountDownLatch(2_000)
            repeat(2_000) { i ->
                pool.execute {
                    val url = "https://doubleclick.net/$i"
                    counter.recordAttempt(url, "doubleclick.net")
                    if (i % 2 == 0) counter.recordError(url, WebViewClient.ERROR_HOST_LOOKUP, dnsFail)
                    done.countDown()
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(TrackerVisitCounts(attempted = 2_000, dnsBlocked = 1_000), counter.snapshot())
    }
}
