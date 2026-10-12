package com.fauxx.engine.dns

import androidx.annotation.VisibleForTesting
import com.fauxx.data.model.DnsMode
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomBrowsingGate
import com.fauxx.engine.webview.PhantomProxyAuth
import com.fauxx.engine.webview.WebViewProxyOverride
import com.fauxx.network.dns.AddressScope
import com.fauxx.network.dns.CachingResolver
import com.fauxx.network.dns.CertificatePin
import com.fauxx.network.dns.CleartextDohResolver
import com.fauxx.network.dns.DegradeCause
import com.fauxx.network.dns.DnsInterceptionCheck
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.DohHostResolver
import com.fauxx.network.dns.DohPresets
import com.fauxx.network.dns.FailOpenResolver
import com.fauxx.network.dns.HostResolver
import com.fauxx.network.dns.LoopbackProxy
import com.fauxx.network.dns.PlainDnsResolver
import com.fauxx.network.dns.PlainDnsServer
import com.fauxx.network.dns.ProxyCredentials
import com.fauxx.network.dns.Resolution
import com.fauxx.network.dns.StrictDnsException
import com.fauxx.network.dns.SystemHostResolver
import com.fauxx.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** The engine's view of custom DNS (#227): start and stop with it, and report how it is doing. */
interface CustomDns {
    val health: StateFlow<DnsHealth>

    /** Route per the current settings. Returns once the WebView override is in effect, or not needed. */
    suspend fun start()

    /** Stop routing: restore direct WebView connections first, then stop the proxy. */
    suspend fun stop()

    /**
     * The resolver the DNS-noise module should use, or null for the device's own. Non-null while
     * custom DNS is active AND the user turned on "also use it for DNS noise", and always in strict
     * mode (#333), where the device's own is off limits.
     */
    fun noiseResolver(): HostResolver?

    /**
     * True while strict mode (#333) cannot route the WebView through Fauxx's proxy, so nothing may
     * browse. The engine leaves the browsing modules out of dispatch meanwhile.
     */
    fun browsingBlocked(): Boolean = false

    /**
     * Whether Fauxx may make its own lookups through the device's resolver. False in strict mode
     * (#333), where nothing Fauxx does may touch the device's DNS; the blocked-tracker check (#341)
     * skips its probe then.
     */
    fun systemLookupsAllowed(): Boolean = true

    companion object {
        /** Custom DNS absent entirely; used by engine tests. */
        val NONE: CustomDns = object : CustomDns {
            override val health: StateFlow<DnsHealth> = MutableStateFlow(DnsHealth.Off)
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun noiseResolver(): HostResolver? = null
        }
    }
}

/**
 * Whether plain DNS on this network reaches the server the user named (#227, PR 2). An interface
 * so the router can be tested without real network probes.
 */
fun interface DnsInterceptionProbe {
    fun isIntercepted(): Boolean

    companion object {
        val SYSTEM = DnsInterceptionProbe { DnsInterceptionCheck.isIntercepted() }
    }
}

/**
 * Emits whenever the device's default network changes (#227, PR 2). Interception is a property of
 * the network, not of the setting: joining a hijacking Wi-Fi or switching a VPN on or off changes
 * it, so the plain-DNS probe re-runs on every change instead of once per proxy start.
 */
interface NetworkChanges {
    val changes: Flow<Unit>

    companion object {
        val NONE: NetworkChanges = object : NetworkChanges {
            override val changes: Flow<Unit> = emptyFlow()
        }
    }
}

