package com.fauxx.data.custompool

import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.support.FakeClock
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The share of crawl actions that draw from the user's own sites (#328), and the safety gates
 * [CrawlListManager] applies to them.
 */
class CustomPoolMixerTest {

    // --- share math ---

    @Test
    fun `no entries means no custom share and no randomness consumed`() {
        assertEquals(0f, CustomPoolMixer.share(0), 0f)
        val counting = object : Random() {
            var calls = 0
            override fun nextBits(bitCount: Int): Int { calls++; return 0 }
        }
        assertFalse(CustomPoolMixer.drawCustom(0, counting))
        assertEquals("an empty pool must not shift seeded draws", 0, counting.calls)
    }

    @Test
    fun `share grows per entry and is capped`() {
        assertEquals(0.05f, CustomPoolMixer.share(1), 1e-6f)
        assertEquals(0.15f, CustomPoolMixer.share(3), 1e-6f)
        assertEquals(CustomPoolMixer.MAX_SHARE, CustomPoolMixer.share(6), 1e-6f)
        assertEquals(CustomPoolMixer.MAX_SHARE, CustomPoolMixer.share(200), 1e-6f)
        assertTrue("built-in corpus must keep the majority", CustomPoolMixer.MAX_SHARE < 0.5f)
    }

    @Test
    fun `observed draw rate matches the share`() {
        val random = Random(328)
        val n = 50_000
        val hits = (1..n).count { CustomPoolMixer.drawCustom(10, random) }
        assertEquals(CustomPoolMixer.MAX_SHARE.toDouble(), hits.toDouble() / n, 0.01)
    }

    // --- CrawlListManager mixing ---

    private val builtIn = listOf(
        CrawlEntry("https://built.in/a", "built.in", CategoryPool.COOKING),
    )

    /** nextFloat() below any non-zero share forces the custom branch. */
    private val alwaysCustom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0f
    }

    /** nextFloat() at the top of the range never draws custom. */
    private val neverCustom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.999f
    }

    private fun manager(
        sites: List<String>,
        random: Random,
        blocked: (String) -> Boolean = { false },
        clock: FakeClock = FakeClock(1_000_000L),
    ): CrawlListManager {
        val blocklist: DomainBlocklist = mockk {
            every { isUrlBlocked(any()) } answers { blocked(firstArg()) }
        }
        val localeManager: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns SupportedLocale.EN
        }
        return CrawlListManager(
            context = mockk(relaxed = true),
            blocklist = blocklist,
            localeManager = localeManager,
            clock = clock,
            random = random,
            customPool = CustomPoolSource.of(sites = sites),
        ).also { it.replaceUrlsForTest(SupportedLocale.EN, builtIn) }
    }

    @Test
    fun `a custom draw returns a user site, marked custom, regardless of category`() {
        val entry = manager(listOf("https://lokal.example.de/"), alwaysCustom).nextUrl(CategoryPool.GAMING)
        assertNotNull(entry)
        assertEquals("https://lokal.example.de/", entry!!.url)
        assertEquals("lokal.example.de", entry.domain)
        assertTrue(entry.isCustom)
    }

    @Test
    fun `without a custom draw the built-in corpus serves the action`() {
        val entry = manager(listOf("https://lokal.example.de/"), neverCustom).nextUrl(CategoryPool.COOKING)
        assertEquals("https://built.in/a", entry?.url)
        assertFalse(entry!!.isCustom)
    }

    @Test
    fun `custom sites still pass the domain blocklist`() {
        val m = manager(listOf("https://blocked.example.com/"), alwaysCustom, blocked = { it.contains("blocked.example.com") })
        val entry = m.nextUrl(CategoryPool.COOKING)
        assertEquals("a blocklisted custom site falls back to the built-in corpus", "https://built.in/a", entry?.url)
    }

    @Test
    fun `custom sites failing the dispatch recheck are never returned`() {
        val m = manager(listOf("javascript:alert(1)", "https://192.168.1.1/"), alwaysCustom)
        assertEquals("https://built.in/a", m.nextUrl(null)?.url)
    }

    @Test
    fun `custom sites share the per-domain rate limit and fall back while cooling down`() {
        val clock = FakeClock(1_000_000L)
        val m = manager(listOf("https://lokal.example.de/"), alwaysCustom, clock = clock)
        assertTrue(m.nextUrl(null)!!.isCustom)
        // Same instant: the only custom domain is cooling down, so the built-in corpus answers.
        assertEquals("https://built.in/a", m.nextUrl(null)?.url)
        clock.nowMs += 5_001L
        assertTrue(m.nextUrl(null)!!.isCustom)
    }

    @Test
    fun `custom sites never become curated hosts for SERP link following`() {
        val m = manager(listOf("https://lokal.example.de/"), alwaysCustom)
        assertFalse(m.isCuratedHost("lokal.example.de"))
        assertTrue(m.isCuratedHost("built.in"))
    }
}
