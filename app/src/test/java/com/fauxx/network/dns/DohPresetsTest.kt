package com.fauxx.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DohPresetsTest {

    @Test
    fun `every preset is https with literal bootstrap addresses`() {
        for (preset in DohPresets.ALL) {
            assertTrue("${preset.id} must be a valid https URL", DohPresets.isValidCustomUrl(preset.url))
            assertTrue("${preset.id} needs bootstrap addresses", preset.bootstrap.isNotEmpty())
            // Literals only: a hostname here would need the system resolver, defeating the point.
            for (ip in preset.bootstrap) {
                assertTrue("${preset.id}: $ip must be an IP literal", ip.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' })
            }
            assertEquals(preset.bootstrap.size, preset.bootstrapAddresses().size)
        }
    }

    @Test
    fun `ids are unique and the default exists`() {
        assertEquals(DohPresets.ALL.size, DohPresets.ALL.map { it.id }.toSet().size)
        assertNotNull(DohPresets.byId(DohPresets.DEFAULT_ID))
        assertFalse(DohPresets.ALL.any { it.id == DohPresets.CUSTOM_ID })
    }

    @Test
    fun `custom URLs must be https with a host`() {
        assertTrue(DohPresets.isValidCustomUrl("https://dns.nextdns.io/abc123"))
        assertTrue(DohPresets.isValidCustomUrl("  https://doh.example/dns-query  "))
        assertFalse("cleartext would leak every lookup", DohPresets.isValidCustomUrl("http://dns.example/dns-query"))
        assertFalse(DohPresets.isValidCustomUrl("dns.example"))
        assertFalse(DohPresets.isValidCustomUrl(""))
        assertFalse(DohPresets.isValidCustomUrl("https://"))
        assertFalse("no credentials in a resolver URL", DohPresets.isValidCustomUrl("https://user:pass@dns.example/dns-query"))
    }

    @Test
    fun `http is accepted only for a resolver on a private network (#334)`() {
        for (ok in listOf(
            "http://192.168.1.2/dns-query",
            "http://10.0.0.53:3000/dns-query",
            "http://172.20.1.1/dns-query",
            "http://100.101.102.103/dns-query", // Tailscale
            "http://127.0.0.1:3000/dns-query",
            "http://[fd00::53]/dns-query",
            "http://pihole.lan/dns-query",
            "http://doh.home.arpa/dns-query",
            "http://dns.internal/dns-query",
            "http://box.tail1234.ts.net/dns-query",
            "http://resolver.local:3000/dns-query",
            "http://localhost:3000/dns-query",
        )) {
            assertTrue("$ok must be accepted", DohPresets.isValidCustomUrl(ok))
            assertTrue("$ok is cleartext", DohPresets.isCleartext(ok))
        }
        for (bad in listOf(
            "http://dns.example/dns-query",
            "http://9.9.9.9/dns-query",
            "http://100.128.0.1/dns-query", // just past CGNAT
            "http://[2620:fe::fe]/dns-query",
            "http://[fe80::1]/dns-query", // needs an interface scope a URL cannot carry
            "http://lan/dns-query", // a bare suffix is not a name under it
            "http://evil.lan.example.com/dns-query",
            "http://user:pass@pihole.lan/dns-query",
        )) {
            assertFalse("$bad must be rejected", DohPresets.isValidCustomUrl(bad))
        }
        assertFalse(DohPresets.isCleartext("https://dns.nextdns.io/abc123"))
    }

    @Test
    fun `with a private server IP an http URL may use any hostname`() {
        for (url in listOf("http://pihole/dns-query", "http://dns.home:3000/dns-query", "http://doh.corp/dns-query", "http://x.vpn/dns-query")) {
            assertTrue("$url with a private IP", DohPresets.isValidCustomUrl(url, "192.168.1.2"))
            assertFalse("$url without one", DohPresets.isValidCustomUrl(url))
            assertFalse("$url with a public one", DohPresets.isValidCustomUrl(url, "9.9.9.9"))
        }
        assertTrue(DohPresets.isValidCustomUrl("http://doh.corp/dns-query", "fd00::53"))
        assertFalse("credentials are still refused", DohPresets.isValidCustomUrl("http://u:p@pihole/dns-query", "192.168.1.2"))
    }

    @Test
    fun `strict mode needs a server IP only for an http URL named by a hostname`() {
        assertTrue(DohPresets.needsServerIpInStrict("http://pihole.lan/dns-query", ""))
        assertFalse(DohPresets.needsServerIpInStrict("http://pihole.lan/dns-query", "192.168.1.2"))
        assertFalse(DohPresets.needsServerIpInStrict("http://192.168.1.2/dns-query", ""))
        assertFalse(DohPresets.needsServerIpInStrict("https://dns.nextdns.io/abc", ""))
    }

    @Test
    fun `an http resolver's server IP must be private too`() {
        assertTrue(DohPresets.isUsableServerIp("http://pihole.lan/dns-query", "192.168.1.2"))
        assertFalse("the queries would cross the internet in the clear", DohPresets.isUsableServerIp("http://pihole.lan/dns-query", "9.9.9.9"))
        assertTrue("https may name any address", DohPresets.isUsableServerIp("https://dns.lan/dns-query", "9.9.9.9"))
        assertFalse(DohPresets.isUsableServerIp("https://dns.lan/dns-query", "dns.lan"))
    }

    @Test
    fun `a custom server IP is numeric only`() {
        assertEquals(java.net.InetAddress.getByName("192.168.6.7"), DohPresets.parseServerIp(" 192.168.6.7 "))
        assertEquals(java.net.InetAddress.getByName("2606:4700::1111"), DohPresets.parseServerIp("[2606:4700::1111]"))
        for (bad in listOf("", "dns.lan", "192.168.6.7:443", "999.1.1.1", "0.0.0.0", "::", "224.0.0.251", "fe80::1")) {
            assertEquals("'$bad' must be rejected", null, DohPresets.parseServerIp(bad))
        }
    }
}