/**
 * Runs the loopback proxy that gives Fauxx's WebView traffic the user's chosen resolver (#227),
 * and points the WebView at it.
 *
 * Fail-OPEN by default (owner decision): every way this can break ends with browsing continuing
 * on the system resolver and [health] saying so, never with the WebView pointed at a dead port.
 * That covers a dead or slow resolver (the [FailOpenResolver] breaker), a WebView without
 * `PROXY_OVERRIDE`, an override that never confirms, the proxy's accept loop dying under a live
 * override, and an override that cannot be CLEARED: then the old proxy is kept running (it still
 * forwards, falling back to the system resolver) until a later clear succeeds, because stopping it
 * would black-hole every page.
 *
 * Fail-CLOSED in strict mode (#333, the user's opt-in "never fall back to the device's DNS"). The
 * override is NEVER cleared there except to leave strict mode (custom DNS off, strict off, or the
 * engine stopping): a WebView already loading a page would otherwise fetch its subresources,
 * redirects and clicks on the system resolver.
 * - The breaker fails lookups instead of sending them to the system resolver, and so do the custom
 *   endpoint's own name lookups and DNS noise.
 * - Settings that cannot be used put the WebView on a proxy that refuses every connection.
 * - When routing is lost (a proxy that will not start, an override WebView did not confirm, a
 *   proxy that died), every proxy the WebView may still point at is kept, the [PhantomBrowsingGate]
 *   closes so nothing new is browsed, and routing is retried with backoff.
 * - Turning strict mode on makes the proxy still serving pages strict at once, and keeps the gate
 *   closed until its replacement is confirmed.
 * A WebView without `PROXY_OVERRIDE` cannot be routed at all; there the gate stays closed and the
 * dashboard says how to turn strict mode off.
 *
 * Lost routing is retried in both modes ([RETRY_DELAYS_MS]); otherwise one timed-out override would
 * leave strict mode blocked, or fail-open mode direct, until the next settings change.
 *
 * Settings changes while running are picked up live; each change gets a fresh proxy with fresh
 * credentials, and the WebView is moved straight onto it. Clearing first and re-setting after left
 * a window in which pages were looked up on the system resolver (#333).
 *
 * Teardown runs under [NonCancellable]: cancelled half-way, it would leave either a live proxy
 * nobody owns or a WebView override pointing at a stopped one.
 */
