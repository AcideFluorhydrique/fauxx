package com.fauxx.engine.dns

import com.fauxx.data.model.DnsMode
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.WebViewProxyOverride
import com.fauxx.network.dns.DegradeCause
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.DohPresets
import com.fauxx.support.FakeClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class CustomDnsRouterTest {

    /**
     * The WebView's override. Unlike the real one it confirms at once by default, so it can also be
     * told to refuse ([accept], which is what a 5 s confirmation timeout returns), to throw
     * ([throwOnSet]), or to hold set() pending until [setGate] completes.
     */
    private class FakeOverride(
        @Volatile var supported: Boolean = true,
        @Volatile var accept: Boolean = true,
        @Volatile var clears: Boolean = true,
        var clearDelayMs: Long = 0L,
    ) : WebViewProxyOverride {
        // Copy-on-write: tests iterate it (count, in) while the router appends from its own
        // threads, and a synchronizedList throws ConcurrentModificationException on that.
        val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        @Volatile var port: Int? = null
        @Volatile var throwOnSet = false
        @Volatile var setGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override fun isSupported() = supported
        override suspend fun set(port: Int): Boolean {
            calls += "set"
            setGate?.await()
            if (throwOnSet) throw IllegalStateException("WebView provider went away")
            if (accept) this.port = port
            return accept
        }
        override suspend fun clear(): Boolean {
            calls += "clear"
            if (clearDelayMs > 0) delay(clearDelayMs)
            if (clears) port = null
            return clears
        }
    }

    private val profile = MutableStateFlow(PoisonProfile())
    private val repo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile() } answers { profile.value }
        every { profiles } returns profile
        // Writes land in the same flow the router watches, as DataStore's would.
        io.mockk.coEvery { updateProfile(any()) } coAnswers {
            val transform = firstArg<(PoisonProfile) -> PoisonProfile>()
            synchronized(profile) { profile.value = transform(profile.value) }
        }
    }
    private val override = FakeOverride()
    @Volatile private var intercepted = false
    private val networkChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Every proxy the router built, with the callback it would call on dying. */
    private val made: MutableList<Pair<com.fauxx.network.dns.LoopbackProxy, (Throwable) -> Unit>> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** How many of the next proxies fail to start. */
    private val failStarts = java.util.concurrent.atomic.AtomicInteger(0)

    private fun servingProxies() = made.toList().count { (p, _) -> isServing(p.port) }
    private val router = CustomDnsRouter(
        repo, FakeClock(0L), override, DnsInterceptionProbe { intercepted },
        object : NetworkChanges { override val changes = networkChanged },
    ).apply {
        // Out of the way of tests that count override calls; the retry tests shorten it.
        retryDelaysMs = listOf(60_000L)
        proxyFactory = { resolver, credentials, onDied ->
            com.fauxx.network.dns.LoopbackProxy(resolver, credentials, onDied = onDied).also { p ->
                made += p to onDied
                // Already bound, so the router's own start() throws, as a failed bind would.
                if (failStarts.getAndUpdate { maxOf(0, it - 1) } > 0) p.start()
            }
        }
    }

    private fun isServing(port: Int) = runCatching { Socket("127.0.0.1", port).close() }.isSuccess

    @After
    fun tearDown() = runBlocking {
        // A test that fails while a set() is held at setGate leaves the router parked inside its
        // lock; release it, or stop() waits on that lock forever and the failure becomes a hang.
        override.setGate?.complete(Unit)
        withTimeout(10_000) { router.stop() }
    }

    private fun doh(provider: String = DohPresets.DEFAULT_ID, url: String = "") =
        PoisonProfile(dnsMode = DnsMode.DOH, dohProvider = provider, dohCustomUrl = url)

    /** The realm the running proxy demands, read back off the wire. */
    private fun realmOf(port: Int): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".toByteArray())
            val head = s.getInputStream().bufferedReader().readText()
            return Regex("realm=\"([^\"]+)\"").find(head)!!.groupValues[1]
        }
    }

    @Test
    fun `system mode never touches the WebView`() = runBlocking {
        router.start()
        assertTrue(override.calls.isEmpty())
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `DoH starts a proxy, points the WebView at it, and answers only its own challenge`() = runBlocking {
        profile.value = doh()
        router.start()

        val port = assertNotNull(override.port).let { override.port!! }
        assertEquals(DnsHealth.Healthy, router.health.value)
        val realm = realmOf(port)
        assertNotNull(router.credentialsFor("127.0.0.1", realm))
        assertNull("a site's own challenge must never get the secret", router.credentialsFor("example.com", realm))
        assertNull("a different realm on loopback must not get it either", router.credentialsFor("127.0.0.1", "other"))
    }

    @Test
    fun `stop clears the WebView override before anything else and forgets the credentials`() = runBlocking {
        profile.value = doh()
        router.start()
        val realm = realmOf(override.port!!)
        router.stop()

        assertEquals(listOf("set", "clear"), override.calls)
        assertNull(router.credentialsFor("127.0.0.1", realm))
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `a WebView without proxy support never touches the override`() = runBlocking {
        override.supported = false
        profile.value = doh()
        router.start()
        assertTrue(override.calls.isEmpty())
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `a WebView that refuses the override is left on direct connections`() = runBlocking {
        override.accept = false
        profile.value = doh()
        router.start()
        // The settings collector's first value is the one start() applied; it must not try again
        // (the backoff retry owns that), whichever of the two runs first.
        withTimeout(5_000) { router.settingsApplied.first { it >= 1 } }
        assertEquals(listOf("set", "clear"), override.calls)
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `an invalid custom URL degrades instead of routing`() = runBlocking {
        // http is only for a private resolver (#334); a public one stays invalid.
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "http://not-https.example/dns-query")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `an invalid server IP for a custom URL degrades instead of routing`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
            .copy(dohCustomServerIp = "dns.lan")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `a custom URL with a server IP routes through the proxy`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
            .copy(dohCustomServerIp = "192.168.6.7")
        router.start()
        assertTrue("the WebView must be pointed at the proxy", override.port != null)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `turning the certificate check off while running swaps in a fresh proxy`() = runBlocking {
        // The setting must reach the resolver, which only happens when the router rebuilds it.
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
        router.start()
        val firstPort = override.port!!

        profile.value = profile.value.copy(dohSkipCertificateCheck = true)
        withTimeout(5_000) { while (override.port == null || override.port == firstPort) delay(20) }
    }

    @Test
    fun `a settings change while running swaps in a fresh proxy with fresh credentials`() = runBlocking {
        profile.value = doh()
        router.start()
        val firstPort = override.port!!
        val firstRealm = realmOf(firstPort)

        profile.value = doh(provider = DohPresets.CLOUDFLARE.id)
        withTimeout(5_000) { while (override.port == null || override.port == firstPort) delay(20) }

        assertNotEquals(firstRealm, realmOf(override.port!!))
        // The old proxy is gone; its credentials stay answerable only so challenges already in
        // flight during the swap do not fail a page load, and they open nothing.
        assertTrue("the old proxy must be stopped", runCatching { Socket("127.0.0.1", firstPort).close() }.isFailure)
        // #333: the WebView moves straight onto the new proxy. Clearing first left a window in
        // which pages were looked up on the system resolver.
        assertEquals(listOf("set", "set"), override.calls)
    }

    @Test
    fun `a swap the WebView refuses falls back to direct connections and stops both proxies`() = runBlocking {
        profile.value = doh()
        router.start()
        val firstPort = override.port!!

        override.accept = false
        profile.value = doh(provider = DohPresets.CLOUDFLARE.id)
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }

        assertEquals(listOf("set", "set", "clear"), override.calls)
        assertTrue("the old proxy goes once the clear confirms", runCatching { Socket("127.0.0.1", firstPort).close() }.isFailure)
        assertNull("fail-open: nothing blocks browsing", router.browsingBlockedReason())
    }

    // --- Strict mode (#333) ---

    /** A DoH URL nothing answers at, so every lookup through it fails. Cleartext, so no TLS to wait on. */
    private fun deadResolver(strict: Boolean, routeNoise: Boolean = false): PoisonProfile {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        return doh(provider = DohPresets.CUSTOM_ID, url = "http://127.0.0.1:$port/dns-query")
            .copy(dnsStrict = strict, routeDnsNoise = routeNoise)
    }

    @Test
    fun `without strict mode a failing resolver falls back to the system resolver`() = runBlocking {
        profile.value = deadResolver(strict = false, routeNoise = true)
        router.start()
        assertTrue(router.noiseResolver()!!.resolve("localhost") is com.fauxx.network.dns.Resolution.Addresses)
        assertNull(router.browsingBlockedReason())
    }

    @Test
    fun `strict mode never sends a lookup to the system resolver`() = runBlocking {
        profile.value = deadResolver(strict = true)
        router.start()
        assertNotNull("the WebView is routed", override.port)
        assertNull(router.browsingBlockedReason())
        // localhost is one name the system resolver always answers, so a Failure means it was not asked.
        val noise = router.noiseResolver()!!
        repeat(4) { assertTrue(noise.resolve("localhost") is com.fauxx.network.dns.Resolution.Failure) }
        // Health reaches the router through a collector, so wait for it rather than race it.
        val health = withTimeout(5_000) {
            var h = router.health.value
            while (h !is DnsHealth.Degraded) { delay(20); h = router.health.value }
            h
        }
        assertTrue("degraded, and saying lookups fail", health.blocked)
    }

    @Test
    fun `strict mode sends DNS noise to the custom resolver even when routing noise is off`() = runBlocking {
        profile.value = doh().copy(dnsStrict = true, routeDnsNoise = false)
        router.start()
        assertNotNull(router.noiseResolver())
    }

    @Test
    fun `strict mode with a WebView that cannot be routed blocks browsing instead of going direct`() = runBlocking {
        override.supported = false
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        assertNotNull(router.browsingBlockedReason())
        val health = router.health.value
        assertTrue(health is DnsHealth.Degraded && health.blocked)
        assertTrue(
            "noise must not reach the system resolver either",
            router.noiseResolver()!!.resolve("localhost") is com.fauxx.network.dns.Resolution.Failure,
        )
    }

    @Test
    fun `strict mode with a WebView that refuses the override blocks browsing and never clears`() = runBlocking {
        override.accept = false
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        assertNotNull(router.browsingBlockedReason())
        assertTrue("strict mode must not restore direct connections", "clear" !in override.calls)
        val health = router.health.value
        assertTrue(health is DnsHealth.Degraded && health.blocked && health.cause == DegradeCause.ROUTING)
    }

    @Test
    fun `strict mode keeps both proxies when a swap is refused, and a retry brings routing back`() = runBlocking {
        router.retryDelaysMs = listOf(100L)
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        val firstPort = override.port!!
        val first = made.toList().single { (p, _) -> p.port == firstPort }.first

        override.accept = false // a 5 s confirmation timeout looks exactly like this
        profile.value = doh(provider = DohPresets.CLOUDFLARE.id).copy(dnsStrict = true)
        withTimeout(5_000) { while (router.browsingBlockedReason() == null) delay(20) }

        assertTrue("never cleared: pages in flight stay on a strict proxy", "clear" !in override.calls)
        assertTrue("the WebView may still point at the old proxy, so it keeps serving", isServing(firstPort))
        assertNotNull("and its challenge is still answered", router.credentialsFor("127.0.0.1", realmOf(firstPort)))

        override.accept = true
        withTimeout(5_000) { while (router.browsingBlockedReason() != null) delay(20) }
        assertTrue("the retry put a fresh proxy in place", override.port != firstPort)
        // Checked on the proxy itself: once its port is free, any other listener in the test JVM
        // can be handed the same number, so probing the port could see a stranger answer.
        assertTrue("and only then retired the old one", first.awaitWorkersStopped(5_000))
        assertTrue("clear" !in override.calls)
    }

    @Test
    fun `an override call that throws is a refusal, not a crash`() = runBlocking {
        override.throwOnSet = true
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        assertNotNull(router.browsingBlockedReason())
        assertTrue("clear" !in override.calls)
    }

    @Test
    fun `strict mode retries a refused override with backoff until it is accepted`() = runBlocking {
        router.retryDelaysMs = listOf(100L)
        override.accept = false
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        assertNotNull(router.browsingBlockedReason())

        withTimeout(5_000) { while (override.calls.count { it == "set" } < 3) delay(20) }
        override.accept = true
        withTimeout(5_000) { while (router.browsingBlockedReason() != null) delay(20) }
        assertNotNull(override.port)
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Healthy) delay(20) }
    }

    @Test
    fun `fail-open mode also retries, instead of staying direct until a settings change`() = runBlocking {
        router.retryDelaysMs = listOf(100L)
        override.accept = false
        profile.value = doh()
        router.start()
        assertNull("fail-open never blocks browsing", router.browsingBlockedReason())

        override.accept = true
        withTimeout(5_000) { while (override.port == null) delay(20) }
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Healthy) delay(20) }
    }

    @Test
    fun `stop cancels a pending retry`() = runBlocking {
        // Long enough that the retry cannot fire before stop() runs.
        router.retryDelaysMs = listOf(60_000L)
        override.accept = false
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        assertTrue("the refused set() scheduled a retry", router.retryPending())
        router.stop()
        assertFalse("stop() cancelled it", router.retryPending())
    }

    @Test
    fun `turning strict mode on closes the gate and makes the serving proxy strict before the swap confirms`() = runBlocking {
        profile.value = deadResolver(strict = false, routeNoise = true)
        router.start()
        val oldNoise = router.noiseResolver()!!
        assertTrue("fail-open: falls back", oldNoise.resolve("localhost") is com.fauxx.network.dns.Resolution.Addresses)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override.setGate = gate
        profile.value = profile.value.copy(dnsStrict = true)
        withTimeout(5_000) { while (override.calls.count { it == "set" } < 2) delay(20) }

        // The new set() is still pending: the old proxy is what pages in flight use.
        assertNotNull("nothing new is browsed until the strict proxy is confirmed", router.browsingBlockedReason())
        assertTrue(
            "the old proxy's resolver no longer falls back",
            oldNoise.resolve("localhost") is com.fauxx.network.dns.Resolution.Failure,
        )
        gate.complete(Unit)
        withTimeout(5_000) { while (router.browsingBlockedReason() != null) delay(20) }
    }

    // --- A strict proxy that dies, and how many proxies are kept (second review) ---

    /** Start strict DoH, then make its proxy die. Returns the dead proxy's port. */
    private suspend fun strictProxyDies(): Int {
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        val port = override.port!!
        // Wait for the settings collector to apply its first value: with the proxy gone, a later
        // first emission would (rightly) route again at once, hiding the state under test.
        withTimeout(5_000) { while (router.settingsApplied.value < 1) delay(20) }
        made.last().second(java.io.IOException("accept loop died"))
        // The router marks routing lost a moment before it publishes the degraded health; wait
        // for both, or a test can read the health in between and see it still Healthy.
        withTimeout(5_000) {
            while (router.browsingBlockedReason() == null || router.health.value !is DnsHealth.Degraded) delay(20)
        }
        return port
    }

    @Test
    fun `a strict proxy that dies is stopped so its port refuses, and the override is kept`() = runBlocking {
        val port = strictProxyDies()
        assertTrue("connections are refused, not left hanging in a backlog", !isServing(port))
        assertTrue("strict mode keeps the WebView on the dead port", "clear" !in override.calls)
        val health = router.health.value
        assertTrue(health is DnsHealth.Degraded && health.blocked && health.cause == DegradeCause.ROUTING)
    }

    @Test
    fun `after a strict proxy dies, switching to system DNS still clears the override`() = runBlocking {
        strictProxyDies()
        profile.value = PoisonProfile()
        withTimeout(5_000) { while ("clear" !in override.calls || router.health.value != DnsHealth.Off) delay(20) }
        assertNull("the WebView no longer points at the dead port", override.port)
        assertNull(router.browsingBlockedReason())
    }

    @Test
    fun `after a strict proxy dies, stopping the engine still clears the override`() = runBlocking {
        strictProxyDies()
        router.stop()
        assertTrue("clear" in override.calls)
        assertNull(override.port)
    }

    @Test
    fun `after a strict proxy dies, turning strict off with a proxy that will not start still clears`() = runBlocking {
        strictProxyDies()
        failStarts.set(1)
        profile.value = doh() // strict off
        withTimeout(5_000) { while ("clear" !in override.calls) delay(20) }
        assertNull("fail-open: direct, not a dead port", override.port)
        assertNull(router.browsingBlockedReason())
    }

    @Test
    fun `strict retries that keep failing keep at most two proxies alive`() = runBlocking {
        router.retryDelaysMs = listOf(50L)
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        val confirmedPort = override.port!!

        override.accept = false // every set() from here on times out
        profile.value = doh(provider = DohPresets.CLOUDFLARE.id).copy(dnsStrict = true)
        withTimeout(10_000) { while (override.calls.count { it == "set" } < 8) delay(20) }

        assertTrue("built one proxy per attempt", made.size >= 8)
        assertTrue("only the last confirmed and the newest unconfirmed may be targets", servingProxies() <= 2)
        assertTrue("the last confirmed one is kept", isServing(confirmedPort))
    }

    @Test
    fun `a proxy whose set threw is stopped at once, the confirmed one kept`() = runBlocking {
        router.retryDelaysMs = listOf(50L)
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        val confirmedPort = override.port!!

        override.throwOnSet = true // the WebView never received these
        profile.value = doh(provider = DohPresets.CLOUDFLARE.id).copy(dnsStrict = true)
        withTimeout(10_000) { while (override.calls.count { it == "set" } < 5) delay(20) }

        // The last attempt may still be finishing; give it a moment to stop its proxy.
        withTimeout(5_000) { while (servingProxies() > 1) delay(20) }
        assertTrue(isServing(confirmedPort))
    }

    @Test
    fun `turning strict on stops the old proxy's endpoint lookups reaching the system resolver`() = runBlocking {
        val systemCalls = java.util.concurrent.atomic.AtomicInteger(0)
        router.systemResolver = com.fauxx.network.dns.HostResolver { systemCalls.incrementAndGet(); com.fauxx.network.dns.Resolution.NoSuchHost }
        // An http resolver named by a private NAME: its own address comes from the system resolver.
        val deadPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "http://dnsbox.lan:$deadPort/dns-query").copy(routeDnsNoise = true)
        router.start()
        val oldNoise = router.noiseResolver()!!
        oldNoise.resolve("example.com")
        val before = systemCalls.get()
        assertTrue("fail-open: the endpoint name is looked up on the system resolver", before > 0)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override.setGate = gate
        // Strict with a server IP, so the new settings are usable and their set() stays pending.
        profile.value = profile.value.copy(dnsStrict = true, dohCustomServerIp = "127.0.0.1")
        withTimeout(5_000) { while (override.calls.count { it == "set" } < 2) delay(20) }

        oldNoise.resolve("example.org")
        assertEquals("the old proxy no longer asks the system resolver for anything", before, systemCalls.get())
        gate.complete(Unit)
        Unit
    }

    @Test
    fun `strict mode keeps the WebView on a refusing proxy when the settings cannot be used`() = runBlocking {
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        val goodPort = override.port!!

        // An http resolver named by a private NAME needs its IP in strict mode: nothing may look it up.
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "http://pihole.lan/dns-query").copy(dnsStrict = true)
        withTimeout(5_000) { while (override.port == goodPort) delay(20) }
        val health = withTimeout(5_000) {
            var h = router.health.value
            while (!(h is DnsHealth.Degraded && h.cause == DegradeCause.SETTINGS)) { delay(20); h = router.health.value }
            h as DnsHealth.Degraded
        }

        assertTrue("never cleared under a loading page", "clear" !in override.calls)
        // The WebView is routed, but to a proxy that refuses everything: nothing new is browsed, so
        // the engine skips the browsing modules rather than logging a failure every tick.
        assertNotNull(router.browsingBlockedReason())
        assertTrue(router.browsingBlocked())
        assertTrue(health.blocked)
        assertTrue(router.noiseResolver()!!.resolve("localhost") is com.fauxx.network.dns.Resolution.Failure)
    }

    @Test
    fun `switching strict mode off restores fail-open routing`() = runBlocking {
        override.supported = false
        profile.value = doh().copy(dnsStrict = true)
        router.start()
        assertNotNull(router.browsingBlockedReason())

        profile.value = doh().copy(dnsStrict = false)
        withTimeout(5_000) { while (router.browsingBlockedReason() != null) delay(20) }
        assertEquals(DnsHealth.Off, router.health.value)
    }

    // --- http:// resolvers (#334) ---

    @Test
    fun `an http resolver with a public server IP is refused`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "http://pihole.lan/dns-query").copy(dohCustomServerIp = "9.9.9.9")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `an http resolver on the private network routes through the proxy`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "http://pihole.lan/dns-query").copy(dohCustomServerIp = "192.168.1.2")
        router.start()
        assertNotNull(override.port)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `switching back to system DNS clears the override`() = runBlocking {
        profile.value = doh()
        router.start()
        profile.value = PoisonProfile()
        // Wait for the end state, not the first sign of it: the override is cleared inside the
        // teardown, and health turns Off only after it returns. Asserting in between flaked on CI.
        withTimeout(5_000) { while (override.port != null || router.health.value != DnsHealth.Off) delay(20) }
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `a clear that fails keeps the proxy running instead of black-holing the WebView`() = runBlocking {
        profile.value = doh()
        router.start()
        val port = override.port!!
        val realm = realmOf(port)

        override.clears = false
        profile.value = PoisonProfile()
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }

        // The WebView may still point at the old port, so it must still answer and authenticate.
        assertEquals("the stranded proxy must still be serving", realm, realmOf(port))
        assertNotNull(router.credentialsFor("127.0.0.1", realm))

        // A later successful clear finally retires it.
        override.clears = true
        profile.value = doh(provider = DohPresets.CLOUDFLARE.id)
        withTimeout(5_000) { while (override.port == null || override.port == port) delay(20) }
        val refused = runCatching { Socket("127.0.0.1", port).close() }.isFailure
        assertTrue("the stranded proxy must be stopped once a clear succeeds", refused)
    }

    @Test
    fun `an unknown preset id falls back to the default instead of degrading`() = runBlocking {
        profile.value = doh(provider = "a-preset-from-a-future-version")
        router.start()
        assertNotNull(override.port)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `stop completes its teardown even when the caller is cancelled`() = runBlocking {
        profile.value = doh()
        router.start()
        val port = override.port!!
        override.clearDelayMs = 300L
        // Start inside stop() and get stuck in the slow clear, THEN cancel the caller.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { router.stop() }
        job.cancel()
        job.join()
        withTimeout(5_000) { while (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) delay(20) }
        assertNull(override.port)
    }

    private fun plain(server: String = "9.9.9.9", routeNoise: Boolean = false) =
        PoisonProfile(dnsMode = DnsMode.PLAIN, plainDnsServer = server, routeDnsNoise = routeNoise)

    @Test
    fun `plain DNS routes through the proxy like DoH`() = runBlocking {
        profile.value = plain()
        router.start()
        assertNotNull(override.port)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `an invalid plain server degrades instead of routing`() = runBlocking {
        profile.value = plain(server = "dns.example")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `intercepted plain DNS is reported even though answers keep arriving`() = runBlocking {
        intercepted = true
        profile.value = plain()
        router.start()
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }
        assertTrue((router.health.value as DnsHealth.Degraded).intercepted)
        assertNotNull("browsing keeps going: fail-open", override.port)
    }

    @Test
    fun `a network change re-probes, and a clean result clears the interception warning`() = runBlocking {
        intercepted = true
        profile.value = plain()
        router.start()
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }

        // The fake has no replay: an emit before the router subscribes is dropped, and on a slow
        // runner the first probe can finish before that subscription exists. Wait for it.
        withTimeout(5_000) { while (networkChanged.subscriptionCount.value == 0) delay(20) }

        intercepted = false // the user exempted Fauxx in their VPN app, say
        networkChanged.emit(Unit)
        withTimeout(5_000) { while (router.health.value != DnsHealth.Healthy) delay(20) }

        intercepted = true // and later joins a network that hijacks DNS
        networkChanged.emit(Unit)
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }
    }

    @Test
    fun `a WebView without proxy support stays quietly off, with no dashboard line`() = runBlocking {
        override.supported = false
        profile.value = plain()
        router.start()
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `interception is only checked for plain DNS`() = runBlocking {
        intercepted = true
        profile.value = doh()
        router.start()
        delay(300)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `DNS noise uses the custom resolver only when the user turned it on`() = runBlocking {
        profile.value = plain(routeNoise = false)
        router.start()
        assertNull(router.noiseResolver())

        profile.value = plain(routeNoise = true)
        // The toggle is read live; flipping it must not restart the proxy.
        val port = override.port
        withTimeout(5_000) { while (router.noiseResolver() == null) delay(20) }
        assertEquals(port, override.port)

        router.stop()
        assertNull("nothing to route through once stopped", router.noiseResolver())
    }

    // --- Trust on first use for a self-signed custom DoH server (release review) ---

    private val tlsServers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()

    @After
    fun closeTlsServers() = tlsServers.forEach { runCatching { it.shutdown() } }

    /** An HTTPS server with a self-signed certificate. The handshake is all these tests need. */
    private fun selfSignedServer(): Pair<okhttp3.mockwebserver.MockWebServer, okhttp3.tls.HeldCertificate> {
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = okhttp3.mockwebserver.MockWebServer().also { tlsServers += it }
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setResponseCode(503)
        }
        server.start()
        return server to certificate
    }

    // The server IP keeps "localhost" from being looked up over the default preset's DoH, a real
    // network call whose 5 s timeout matches the tests' own and made them flaky on CI.
    private fun selfSigned(server: okhttp3.mockwebserver.MockWebServer, pinnedKey: String = "") = PoisonProfile(
        dnsMode = DnsMode.DOH, dohProvider = DohPresets.CUSTOM_ID,
        dohCustomUrl = "https://localhost:${server.port}/dns-query", dohCustomServerIp = "127.0.0.1",
        dohSkipCertificateCheck = true, dohPinnedKey = pinnedKey, routeDnsNoise = true,
    )

    @Test
    fun `the first key a self-signed server presents is saved, and the resolver rebuilt around it`() = runBlocking {
        val (server, certificate) = selfSignedServer()
        profile.value = selfSigned(server)
        router.start()
        val firstPort = override.port!!

        router.noiseResolver()!!.resolve("example.com") // first contact
        withTimeout(5_000) { while (profile.value.dohPinnedKey.isEmpty()) delay(20) }

        assertEquals(com.fauxx.network.dns.CertificatePin.fingerprint(certificate.certificate), profile.value.dohPinnedKey)
        withTimeout(5_000) { while (override.port == firstPort) delay(20) }
    }

    @Test
    fun `a server whose key is not the pinned one is reported as a changed certificate`() = runBlocking {
        val (server, _) = selfSignedServer()
        val someoneElse = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        profile.value = selfSigned(server, pinnedKey = com.fauxx.network.dns.CertificatePin.fingerprint(someoneElse.certificate))
        router.start()

        router.noiseResolver()!!.resolve("example.com")

        val health = withTimeout(5_000) {
            var h = router.health.value
            while (!(h is DnsHealth.Degraded && h.certificateChanged)) { delay(20); h = router.health.value }
            h
        }
        assertTrue(health is DnsHealth.Degraded && health.certificateChanged)
        assertEquals("a refused key is never saved over the pin", com.fauxx.network.dns.CertificatePin.fingerprint(someoneElse.certificate), profile.value.dohPinnedKey)
    }

    @Test
    fun `a pin is ignored while the certificate check is on`() = runBlocking {
        // A stray stored pin must neither rebuild nor change verification when the switch is off.
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
        router.start()
        val port = override.port!!
        profile.value = profile.value.copy(dohPinnedKey = "stray")
        delay(300)
        assertEquals(port, override.port)
    }
}
