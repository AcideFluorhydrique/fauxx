package com.fauxx.engine.webview

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Host matching and fail-open loading for the bundled tracker list (#341). */
class TrackerHostsTest {

    private val hosts = TrackerHosts { setOf("doubleclick.net", "bat.bing.com", "connect.facebook.net") }

    @Test
    fun `matches a listed host exactly and its subdomains`() {
        assertTrue(hosts.isTracker("doubleclick.net"))
        assertTrue(hosts.isTracker("stats.g.doubleclick.net"))
        assertTrue(hosts.isTracker("bat.bing.com"))
        assertTrue(hosts.isTracker("connect.facebook.net"))
    }

    @Test
    fun `normalises case and stray dots like the blocklist does`() {
        assertTrue(hosts.isTracker("Stats.G.DoubleClick.NET"))
        assertTrue(hosts.isTracker(".doubleclick.net"))
        assertTrue("a fully-qualified trailing dot is the same host", hosts.isTracker("doubleclick.net."))
    }

    @Test
    fun `does not match parents, siblings or lookalikes`() {
        assertFalse("the parent of a listed subdomain is first-party content", hosts.isTracker("bing.com"))
        assertFalse("a sibling of a listed subdomain", hosts.isTracker("www.bing.com"))
        assertFalse("facebook.net itself is not listed", hosts.isTracker("facebook.net"))
        assertFalse("a suffix without a dot boundary", hosts.isTracker("notdoubleclick.net"))
        assertFalse("a listed name as a prefix of another domain", hosts.isTracker("doubleclick.net.example.com"))
        assertFalse(hosts.isTracker(""))
        assertFalse(hosts.isTracker("net"))
    }

    @Test
    fun `a list that fails to load turns detection off instead of crashing`() {
        val broken = TrackerHosts { throw IOException("missing") }
        assertFalse(broken.available)
        assertFalse(broken.isTracker("doubleclick.net"))
    }

    @Test
    fun `loads the asset through the context and survives malformed json`() {
        assertTrue(fromAsset("""{"domains": ["Criteo.com", " adnxs.com ", ""]}""").isTracker("x.criteo.com"))
        assertTrue(fromAsset("""{"domains": ["adnxs.com"]}""").isTracker("ib.adnxs.com"))
        val malformed = fromAsset("""{"domains": [""")
        assertFalse(malformed.available)
        assertFalse(malformed.isTracker("adnxs.com"))
        val missingField = fromAsset("""{"other": []}""")
        assertFalse(missingField.available)
    }

    private fun fromAsset(json: String): TrackerHosts {
        val assets: AssetManager = mockk()
        val context: Context = mockk()
        every { context.assets } returns assets
        every { assets.open(TrackerHosts.ASSET) } returns ByteArrayInputStream(json.toByteArray())
        return TrackerHosts(context)
    }

    // --- the bundled list -------------------------------------------------------------------

    private val bundled: Set<String> by lazy {
        val file = File("src/main/assets/${TrackerHosts.ASSET}")
        assertTrue("asset missing at ${file.absolutePath}", file.isFile)
        TrackerHosts.parse(file.readText())
    }

    @Test
    fun `the bundled list is a reasonable size and well formed`() {
        assertTrue("expected roughly 60-120 entries, got ${bundled.size}", bundled.size in 60..120)
        val malformed = bundled.filterNot { Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""").matches(it) }
        assertTrue("entries must be bare lowercase host names: $malformed", malformed.isEmpty())
    }

    @Test
    fun `the bundled list never claims first-party content domains`() {
        val list = TrackerHosts { bundled }
        val firstParty = listOf(
            "google.com", "www.google.com", "googleapis.com", "youtube.com", "www.youtube.com",
            "facebook.com", "www.facebook.com", "facebook.net", "bing.com", "www.bing.com",
            "microsoft.com", "amazon.com", "www.amazon.com", "linkedin.com", "www.linkedin.com",
            "twitter.com", "x.com", "tiktok.com", "www.tiktok.com", "pinterest.com", "reddit.com",
            "www.reddit.com", "yandex.ru", "yahoo.com", "www.yahoo.com", "baidu.com", "mail.ru",
            "wikipedia.org", "snapchat.com", "quora.com", "licdn.com", "cloudflare.com",
        )
        val claimed = firstParty.filter { list.isTracker(it) }
        assertTrue("first-party content domains must not count as trackers: $claimed", claimed.isEmpty())
    }

    @Test
    fun `the bundled list covers the big ad and analytics networks`() {
        val list = TrackerHosts { bundled }
        val expected = listOf(
            "securepubads.g.doubleclick.net", "www.google-analytics.com", "www.googletagmanager.com",
            "pagead2.googlesyndication.com", "connect.facebook.net", "bat.bing.com",
            "aax.amazon-adsystem.com", "ib.adnxs.com", "match.adsrvr.org", "sb.scorecardresearch.com",
            "static.hotjar.com", "dpm.demdex.net", "mc.yandex.ru", "analytics.tiktok.com",
        )
        val missed = expected.filterNot { list.isTracker(it) }
        assertTrue("missing well-known trackers: $missed", missed.isEmpty())
    }
}