@Singleton
class CustomDnsRouter @Inject constructor(
    private val profileRepo: PoisonProfileRepository,
    private val clock: Clock,
    private val override: WebViewProxyOverride,
    private val interceptionProbe: DnsInterceptionProbe,
    private val networkChanges: NetworkChanges,
) : CustomDns, PhantomProxyAuth, PhantomBrowsingGate {

    private data class Settings(
        val mode: DnsMode,
        val provider: String,
        val customUrl: String,
        val plainServer: String,
        val customServerIp: String,
        val customSkipCertificateCheck: Boolean,
        /** Part of the settings so learning the pin rebuilds the resolver around it. */
        val pinnedKey: String,
        val strict: Boolean,
    )

    private class Running(
        val settings: Settings,
        val proxy: LoopbackProxy,
        val credentials: ProxyCredentials,
        /** Uncached: DNS noise exists to generate queries, and a cache would swallow repeats. */
        val uncached: HostResolver,
        /** When the custom DoH server last presented a key other than the pinned one, or null. */
        val certificateChangedSince: MutableStateFlow<Long?>,
        /** The breaker, so turning strict mode on can make this proxy strict at once. Null for REFUSE. */
        val failOpen: FailOpenResolver?,
    ) {
        /** When plain DNS was found intercepted on the current network, or null. */
        val interceptedSince = MutableStateFlow<Long?>(null)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile private var running: Running? = null

    /**
     * Proxies the WebView may still point at, kept alive until an override change confirms: an
     * override that could not be cleared, or in strict mode any proxy whose replacement could not be
     * put in place.
     */
    @Volatile private var stranded: List<Running> = emptyList()

    /** Credentials of the proxy just replaced, still answered while its last challenges drain. */
    @Volatile private var retired: ProxyCredentials? = null

    /** Strict mode is on for a custom resolver (#333); see [browsingBlockedReason]. */
    @Volatile private var strictActive = false

    /** The WebView override is confirmed pointing at the current proxy. */
    @Volatile private var routed = false

    /**
     * The override may point at one of our proxies, dead ones included: set by every set()
     * attempt, cleared only by a confirmed clear(). Tracked apart from [running] and [stranded],
     * which a dead proxy leaves, so a teardown never skips the clear that a WebView pointed at a
     * dead port needs.
     */
    @Volatile private var overrideSet = false

    /**
     * The two proxies the WebView can actually be pointing at. Override changes apply in order on
     * the main thread, so it is the last one a set() confirmed, or the newest one whose set() did
     * not confirm in time and may still apply. Every other kept proxy can be stopped.
     */
    private var lastConfirmed: Running? = null
    private var unconfirmed: Running? = null

    private var active = false
    private var settingsJob: Job? = null
    private var healthJob: Job? = null
    private var probeJob: Job? = null
    private var endpointJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0

    /**
     * The settings last asked for, by [start] or the collector (retries re-apply these). The
     * collector skips a value equal to it: its first value is usually the one [start] just
     * applied, and if that apply lost routing, an immediate second attempt would only pre-empt
     * the backoff retry.
     */
    private var lastRequested: Settings? = null

    /** Backoff for re-applying settings after routing was lost; the last value repeats. */
    @VisibleForTesting
    internal var retryDelaysMs: List<Long> = RETRY_DELAYS_MS

    /**
     * How many values the settings collector has applied. Its first value arrives asynchronously
     * after [start]; tests wait on this instead of sleeping.
     */
    @VisibleForTesting
    internal val settingsApplied = MutableStateFlow(0)

    /** The device's resolver, behind every fail-open path; a seam so tests can see who asks it. */
    @VisibleForTesting
    internal var systemResolver: HostResolver = SystemHostResolver

    /** Builds each proxy; a seam so tests can make one fail to start or die. */
    @VisibleForTesting
    internal var proxyFactory: (HostResolver, ProxyCredentials, (Throwable) -> Unit) -> LoopbackProxy =
        { resolver, credentials, onDied -> LoopbackProxy(resolver, credentials, onDied = onDied) }

    private val _health = MutableStateFlow<DnsHealth>(DnsHealth.Off)
    override val health: StateFlow<DnsHealth> = _health.asStateFlow()

    override suspend fun start() {
        mutex.withLock {
            active = true
            applyLocked(settingsOf(profileRepo.getProfile()))
            settingsJob?.cancel()
            // No drop(1): a change landing between the apply above and this subscription would
            // arrive as the flow's first value, and dropping it would lose the change. The value
            // start() just applied is skipped by comparison instead (see lastRequested).
            settingsJob = scope.launch {
                profileRepo.profiles.map(::settingsOf).distinctUntilChanged().collect { settings ->
                    mutex.withLock { if (active && settings != lastRequested) applyLocked(settings) }
                    settingsApplied.value++
                }
            }
        }
    }

    override suspend fun stop() {
        withContext(NonCancellable) {
            mutex.withLock {
                active = false
                settingsJob?.cancel()
                settingsJob = null
                cancelRetryLocked()
                teardownLocked()
                // Nothing is left to answer for (a stranded proxy keeps its own credentials).
                retired = null
                _health.value = DnsHealth.Off
            }
        }
    }

    override fun noiseResolver(): HostResolver? {
        val current = running
        // Strict means no Fauxx lookup reaches the device's DNS, DNS noise included.
        if (strictActive) return current?.uncached ?: REFUSE
        current ?: return null
        return if (profileRepo.getProfile().routeDnsNoise) current.uncached else null
    }

    override fun browsingBlocked(): Boolean = browsingBlockedReason() != null

    // The saved settings too, not only the live flag: strict mode is applied asynchronously on
    // engine start, and a lookup must not slip out in between (#341).
    override fun systemLookupsAllowed(): Boolean {
        if (strictActive) return false
        val p = profileRepo.getProfile()
        return !(p.dnsStrict && p.dnsMode != DnsMode.SYSTEM)
    }

    override fun credentialsFor(host: String, realm: String): Pair<String, String>? {
        if (host != LoopbackProxy.LOOPBACK) return null
        val creds = (listOfNotNull(running?.credentials, retired) + stranded.map { it.credentials })
            .firstOrNull { it.realm == realm }
        return creds?.let { it.user to it.secret }
    }

    /**
     * Blocked while strict mode cannot route the WebView, and while it routes it to the REFUSE
     * proxy (settings that cannot be used): every page would fail there, so browsing would only
     * fill the action log with failures.
     *
     * NOT while the strict breaker is open. The half-open probe that closes it is driven by
     * lookups, and with DNS noise off, browsing is the only source of them, so blocking would leave
     * the breaker open for good. Those pages fail fast (no network wait), at the normal action rate,
     * and are honest failures, as when a site is down in fail-open mode.
     */
    override fun browsingBlockedReason(): String? = when {
        !strictActive -> null
        !routed -> "Strict DNS is on, but Fauxx's browsing cannot be routed through your resolver"
        running?.failOpen == null -> "Strict DNS is on, but the custom DNS settings cannot be used"
        else -> null
    }

    private suspend fun applyLocked(settings: Settings, fromRetry: Boolean = false) {
        if (!fromRetry) {
            lastRequested = settings
            // A new setting, or a fresh start: any pending retry was for the old one.
            cancelRetryLocked()
            retryAttempt = 0
        }
        if (running?.settings == settings && stranded.isEmpty() && routed) return
        cancelJobsLocked()
        val strict = settings.strict && settings.mode != DnsMode.SYSTEM
        if (strict && running?.settings?.strict != true) {
            // Turning strict on (#333): the proxy still serving pages must not fall back from this
            // moment, and nothing new is browsed until its strict replacement is confirmed.
            routed = false
            (listOfNotNull(running) + stranded).forEach { it.failOpen?.fallsBack = false }
        }
        strictActive = strict
        if (settings.mode == DnsMode.SYSTEM) {
            teardownLocked()
            if (stranded.isEmpty()) _health.value = DnsHealth.Off
            return
        }
        if (!override.isSupported()) {
            teardownLocked()
            if (strict) {
                // Nothing can route the WebView, so nothing may browse (see browsingBlockedReason).
                // Settings keeps the switch reachable so the user can turn strict mode off.
                degrade(UNSUPPORTED_STRICT, DegradeCause.ROUTING)
            } else {
                // The Settings card already says the feature is unavailable on this WebView. A
                // dashboard line the user cannot act on (the switch is disabled) would only nag.
                Timber.w("Custom DNS unavailable: this WebView cannot route through a proxy")
                _health.value = DnsHealth.Off
            }
            return
        }
        val certificateChangedSince = MutableStateFlow<Long?>(null)
        val custom: HostResolver? = when (settings.mode) {
            DnsMode.PLAIN -> PlainDnsServer.parse(settings.plainServer)?.let { PlainDnsResolver(it) }
            else -> dohFor(settings, certificateChangedSince)
        }
        if (custom == null) {
            val reason = invalidReason(settings)
            if (strict) {
                // Keep the WebView on a proxy that refuses everything, rather than clear the
                // override under a page that is still loading.
                if (swapInLocked(settings, REFUSE, REFUSE, certificateChangedSince, failOpen = null) != null) {
                    degrade(reason, DegradeCause.SETTINGS)
                }
            } else {
                teardownLocked()
                degrade(reason, DegradeCause.SETTINGS)
            }
            return
        }
        val failOpen = FailOpenResolver(
            // systemUnlessStrict as well as fallsBack: belt and braces for a proxy kept past a flip.
            custom, systemUnlessStrict, clock,
            // Strict mode fails every lookup while the breaker is open, so it re-probes sooner: a
            // probe costs one lookup's timeout, while the cooldown is a full browsing outage.
            cooldownMs = if (strict) STRICT_COOLDOWN_MS else FAIL_OPEN_COOLDOWN_MS,
            fallsBack = !strict,
        )
        val current = swapInLocked(settings, CachingResolver(failOpen, clock), failOpen, certificateChangedSince, failOpen) ?: return
        _health.value = failOpen.health.value
        healthJob = scope.launch {
            // One place computes health. An interception warning outranks the breaker's view:
            // answers are arriving, just not from the server the user chose, so the breaker would
            // happily report healthy. A changed certificate is reported as such; in strict mode its
            // lookups fail, so it is blocked too.
            combine(failOpen.health, current.interceptedSince, current.certificateChangedSince) { h, since, changed ->
                when {
                    since != null -> DnsHealth.Degraded(since, INTERCEPTED, intercepted = true)
                    changed != null -> DnsHealth.Degraded(changed, CERTIFICATE_CHANGED, certificateChanged = true, blocked = strict)
                    else -> h
                }
            }.collect {
                // A collector still running when teardown cancels it must not overwrite the Off or
                // degraded state teardown just set.
                if (running === current) _health.value = it
            }
        }
        if (settings.mode == DnsMode.PLAIN) {
            probeJob = scope.launch(Dispatchers.IO) {
                // Probe now, and again on every network change; a clean result clears the warning.
                merge(flowOf(Unit), networkChanges.changes).collect {
                    val intercepted = interceptionProbe.isIntercepted()
                    if (running !== current) return@collect
                    if (intercepted) {
                        if (current.interceptedSince.value == null) {
                            Timber.w("Custom DNS degraded: %s", INTERCEPTED)
                            current.interceptedSince.value = clock.currentTimeMillis()
                        }
                    } else {
                        current.interceptedSince.value = null
                    }
                }
            }
        }
        if (custom is CleartextDohResolver) {
            // A private name or range can be a different machine on the next network (#334).
            endpointJob = scope.launch { networkChanges.changes.collect { custom.forgetEndpoint() } }
        }
        Timber.i("Custom DNS active (%s%s) on 127.0.0.1:%d", settings.mode, if (strict) ", strict" else "", current.proxy.port)
    }

    /**
     * Start a fresh proxy around [resolver] and move the WebView straight onto it, then stop the
     * one it replaces. The override is never cleared in between (#333): that was a window in which
     * pages went to the system resolver. Returns the new [Running], or null when it could not be
     * put in place; then [lostRoutingLocked] has handled it.
     */
    private suspend fun swapInLocked(
        settings: Settings,
        resolver: HostResolver,
        uncached: HostResolver,
        certificateChangedSince: MutableStateFlow<Long?>,
        failOpen: FailOpenResolver?,
    ): Running? = withContext(NonCancellable) {
        val credentials = ProxyCredentials.random()
        lateinit var proxy: LoopbackProxy
        proxy = proxyFactory(resolver, credentials) { onProxyDied(proxy) }
        val port = try {
            proxy.start()
        } catch (e: Exception) {
            Timber.w("Could not start the custom DNS proxy (%s)", e.javaClass.simpleName)
            proxy.stop()
            lostRoutingLocked(settings, null, "The custom DNS proxy could not start")
            return@withContext null
        }
        // Publish before pointing the WebView at it, so the auth challenge can always be answered.
        // The proxy being replaced keeps answering its own challenges while they drain.
        val previous = running
        val current = Running(settings, proxy, credentials, uncached, certificateChangedSince, failOpen)
        running = current
        if (previous != null) retired = previous.credentials
        overrideSet = true
        var threw = false
        val applied = try {
            override.set(port)
        } catch (e: Exception) {
            Timber.w("Setting the WebView proxy override failed (%s)", e.javaClass.simpleName)
            threw = true
            false
        }
        if (!applied) {
            // A set() that did not confirm in time may still apply later; one that threw never
            // reached the WebView. Either way the WebView may still point at the previous proxy,
            // so it goes to the teardown too, and only the possible targets are kept.
            if (!threw) unconfirmed = current
            if (previous != null) stranded = stranded + previous
            lostRoutingLocked(settings, current, "The WebView did not accept the proxy")
            return@withContext null
        }
        lastConfirmed = current
        unconfirmed = null
        previous?.proxy?.stop()
        stranded.forEach { if (it !== current) it.proxy.stop() }
        stranded = emptyList()
        retryAttempt = 0
        routed = true
        current
    }

    /**
     * Routing was lost or could not be set up. Fail-open: restore direct connections, as before.
     * Strict: never clear (see the class KDoc); keep only the proxies the WebView may point at,
     * close the gate. Either way, retry with backoff.
     */
    private suspend fun lostRoutingLocked(settings: Settings, current: Running?, reason: String) {
        if (settings.strict && settings.mode != DnsMode.SYSTEM) {
            cancelJobsLocked()
            val candidates = stranded + listOfNotNull(running, current)
            running = null
            keepOnlyTargetsLocked(candidates)
            routed = false
        } else {
            teardownLocked()
        }
        degrade(reason, DegradeCause.ROUTING)
        scheduleRetryLocked()
    }

    /**
     * Keep, of [candidates], only the proxies the WebView can still be pointing at ([lastConfirmed]
     * and [unconfirmed]) and stop the rest. Without this, every failed strict retry would keep one
     * more proxy (its socket, threads and credentials) until a set() finally confirmed.
     */
    private fun keepOnlyTargetsLocked(candidates: List<Running>) {
        val keep = candidates.filter { it === lastConfirmed || it === unconfirmed }.distinct()
        candidates.distinct().filter { c -> keep.none { it === c } }.forEach { it.proxy.stop() }
        stranded = keep
    }

    /** Re-apply the current settings after [retryDelaysMs], while routing is still lost. */
    private fun scheduleRetryLocked() {
        if (!active) return
        retryJob?.cancel()
        val delayMs = retryDelaysMs[minOf(retryAttempt, retryDelaysMs.lastIndex)]
        retryAttempt++
        retryJob = scope.launch {
            delay(delayMs)
            mutex.withLock {
                retryJob = null
                if (!active || routed) return@withLock
                Timber.i("Custom DNS: retrying routing (attempt %d)", retryAttempt)
                applyLocked(settingsOf(profileRepo.getProfile()), fromRetry = true)
            }
        }
    }

    /** Whether a routing retry is scheduled; for tests. */
    @VisibleForTesting
    internal fun retryPending(): Boolean = retryJob?.isActive == true

    private fun cancelRetryLocked() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Clear the override FIRST, then stop the proxies. If the clear does not confirm, they are NOT
     * stopped (the WebView may still be pointing at one) and are kept as [stranded] until a later
     * clear, or a later successful [swapInLocked], retires them. Never cancelled half-way (see the
     * class KDoc).
     */
    private suspend fun teardownLocked() = withContext(NonCancellable) {
        cancelJobsLocked()
        val current = running
        // overrideSet, not just running/stranded: after a strict proxy died, neither holds it, but
        // the WebView still points at its dead port and must be cleared.
        if (current == null && stranded.isEmpty() && !overrideSet) {
            routed = false
            return@withContext
        }
        running = null
        if (current != null) retired = current.credentials
        val cleared = try {
            override.clear()
        } catch (e: Exception) {
            Timber.w("Clearing the WebView proxy override failed (%s)", e.javaClass.simpleName)
            false
        }
        routed = false
        val all = stranded + listOfNotNull(current)
        if (cleared) {
            all.forEach { it.proxy.stop() }
            stranded = emptyList()
            overrideSet = false
            lastConfirmed = null
            unconfirmed = null
        } else {
            keepOnlyTargetsLocked(all)
            degrade("Could not restore direct connections; keeping the proxy running", DegradeCause.ROUTING)
        }
    }

    private fun cancelJobsLocked() {
        healthJob?.cancel()
        healthJob = null
        probeJob?.cancel()
        probeJob = null
        endpointJob?.cancel()
        endpointJob = null
    }

    private fun onProxyDied(proxy: LoopbackProxy) {
        scope.launch {
            mutex.withLock {
                // Only the proxy that died: a late callback must not tear down its replacement.
                val current = running ?: return@withLock
                if (current.proxy !== proxy) return@withLock
                if (current.settings.strict && current.settings.mode != DnsMode.SYSTEM) {
                    // Stop it so its port REFUSES connections (a dead accept loop can leave the
                    // socket listening, where connections would hang in the backlog) and its
                    // tunnels close. The override stays on that port, which fails closed, and
                    // stays recorded in overrideSet so leaving strict mode clears it.
                    cancelJobsLocked()
                    current.proxy.stop()
                    running = null
                    if (lastConfirmed === current) lastConfirmed = null
                    if (unconfirmed === current) unconfirmed = null
                    retired = current.credentials
                    routed = false
                    degrade("The custom DNS proxy stopped", DegradeCause.ROUTING)
                    scheduleRetryLocked()
                } else {
                    lostRoutingLocked(current.settings, null, "The custom DNS proxy stopped")
                }
            }
        }
    }

    /** In strict mode a degraded custom resolver means lookups fail, not fall back (#333). */
    private fun degrade(reason: String, cause: DegradeCause) {
        Timber.w("Custom DNS degraded: %s", reason)
        _health.value = DnsHealth.Degraded(clock.currentTimeMillis(), reason, blocked = strictActive, cause = cause)
    }

    private fun invalidReason(settings: Settings): String = when {
        settings.mode == DnsMode.PLAIN -> "The plain DNS server is not a valid IP address"
        settings.strict && settings.provider == DohPresets.CUSTOM_ID &&
            DohPresets.needsServerIpInStrict(settings.customUrl, settings.customServerIp) ->
            "In strict mode, an http:// resolver needs its server IP address"
        else -> "The custom DNS-over-HTTPS URL or server IP is not valid"
    }

    private fun dohFor(settings: Settings, certificateChangedSince: MutableStateFlow<Long?>): HostResolver? {
        if (settings.provider == DohPresets.CUSTOM_ID) {
            if (!DohPresets.isValidCustomUrl(settings.customUrl, settings.customServerIp)) return null
            // The user may give the server's address, so its hostname is never looked up (a LAN
            // resolver, or one their DNS blocks). Otherwise, or if that address stops answering,
            // the hostname is resolved over the default preset's DoH first: DoH-bypass blocklists
            // on the very Pi-hole or VPN being routed around often block hosts like
            // dns.nextdns.io. The system resolver is the last fallback, never used in strict mode.
            val serverIp = if (settings.customServerIp.isBlank()) {
                null
            } else {
                DohPresets.parseServerIp(settings.customServerIp) ?: return null
            }
            if (DohPresets.isCleartext(settings.customUrl)) return cleartextFor(settings, serverIp)
            val pin = if (settings.customSkipCertificateCheck) {
                CertificatePin(
                    pinned = settings.pinnedKey,
                    onPinned = { key -> rememberPin(settings, key) },
                    onChecked = { matched ->
                        certificateChangedSince.value = if (matched) null else certificateChangedSince.value ?: clock.currentTimeMillis()
                    },
                )
            } else {
                null
            }
            return DohHostResolver(
                settings.customUrl.trim(),
                bootstrap = listOfNotNull(serverIp),
                endpointResolver = if (settings.strict) defaultOnly else defaultThenSystem,
                certificatePin = pin,
            )
        }
        // An id this build does not know (a preset removed in a later version) means the default,
        // not a broken setting.
        val preset = DohPresets.byId(settings.provider) ?: DohPresets.byId(DohPresets.DEFAULT_ID)!!
        // A preset is reached by its built-in IPs; only if every one fails is its name looked up,
        // and in strict mode not on the system resolver.
        return DohHostResolver(
            preset.url, preset.bootstrapAddresses(),
            endpointResolver = if (settings.strict) REFUSE else systemUnlessStrict,
        )
    }

    /**
     * The system resolver for an endpoint's own name, refused from the moment strict mode is on
     * (#333). Read live, not from the settings a resolver was built with, so a fail-open proxy
     * still serving pages while its strict replacement goes in cannot use it either.
     */
    private val systemUnlessStrict = HostResolver { host ->
        if (strictActive) REFUSE.resolve(host) else systemResolver.resolve(host)
    }

    /**
     * An `http://` resolver on the user's own network (#334). Its server IP, if given, must be
     * private too: the URL's host being private means nothing if the connection goes elsewhere. A
     * name is resolved on the system resolver (the default preset cannot know `pihole.lan`, and
     * should not be told it), except in strict mode, where the server IP is required.
     */
    private fun cleartextFor(settings: Settings, serverIp: InetAddress?): HostResolver? {
        if (serverIp != null && !AddressScope.isPrivateNetwork(serverIp)) return null
        if (settings.strict && DohPresets.needsServerIpInStrict(settings.customUrl, settings.customServerIp)) return null
        return CleartextDohResolver(
            settings.customUrl,
            serverAddress = serverIp,
            endpointResolver = if (settings.strict) null else systemUnlessStrict,
        )
    }

    /**
     * Persist a key learned on first use, but only if the settings it was learned under still
     * stand: a URL or switch changed meanwhile means it belongs to nothing any more. Saving it
     * changes [Settings], so the resolver is rebuilt around the stored pin.
     */
    private fun rememberPin(settings: Settings, key: String) {
        scope.launch {
            profileRepo.updateProfile { p ->
                val stillApplies = p.dohSkipCertificateCheck && p.dohPinnedKey.isEmpty() &&
                    p.dohCustomUrl == settings.customUrl && p.dohCustomServerIp == settings.customServerIp
                if (stillApplies) p.copy(dohPinnedKey = key) else p
            }
        }
    }

    private val defaultOnly: HostResolver by lazy {
        val preset = DohPresets.byId(DohPresets.DEFAULT_ID)!!
        DohHostResolver(preset.url, preset.bootstrapAddresses(), endpointResolver = REFUSE)
    }

    private val defaultThenSystem: HostResolver by lazy {
        val preset = DohPresets.byId(DohPresets.DEFAULT_ID)!!
        val viaDefault = DohHostResolver(preset.url, preset.bootstrapAddresses(), endpointResolver = systemUnlessStrict)
        HostResolver { host ->
            viaDefault.resolve(host).takeIf { it is Resolution.Addresses } ?: systemUnlessStrict.resolve(host)
        }
    }

    private fun settingsOf(p: PoisonProfile) = Settings(
        p.dnsMode, p.dohProvider, p.dohCustomUrl, p.plainDnsServer, p.dohCustomServerIp, p.dohSkipCertificateCheck,
        // Only meaningful while the check is skipped; otherwise a stray value must not rebuild.
        if (p.dohSkipCertificateCheck) p.dohPinnedKey else "",
        p.dnsStrict,
    )

    private companion object {
        const val INTERCEPTED = "Plain DNS may be intercepted on this network (a VPN or firewall app?)"
        const val CERTIFICATE_CHANGED = "The DNS server's certificate is not the one first trusted"
        const val UNSUPPORTED_STRICT = "Strict DNS is on, but this WebView cannot route through a proxy"

        /** The fail-open breaker's cooldown: lookups meanwhile go to the system resolver. */
        const val FAIL_OPEN_COOLDOWN_MS = 60_000L

        /**
         * Strict mode's: every lookup fails meanwhile, so recovery should be noticed sooner. 15 s
         * keeps a dead resolver to one probe (at most one lookup's timeout) per 15 s.
         */
        const val STRICT_COOLDOWN_MS = 15_000L

        /** Routing retries: quick at first (a main-thread stall clears fast), then every 5 minutes. */
        val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 60_000L, 300_000L)

        /** Strict mode's answer wherever the system resolver would have been asked (#333). */
        val REFUSE = HostResolver { Resolution.Failure(StrictDnsException()) }
    }
}
