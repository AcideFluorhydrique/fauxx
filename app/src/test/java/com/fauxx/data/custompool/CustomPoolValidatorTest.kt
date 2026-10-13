package com.fauxx.data.custompool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing, normalisation and the safety rules for user-added searches and sites (#328).
 * Pure JVM: the blocklists are injected as predicates.
 */
class CustomPoolValidatorTest {

    private val allowAll: (String) -> Boolean = { false }

    private fun url(raw: String, isBlocked: (String) -> Boolean = allowAll) =
        CustomPoolValidator.validateUrl(raw, isBlocked)

    private fun query(raw: String, isBlocked: (String) -> Boolean = allowAll) =
        CustomPoolValidator.validateQuery(raw, isBlocked)

    private fun rejected(reason: CustomPoolRejection) = CustomPoolValidation.Rejected(reason)

    // --- searches ---

    @Test
    fun `a search is trimmed, whitespace-collapsed and keeps its case`() {
        assertEquals(CustomPoolValidation.Valid("Wetter Berlin morgen"), query("  Wetter   Berlin\u00A0 morgen "))
    }

    @Test
    fun `a search in any script is accepted`() {
        assertEquals(CustomPoolValidation.Valid("погода в Казани"), query("погода в Казани"))
        assertEquals(CustomPoolValidation.Valid("天気 東京"), query("天気 東京"))
    }

    @Test
    fun `a blank search is EMPTY`() {
        assertEquals(rejected(CustomPoolRejection.EMPTY), query("   "))
    }

    @Test
    fun `a search over the length cap is rejected`() {
        assertEquals(rejected(CustomPoolRejection.TOO_LONG), query("a".repeat(CustomPoolValidator.MAX_QUERY_LENGTH + 1)))
        assertTrue(query("a".repeat(CustomPoolValidator.MAX_QUERY_LENGTH)) is CustomPoolValidation.Valid)
    }

    @Test
    fun `control and bidi override characters are rejected`() {
        assertEquals(rejected(CustomPoolRejection.INVALID_CHARACTERS), query("news\tsport"))
        assertEquals(rejected(CustomPoolRejection.INVALID_CHARACTERS), query("abc\u202Edef"))
    }

    @Test
    fun `a search the blocklist matches is BLOCKED, and the blocklist sees the normalised text`() {
        var seen: String? = null
        val result = query("  988   crisis line ") { seen = it; it.contains("988") }
        assertEquals(rejected(CustomPoolRejection.BLOCKED), result)
        assertEquals("988 crisis line", seen)
    }

    // --- sites ---

    @Test
    fun `a bare domain becomes an https URL with a root path`() {
        assertEquals(CustomPoolValidation.Valid("https://example.de/"), url("example.de"))
    }

    @Test
    fun `host is lowercased, the default port and the fragment are dropped, path and query kept`() {
        assertEquals(
            CustomPoolValidation.Valid("https://news.example.com/lokal/sport?page=2"),
            url("HTTPS://News.Example.COM:443/lokal/sport?page=2#top")
        )
        assertEquals(CustomPoolValidation.Valid("http://example.com/"), url("http://example.com:80"))
        assertEquals(CustomPoolValidation.Valid("https://example.com:8443/"), url("https://example.com:8443"))
    }

    @Test
    fun `an internationalised domain is stored in its ASCII form`() {
        assertEquals(CustomPoolValidation.Valid("https://xn--e1afmkfd.xn--p1ai/"), url("https://пример.рф"))
        assertEquals(CustomPoolValidation.Valid("https://xn--e1afmkfd.xn--p1ai/"), url("пример.рф"))
    }

    @Test
    fun `only http and https are accepted`() {
        listOf(
            "ftp://example.com/file",
            "javascript:alert(1)",
            "file:///etc/hosts",
            "data:text/html,hi",
            "mailto:someone@example.com",
            "intent://scan/#Intent;scheme=zxing;end",
            "content://com.example/x",
        ).forEach { assertEquals(it, rejected(CustomPoolRejection.NOT_A_WEB_ADDRESS), url(it)) }
    }

    @Test
    fun `garbage and hostless input is not a web address`() {
        listOf("https://", "https:///path", "http://exa mple.com", "https://ex_ample.com/", "https://example.com:99999/")
            .forEach {
                val r = url(it)
                assertTrue("$it -> $r", r is CustomPoolValidation.Rejected)
            }
    }

    @Test
    fun `credentials in the URL are rejected`() {
        assertEquals(rejected(CustomPoolRejection.HAS_CREDENTIALS), url("https://user:pass@example.com/"))
        assertEquals(rejected(CustomPoolRejection.HAS_CREDENTIALS), url("https://example.com@evil.com/"))
    }

    @Test
    fun `local and private-network hosts are rejected`() {
        listOf(
            "http://192.168.1.1/",
            "https://10.0.0.5/admin",
            "http://127.0.0.1:8080/",
            "http://[::1]/",
            "http://localhost/",
            "http://router/",
            "https://nas.lan/",
            "https://printer.local/",
            "https://pi.home.arpa/",
            "https://grafana.internal/",
            "https://site.onion/",
        ).forEach { assertEquals(it, rejected(CustomPoolRejection.LOCAL_ADDRESS), url(it)) }
    }

    @Test
    fun `the domain blocklist is consulted with the normalised URL`() {
        var seen: String? = null
        val result = url("Bad.Example.com/x") { seen = it; true }
        assertEquals(rejected(CustomPoolRejection.BLOCKED), result)
        assertEquals("https://bad.example.com/x", seen)
    }

    @Test
    fun `an address over the length cap is rejected`() {
        val long = "https://example.com/" + "a".repeat(CustomPoolValidator.MAX_URL_LENGTH)
        assertEquals(rejected(CustomPoolRejection.TOO_LONG), url(long))
    }

    @Test
    fun `whitespace inside an address is rejected`() {
        assertEquals(rejected(CustomPoolRejection.INVALID_CHARACTERS), url("https://example.com/a b"))
    }

    @Test
    fun `dispatch-time recheck accepts stored URLs and fails closed on anything else`() {
        assertTrue(CustomPoolValidator.isDispatchableUrl("https://example.com/"))
        assertTrue(CustomPoolValidator.isDispatchableUrl("http://xn--e1afmkfd.xn--p1ai/"))
        assertFalse(CustomPoolValidator.isDispatchableUrl("javascript:alert(1)"))
        assertFalse(CustomPoolValidator.isDispatchableUrl("https://192.168.0.1/"))
        assertFalse(CustomPoolValidator.isDispatchableUrl("https://u:p@example.com/"))
        assertFalse(CustomPoolValidator.isDispatchableUrl("not a url"))
    }

    // --- bulk paste ---

    @Test
    fun `splitLines keeps one entry per non-blank line`() {
        assertEquals(listOf("a.com", "b.com", "c d"), CustomPoolValidator.splitLines("a.com\r\n\n  b.com \n\tc d\n"))
    }

    @Test
    fun `planAdd accepts valid lines and counts each rejection reason`() {
        val lines = listOf("a.com", "ftp://b.com", "http://192.168.0.1", "a.com", "c.com", "", "blocked.com")
        val plan = CustomPoolValidator.planAdd(lines, existing = emptyList(), capacity = 10) {
            url(it) { u -> u.contains("blocked") }
        }
        assertEquals(listOf("https://a.com/", "https://c.com/"), plan.accepted)
        assertEquals(1, plan.rejections[CustomPoolRejection.NOT_A_WEB_ADDRESS])
        assertEquals(1, plan.rejections[CustomPoolRejection.LOCAL_ADDRESS])
        assertEquals(1, plan.rejections[CustomPoolRejection.DUPLICATE])
        assertEquals(1, plan.rejections[CustomPoolRejection.BLOCKED])
        assertEquals(4, plan.rejectedCount)
        // Blank lines are not "rejections".
        assertFalse(plan.rejections.containsKey(CustomPoolRejection.EMPTY))
    }

    @Test
    fun `planAdd treats an existing entry as a duplicate, case-insensitively`() {
        val plan = CustomPoolValidator.planAdd(
            listOf("Le Monde", "le monde", "Libération"),
            existing = listOf("le monde"),
            capacity = 10,
        ) { query(it) }
        assertEquals(listOf("Libération"), plan.accepted)
        assertEquals(2, plan.rejections[CustomPoolRejection.DUPLICATE])
    }

    @Test
    fun `planAdd stops at capacity and reports the rest as over the limit`() {
        val plan = CustomPoolValidator.planAdd(
            listOf("one", "two", "three", "four"),
            existing = emptyList(),
            capacity = 2,
        ) { query(it) }
        assertEquals(listOf("one", "two"), plan.accepted)
        assertEquals(2, plan.rejections[CustomPoolRejection.LIMIT_REACHED])
    }

    @Test
    fun `planAdd reads at most MAX_BULK_LINES lines of a paste`() {
        val lines = (1..CustomPoolValidator.MAX_BULK_LINES + 25).map { "term $it" }
        var validated = 0
        val plan = CustomPoolValidator.planAdd(lines, existing = emptyList(), capacity = Int.MAX_VALUE) {
            validated++
            query(it)
        }
        assertEquals(CustomPoolValidator.MAX_BULK_LINES, validated)
        assertEquals(CustomPoolValidator.MAX_BULK_LINES, plan.accepted.size)
        assertEquals(25, plan.rejections[CustomPoolRejection.LIMIT_REACHED])
    }
}
