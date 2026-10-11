package com.fauxx.network.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AddressScopeTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private network addresses`() {
        for (s in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.2", "100.64.0.1",
            "100.127.255.254", "169.254.1.1", "::1", "fd12:3456::1", "fc00::1")) {
            assertTrue("$s is private", AddressScope.isPrivateNetwork(ip(s)))
        }
        for (s in listOf("9.9.9.9", "172.32.0.1", "100.128.0.1", "100.63.255.255", "2620:fe::fe", "fe80::1", "0.0.0.0")) {
            assertFalse("$s is not private", AddressScope.isPrivateNetwork(ip(s)))
        }
    }

    @Test
    fun `private hosts by address or by name`() {
        for (h in listOf("192.168.1.2", "fd00::53", "localhost", "pihole.lan", "PiHole.LAN", "dns.home.arpa", "a.internal",
            "box.tail1.ts.net", "nas.local", "pihole.lan.")) {
            assertTrue("$h is private", AddressScope.isPrivateHost(h))
        }
        for (h in listOf("dns.example", "9.9.9.9", "lan", ".lan", "evil.lan.example.com", "ts.net", "notlan")) {
            assertFalse("$h is not private", AddressScope.isPrivateHost(h))
        }
    }

    @Test
    fun `the proxy's public-target check agrees on CGNAT and ULA`() {
        assertFalse(LoopbackProxy.isPublicTarget(ip("100.64.0.1")))
        assertFalse(LoopbackProxy.isPublicTarget(ip("fd00::1")))
        assertTrue(LoopbackProxy.isPublicTarget(ip("100.128.0.1")))
        assertTrue(LoopbackProxy.isPublicTarget(ip("2620:fe::fe")))
    }
}
