package com.fauxx.data.crawllist

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.util.Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** #353: the crawl-health memory, from the pure backoff rules up to CrawlListManager's picks. */
class UrlHealthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeClock(var now: Long = 1_000_000_000_000L) : Clock {
        override fun currentTimeMillis() = now
        override fun elapsedRealtime() = now
    }

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    // --- UrlHealthPolicy -------------------------------------------------------------------------

    @Test
    fun `a dead URL is held back for 30 days from the first failure`() {
        val record = UrlHealthPolicy.onFailure(null, HealthFailure.DEAD_URL, now = 0L)
        assertEquals(30 * day, record.suppressedUntil)
        assertTrue(UrlHealthPolicy.isSuppressed(record, 30 * day - 1))
        assertFalse("tried again after the hold, so a page that comes back is used again", UrlHealthPolicy.isSuppressed(record, 30 * day))
    }

    @Test
    fun `a missing domain needs a second failure before it is held back`() {
        val first = UrlHealthPolicy.onFailure(null, HealthFailure.DEAD_HOST, now = 0L)
        assertFalse(UrlHealthPolicy.isSuppressed(first, 1L))
        val second = UrlHealthPolicy.onFailure(first, HealthFailure.DEAD_HOST, now = hour)
        assertEquals(hour + 30 * day, second.suppressedUntil)
    }

    @Test
    fun `bot walls escalate from nothing to hours to days`() {
        val holds = generateSequence(1) { it + 1 }.take(6).map { UrlHealthPolicy.holdMs(HealthFailure.BOT_WALL, it) }.toList()
        assertEquals(listOf(0L, 6 * hour, day, 3 * day, 3 * day, 3 * day), holds)
    }

    @Test
    fun `transient errors escalate more gently`() {
        val holds = generateSequence(1) { it + 1 }.take(6).map { UrlHealthPolicy.holdMs(HealthFailure.TRANSIENT, it) }.toList()
        assertEquals(listOf(0L, hour, 6 * hour, day, 3 * day, 3 * day), holds)
    }

    @Test
    fun `a search engine bot check holds the engine back from the first hit`() {
        val holds = generateSequence(1) { it + 1 }.take(5).map { UrlHealthPolicy.holdMs(HealthFailure.SEARCH_BOT_WALL, it) }.toList()
        assertEquals(listOf(hour, 6 * hour, day, 3 * day, 3 * day), holds)
        val first = UrlHealthPolicy.onFailure(null, HealthFailure.SEARCH_BOT_WALL, now = 0L)
        val weekLater = UrlHealthPolicy.onFailure(first, HealthFailure.SEARCH_BOT_WALL, now = UrlHealthPolicy.STRIKE_WINDOW_MS + 1)
        assertEquals("strikes reset after 7 days without a hit", 1, weekLater.strikes)
    }

    @Test
    fun `engine holds persist, clear on success and on clear, and stay apart from crawl domains`() = runTest {
        val clock = FakeClock()
        val file = File(tmp.root, FileUrlHealthStore.FILE_NAME)
        val tracker = UrlHealthTracker(FileUrlHealthStore(file), clock)

        tracker.recordEngineBotCheck("google")
        assertTrue(tracker.isEngineHeldBack("google"))
        assertFalse(tracker.isEngineHeldBack("bing"))
        assertFalse("an engine hold is not a crawl-domain hold", tracker.isSuppressed("https://www.google.com/", "www.google.com"))
        assertTrue(UrlHealthTracker(FileUrlHealthStore(file), clock).isEngineHeldBack("google"))

        clock.now += hour
        assertFalse("the first hold is one hour", tracker.isEngineHeldBack("google"))
        tracker.recordEngineBotCheck("google")
        clock.now += 5 * hour
        assertTrue("the second is six hours", tracker.isEngineHeldBack("google"))

        tracker.recordEngineSuccess("google")
        assertFalse(tracker.isEngineHeldBack("google"))

        tracker.recordEngineBotCheck("yandex")
        tracker.clear()
        assertFalse(tracker.isEngineHeldBack("yandex"))
    }

    @Test
    fun `strikes far apart start over`() {
        val first = UrlHealthPolicy.onFailure(null, HealthFailure.BOT_WALL, now = 0L)
        val late = UrlHealthPolicy.onFailure(first, HealthFailure.BOT_WALL, now = UrlHealthPolicy.STRIKE_WINDOW_MS + 1)
        assertEquals(1, late.strikes)
        assertEquals(0L, late.suppressedUntil)
        val soon = UrlHealthPolicy.onFailure(first, HealthFailure.BOT_WALL, now = day)
        assertEquals(2, soon.strikes)
    }

    @Test
    fun `a failure never shortens a hold already running`() {
        val dead = HealthRecord(HealthFailure.DEAD_HOST, 2, lastFailureAt = 0L, suppressedUntil = 30 * day)
        val after = UrlHealthPolicy.onFailure(dead, HealthFailure.TRANSIENT, now = day)
        assertEquals(30 * day, after.suppressedUntil)
    }

    @Test
    fun `dead URLs are keyed by URL and everything else by domain`() {
        assertEquals("u:https://a.example/x", UrlHealthPolicy.keyFor(HealthFailure.DEAD_URL, "https://a.example/x", "a.example"))
        assertEquals("d:a.example", UrlHealthPolicy.keyFor(HealthFailure.BOT_WALL, "https://www.a.example/x", "www.A.example"))
    }

    @Test
    fun `prune drops stale records and caps the rest, keeping the longest holds`() {
        val now = 100 * day
        val stale = HealthRecord(HealthFailure.TRANSIENT, 1, lastFailureAt = now - 8 * day, suppressedUntil = 0L)
        val fresh = HealthRecord(HealthFailure.TRANSIENT, 1, lastFailureAt = now - day, suppressedUntil = 0L)
        assertEquals(setOf("d:fresh"), UrlHealthPolicy.prune(mapOf("d:stale" to stale, "d:fresh" to fresh), now).keys)

        val many = (0 until UrlHealthPolicy.MAX_RECORDS + 10).associate { i ->
            "u:$i" to HealthRecord(HealthFailure.DEAD_URL, 1, now, now + i)
        }
        val capped = UrlHealthPolicy.prune(many, now)
        assertEquals(UrlHealthPolicy.MAX_RECORDS, capped.size)
        assertFalse("the shortest holds go first", "u:0" in capped)
        assertTrue("u:${UrlHealthPolicy.MAX_RECORDS + 9}" in capped)
    }

    // --- FileUrlHealthStore ----------------------------------------------------------------------

    @Test
    fun `the file store round-trips and skips lines it cannot read`() {
        val file = File(tmp.root, FileUrlHealthStore.FILE_NAME)
        val store = FileUrlHealthStore(file)
        val records = mapOf(
            "u:https://a.example/x?q=1" to HealthRecord(HealthFailure.DEAD_URL, 1, 5L, 10L),
            "d:b.example" to HealthRecord(HealthFailure.BOT_WALL, 3, 6L, 20L),
        )
        store.save(records)
        assertEquals(records, FileUrlHealthStore(file).load())

        file.appendText("garbage\nd:c.example\tNOPE\t1\t1\t1\nd:d.example\tTRANSIENT\t0\t1\t1\n")
        assertEquals("malformed lines are ignored, not fatal", records, FileUrlHealthStore(file).load())

        store.clear()
        assertFalse(file.exists())
        assertEquals(emptyMap<String, HealthRecord>(), store.load())
    }

    // --- UrlHealthTracker ------------------------------------------------------------------------

    @Test
    fun `the tracker persists, forgets on success, and clears`() = runTest {
        val clock = FakeClock()
        val file = File(tmp.root, FileUrlHealthStore.FILE_NAME)
        val tracker = UrlHealthTracker(FileUrlHealthStore(file), clock)

        tracker.recordFailure("https://a.example/gone", "a.example", HealthFailure.DEAD_URL)
        tracker.recordFailure("https://b.example/", "www.b.example", HealthFailure.BOT_WALL)
        tracker.recordFailure("https://b.example/other", "www.b.example", HealthFailure.BOT_WALL)
        assertTrue(tracker.isSuppressed("https://a.example/gone", "a.example"))
        assertFalse("a dead URL holds back only that URL", tracker.isSuppressed("https://a.example/fine", "a.example"))
        assertTrue("a bot wall holds back the domain", tracker.isSuppressed("https://b.example/third", "b.example"))

        // A fresh instance (the next process) reads the same memory back.
        val reloaded = UrlHealthTracker(FileUrlHealthStore(file), clock)
        assertTrue(reloaded.isSuppressed("https://a.example/gone", "a.example"))
        assertTrue(reloaded.isSuppressed("https://b.example/x", "b.example"))

        reloaded.recordSuccess("https://b.example/x", "b.example")
        assertFalse(reloaded.isSuppressed("https://b.example/x", "b.example"))
        assertFalse(UrlHealthTracker(FileUrlHealthStore(file), clock).isSuppressed("https://b.example/x", "b.example"))

        clock.now += 30 * day
        assertFalse("the hold expires", reloaded.isSuppressed("https://a.example/gone", "a.example"))

        reloaded.recordFailure("https://c.example/", "c.example", HealthFailure.DEAD_URL)
        reloaded.clear()
        assertFalse(reloaded.isSuppressed("https://c.example/", "c.example"))
        assertFalse("clear removes the file", file.exists())
    }

    // --- CrawlListManager picks ------------------------------------------------------------------

    private val blocklist: DomainBlocklist = mockk { every { isUrlBlocked(any()) } returns false }

    private fun manager(urls: List<CrawlEntry>, gate: UrlHealthGate): CrawlListManager {
        val localeManager: LocaleManager = mockk(relaxed = true) { every { currentLocale } returns SupportedLocale.EN }
        return CrawlListManager(mockk(relaxed = true), blocklist, localeManager, FakeClock(), Random(7), gate).apply {
            replaceUrlsForTest(SupportedLocale.EN, urls)
        }
    }

    private fun gateHolding(vararg urls: String) = object : UrlHealthGate {
        override fun isSuppressed(url: String, domain: String) = url in urls
    }

    private val gaming1 = CrawlEntry("https://g1.example/", "g1.example", CategoryPool.GAMING)
    private val gaming2 = CrawlEntry("https://g2.example/", "g2.example", CategoryPool.GAMING)
    private val medical = CrawlEntry("https://m.example/", "m.example", CategoryPool.MEDICAL)

    @Test
    fun `a pick skips held-back URLs`() {
        val manager = manager(listOf(gaming1, gaming2, medical), gateHolding(gaming1.url))
        repeat(20) {
            val pick = manager.nextUrl(CategoryPool.GAMING)
            assertEquals(gaming2, pick)
            manager.markVisited(gaming2.domain, 0L) // keep it eligible
        }
    }

    @Test
    fun `when a whole category is held back the pick moves to another category`() {
        val manager = manager(listOf(gaming1, gaming2, medical), gateHolding(gaming1.url, gaming2.url))
        assertEquals(medical, manager.nextUrl(CategoryPool.GAMING))
    }

    @Test
    fun `when everything is held back the memory is ignored rather than stalling`() {
        val all = listOf(gaming1, gaming2, medical)
        val manager = manager(all, gateHolding(*all.map { it.url }.toTypedArray()))
        val pick = manager.nextUrl(CategoryPool.GAMING)
        assertNotNull(pick)
        assertEquals("it stays in the asked-for category", CategoryPool.GAMING, pick!!.category)
        assertNotNull(manager.nextUrlOrWait(null))
    }

    @Test
    fun `the wait-for-rate-limit path uses the same held-back filter`() {
        val manager = manager(listOf(gaming1, gaming2), gateHolding(gaming1.url))
        manager.markVisited(gaming2.domain, FakeClock().now)
        val pending = manager.nextUrlOrWait(CategoryPool.GAMING)
        assertEquals("it waits for the healthy page instead of returning the held-back one", gaming2, pending?.entry)
        assertTrue(pending!!.waitMs > 0)
    }

    @Test
    fun `a gate that throws never breaks a pick`() {
        val broken = object : UrlHealthGate {
            override fun isSuppressed(url: String, domain: String): Boolean = error("disk gone")
        }
        assertNotNull(manager(listOf(gaming1), broken).nextUrl(CategoryPool.GAMING))
    }
}
