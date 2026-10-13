package com.fauxx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.EngineState
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.TrackerBlockSource
import com.fauxx.engine.webview.TrackerBlockKind
import com.fauxx.engine.webview.TrackerBlockVerdict
import com.fauxx.engine.webview.VpnState
import com.fauxx.ui.InstalledApps
import com.fauxx.engine.webview.WebViewCapabilities
import com.fauxx.network.dns.DnsHealth
import com.fauxx.service.PendingResume
import com.fauxx.service.PendingResumeSource
import com.fauxx.ui.viewmodels.TrackerBlockNotice
import com.fauxx.support.FakeClock
import com.fauxx.support.MainDispatcherRule
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.fauxx.ui.viewmodels.DashboardViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the rolling action-count window. The "today" / "this week" counts use
 * `countSince(now - window)`; computing `now` once at flow-construction froze the boundary,
 * so the window silently widened the longer the dashboard stayed open. The boundary must
 * slide as time passes.
 *
 * The test dispatcher is shared with the rule (so viewModelScope and runTest use one
 * scheduler) — otherwise advancing virtual time wouldn't drive the ViewModel's ticker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val context: Context = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile() } returns PoisonProfile()
        // Must actually emit: a relaxed-mock Flow never emits, and combine() in uiState
        // only produces values once ALL source flows have emitted (issue #62 added this).
        every { profiles } returns MutableStateFlow(PoisonProfile())
    }
    private val poisonEngine: PoisonEngine = mockk(relaxed = true) {
        every { healthWarnings } returns MutableStateFlow(emptyList())
        every { engineState } returns MutableStateFlow(EngineState.STOPPED)
    }
    private val targetingEngine: TargetingEngine = mockk(relaxed = true) {
        every { getWeights() } returns MutableStateFlow(emptyMap<CategoryPool, Float>())
    }
    private val personaLayer: PersonaRotationLayer = mockk(relaxed = true) {
        every { currentPersona } returns MutableStateFlow<SyntheticPersona?>(null)
    }
    private val dataStore: DataStore<Preferences> = mockk(relaxed = true) {
        every { data } returns flowOf(emptyPreferences())
    }
    private val actionLogDao: ActionLogDao = mockk(relaxed = true)

    @Test
    fun `rolling action-count window boundary slides as time passes`() = runTest(testDispatcher) {
        val clock = FakeClock(1_700_000_000_000L)
        val boundaries = java.util.Collections.synchronizedList(mutableListOf<Long>())
        every { actionLogDao.countSince(capture(boundaries)) } returns MutableStateFlow(0)

        val vm = DashboardViewModel(
            context, actionLogDao, profileRepo, poisonEngine, targetingEngine,
            personaLayer, dataStore,
            mockk<com.fauxx.targeting.layer2.ProfileSnapshotDao>(relaxed = true) {
                every { observeAll() } returns MutableStateFlow(emptyList<com.fauxx.targeting.layer2.ProfileSnapshot>())
            },
            com.fauxx.targeting.layer2.ProfileDriftMetric(), clock
        )
        val job = launch { vm.uiState.collect {} }
        testScheduler.runCurrent()
        val maxBefore = boundaries.maxOrNull()

        // Advance past the 60s window-refresh interval, moving the clock in lockstep.
        clock.nowMs += 5 * 60_000L
        testScheduler.advanceTimeBy(5 * 60_000L)
        testScheduler.runCurrent()
        val maxAfter = boundaries.maxOrNull()
        job.cancel()

        assertNotNull("countSince must be invoked at least once", maxBefore)
        assertTrue(
            "the rolling-window boundary must advance as time passes (it was frozen at " +
                "construction); boundaries=$boundaries",
            maxAfter != null && maxAfter > maxBefore!!
        )
    }

    private fun newViewModel(
        canHidePackageName: Boolean,
        customDns: com.fauxx.engine.dns.CustomDns = com.fauxx.engine.dns.CustomDns.NONE,
        playInstalled: () -> Boolean = { false },
        trackerBlocks: TrackerBlockSource = TrackerBlockSource.NONE,
        installedApps: InstalledApps = InstalledApps.NONE,
    ) = DashboardViewModel(
        context, actionLogDao, profileRepo, poisonEngine, targetingEngine, personaLayer, dataStore,
        mockk<com.fauxx.targeting.layer2.ProfileSnapshotDao>(relaxed = true) {
            every { observeAll() } returns MutableStateFlow(emptyList<com.fauxx.targeting.layer2.ProfileSnapshot>())
        },
        com.fauxx.targeting.layer2.ProfileDriftMetric(), FakeClock(0L),
        object : WebViewCapabilities {
            override fun canHidePackageName(): Boolean = canHidePackageName
            override fun canUseProxy(): Boolean = true
            override fun isPlayStoreInstalled(context: Context): Boolean = playInstalled()
        },
        customDns,
        trackerBlocks,
        installedApps,
    )

    @Test
    fun `warns when the WebView cannot hide the package name`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        val vm = newViewModel(canHidePackageName = false)
        // The check runs on Dispatchers.Default, a real thread, so wait for it rather than assume.
        val warning = withTimeout(5_000) { vm.webViewWarning.first { it != null } }!!
        // The card names what is missing rather than calling the WebView "too old" (#336).
        assertEquals(WebViewCapabilities.PACKAGE_NAME_HIDING_FEATURES, warning.found.missingForPackageNameHiding)
    }

    @Test
    fun `the warning's button follows the Play Store state on refresh`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        // The WebView answer is fixed for the process; only whether Play is installed can change.
        val play = java.util.concurrent.atomic.AtomicBoolean(false)
        val vm = newViewModel(canHidePackageName = false, playInstalled = { play.get() })
        val before = withTimeout(5_000) { vm.webViewWarning.first { it != null } }!!
        // The fake reports no provider, so without Play there is nowhere to send the user.
        assertEquals(com.fauxx.engine.webview.WebViewUpdateRoute.None, before.advice.route)
        // The user enables Play and comes back to the dashboard.
        play.set(true)
        vm.refreshStoreState()
        val after = withTimeout(5_000) {
            vm.webViewWarning.first { it?.advice?.route is com.fauxx.engine.webview.WebViewUpdateRoute.PlayStore }
        }!!
        assertEquals(before.found, after.found)
    }

    @Test
    fun `stays quiet when the WebView can hide the package name`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        val vm = newViewModel(canHidePackageName = true)
        delay(200)
        assertEquals(null, vm.webViewWarning.value)
    }

    @Test
    fun `the quiet DNS line follows the custom resolver's health`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        val health = MutableStateFlow<com.fauxx.network.dns.DnsHealth>(com.fauxx.network.dns.DnsHealth.Healthy)
        val dns = object : com.fauxx.engine.dns.CustomDns {
            override val health = health
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun noiseResolver(): com.fauxx.network.dns.HostResolver? = null
        }
        val vm = newViewModel(canHidePackageName = true, customDns = dns)
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.customDnsNotice.collect {} }
        assertEquals(null, vm.customDnsNotice.value)
        health.value = com.fauxx.network.dns.DnsHealth.Degraded(0L, "down")
        assertEquals(com.fauxx.ui.viewmodels.CustomDnsNotice.FALLBACK, withTimeout(5_000) { vm.customDnsNotice.first { it != null } })
        // Interception gets its own wording: then Fauxx is NOT on the device's DNS.
        health.value = com.fauxx.network.dns.DnsHealth.Degraded(0L, "intercepted", intercepted = true)
        assertEquals(
            com.fauxx.ui.viewmodels.CustomDnsNotice.INTERCEPTED,
            withTimeout(5_000) { vm.customDnsNotice.first { it == com.fauxx.ui.viewmodels.CustomDnsNotice.INTERCEPTED } },
        )
        // A changed certificate gets its own wording too: the user may have caused it and can fix it.
        health.value = com.fauxx.network.dns.DnsHealth.Degraded(0L, "cert", certificateChanged = true)
        assertEquals(
            com.fauxx.ui.viewmodels.CustomDnsNotice.CERTIFICATE_CHANGED,
            withTimeout(5_000) { vm.customDnsNotice.first { it == com.fauxx.ui.viewmodels.CustomDnsNotice.CERTIFICATE_CHANGED } },
        )
        // Strict mode (#333): lookups fail rather than fall back, so "using your device's DNS" would
        // be false. Blocked outranks everything, and says why.
        val cases = listOf(
            com.fauxx.network.dns.DnsHealth.Degraded(0L, "down", blocked = true) to
                com.fauxx.ui.viewmodels.CustomDnsNotice.BLOCKED_RESOLVER,
            com.fauxx.network.dns.DnsHealth.Degraded(0L, "routing", blocked = true, cause = com.fauxx.network.dns.DegradeCause.ROUTING) to
                com.fauxx.ui.viewmodels.CustomDnsNotice.BLOCKED_ROUTING,
            com.fauxx.network.dns.DnsHealth.Degraded(0L, "settings", blocked = true, cause = com.fauxx.network.dns.DegradeCause.SETTINGS) to
                com.fauxx.ui.viewmodels.CustomDnsNotice.BLOCKED_SETTINGS,
            com.fauxx.network.dns.DnsHealth.Degraded(1L, "cert", certificateChanged = true, blocked = true) to
                com.fauxx.ui.viewmodels.CustomDnsNotice.BLOCKED_RESOLVER,
        )
        for ((h, expected) in cases) {
            health.value = h
            assertEquals(expected, withTimeout(5_000) { vm.customDnsNotice.first { it == expected } })
        }
        job.cancel()
    }

    // --- blocked trackers (#341) -------------------------------------------------------------

    private val noVpn = VpnState.NONE
    private val vpnWithDns = VpnState(active = true, declaresDns = true)
    private val vpnWithoutDns = VpnState(active = true, declaresDns = false)
    private fun blocked(kind: TrackerBlockKind, vpn: VpnState) = TrackerBlockVerdict(true, kind, vpn)
    private fun clear(vpn: VpnState) = TrackerBlockVerdict(false, null, vpn)

    private fun notice(
        verdict: TrackerBlockVerdict,
        health: DnsHealth = DnsHealth.Off,
        enabled: Boolean = true,
        ddgInstalled: Boolean = false,
        ddgDismissed: Boolean = false,
    ) = DashboardViewModel.trackerNoticeFor(enabled, verdict, health, ddgInstalled, ddgDismissed)

    @Test
    fun `the tracker line needs protection on`() {
        assertEquals(null, notice(blocked(TrackerBlockKind.DNS, noVpn), enabled = false))
        assertEquals(null, notice(clear(vpnWithDns), enabled = false, ddgInstalled = true))
        assertEquals(null, notice(TrackerBlockVerdict.CLEAR))
    }

    @Test
    fun `a blocker app when a VPN with its own DNS, or connection-level blocks under a VPN`() {
        assertEquals(TrackerBlockNotice.BLOCKER_APP, notice(blocked(TrackerBlockKind.DNS, vpnWithDns)))
        assertEquals(TrackerBlockNotice.BLOCKER_APP, notice(blocked(TrackerBlockKind.CONNECTION, vpnWithDns)))
        assertEquals(TrackerBlockNotice.BLOCKER_APP, notice(blocked(TrackerBlockKind.CONNECTION, vpnWithoutDns)))
        assertEquals(TrackerBlockNotice.BLOCKER_APP, notice(blocked(TrackerBlockKind.CONNECTION, vpnWithoutDns), DnsHealth.Healthy))
    }

    @Test
    fun `a VPN without DNS servers leaves DNS-level blocks to the network`() {
        // DuckDuckGo-style VPN: Fauxx's lookups go to the underlying network's resolvers.
        assertEquals(TrackerBlockNotice.NETWORK, notice(blocked(TrackerBlockKind.DNS, vpnWithoutDns)))
    }

    @Test
    fun `without a VPN the line follows the kind of block and custom DNS`() {
        assertEquals(TrackerBlockNotice.NETWORK, notice(blocked(TrackerBlockKind.DNS, noVpn)))
        // Custom DNS cannot help with connection-level blocks, so that line does not suggest it.
        assertEquals(TrackerBlockNotice.NETWORK_CONNECTION, notice(blocked(TrackerBlockKind.CONNECTION, noVpn)))
        assertEquals(TrackerBlockNotice.CUSTOM_RESOLVER, notice(blocked(TrackerBlockKind.DNS, noVpn), DnsHealth.Healthy))
        assertEquals(TrackerBlockNotice.CUSTOM_RESOLVER, notice(blocked(TrackerBlockKind.CONNECTION, noVpn), DnsHealth.Healthy))
    }

    @Test
    fun `the confirmed line yields to the custom DNS line`() {
        for (verdict in listOf(blocked(TrackerBlockKind.DNS, noVpn), blocked(TrackerBlockKind.CONNECTION, vpnWithDns))) {
            assertEquals(null, notice(verdict, DnsHealth.Degraded(0L, "down")))
            assertEquals(null, notice(verdict, DnsHealth.Degraded(0L, "down", blocked = true)))
        }
    }

    @Test
    fun `the DuckDuckGo hint needs the app and a VPN, and stays dismissed`() {
        assertEquals(TrackerBlockNotice.DUCKDUCKGO_HINT, notice(clear(vpnWithoutDns), ddgInstalled = true))
        assertEquals(TrackerBlockNotice.DUCKDUCKGO_HINT, notice(clear(vpnWithDns), ddgInstalled = true))
        assertEquals("not installed", null, notice(clear(vpnWithoutDns), ddgInstalled = false))
        assertEquals("no VPN", null, notice(clear(noVpn), ddgInstalled = true))
        assertEquals("dismissed", null, notice(clear(vpnWithoutDns), ddgInstalled = true, ddgDismissed = true))
    }

    @Test
    fun `a confirmed block outranks the DuckDuckGo hint, dismissed or not`() {
        assertEquals(
            TrackerBlockNotice.BLOCKER_APP,
            notice(blocked(TrackerBlockKind.CONNECTION, vpnWithoutDns), ddgInstalled = true),
        )
        assertEquals(
            TrackerBlockNotice.BLOCKER_APP,
            notice(blocked(TrackerBlockKind.CONNECTION, vpnWithoutDns), ddgInstalled = true, ddgDismissed = true),
        )
    }

    @Test
    fun `the tracker line follows the verdict while protection is on`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        every { profileRepo.getProfile() } returns PoisonProfile(enabled = true)
        val verdicts = MutableStateFlow(TrackerBlockVerdict.CLEAR)
        val refreshed = java.util.concurrent.atomic.AtomicInteger(0)
        val source = object : TrackerBlockSource {
            override val verdict = verdicts
            override suspend fun refresh() { refreshed.incrementAndGet() }
        }
        val vm = newViewModel(canHidePackageName = true, trackerBlocks = source, installedApps = { it == DashboardViewModel.DUCKDUCKGO_PACKAGE })
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.trackerBlockNotice.collect {} }
        try {
            assertEquals(null, vm.trackerBlockNotice.value)
            verdicts.value = blocked(TrackerBlockKind.DNS, vpnWithDns)
            assertEquals(
                TrackerBlockNotice.BLOCKER_APP,
                withTimeout(5_000) { vm.trackerBlockNotice.first { it != null } },
            )
            // Not blocked any more, but a VPN is on: the hint needs DuckDuckGo seen, which resume checks.
            verdicts.value = clear(vpnWithoutDns)
            assertEquals(null, withTimeout(5_000) { vm.trackerBlockNotice.first { it == null } })
            vm.refreshStoreState()
            assertEquals(
                TrackerBlockNotice.DUCKDUCKGO_HINT,
                withTimeout(5_000) { vm.trackerBlockNotice.first { it == TrackerBlockNotice.DUCKDUCKGO_HINT } },
            )
            assertTrue("resume also refreshes the tracker checks", refreshed.get() > 0)
        } finally {
            job.cancel()
        }
    }

    // --- resigned engine, resume pending (#346) ----------------------------------------------

    @Test
    fun `the card shows a pending resume only while switched on and stopped`() {
        val pending = PendingResume(EngineState.PAUSED_QUIET_HOURS, 1_000L)
        assertEquals(pending, DashboardViewModel.pendingResumeShown(true, EngineState.STOPPED, pending))
        // A running engine reports its own state, paused or not.
        assertEquals(null, DashboardViewModel.pendingResumeShown(true, EngineState.ACTIVE, pending))
        assertEquals(null, DashboardViewModel.pendingResumeShown(true, EngineState.PAUSED_QUIET_HOURS, pending))
        // With the switch off a leftover record must not make the card read as paused.
        assertEquals(null, DashboardViewModel.pendingResumeShown(false, EngineState.STOPPED, pending))
        assertEquals(null, DashboardViewModel.pendingResumeShown(true, EngineState.STOPPED, null))
    }

    private fun pendingResumeViewModel(clock: FakeClock, pending: MutableStateFlow<PendingResume?>) =
        DashboardViewModel(
            context, actionLogDao, profileRepo, poisonEngine, targetingEngine, personaLayer, dataStore,
            mockk<com.fauxx.targeting.layer2.ProfileSnapshotDao>(relaxed = true) {
                every { observeAll() } returns MutableStateFlow(emptyList<com.fauxx.targeting.layer2.ProfileSnapshot>())
            },
            com.fauxx.targeting.layer2.ProfileDriftMetric(), clock,
            pendingResumes = object : PendingResumeSource {
                override val pendingResume = pending
            },
        )

    @Test
    fun `quiet hours after the engine resigned read as paused until the resume time`() = runTest(testDispatcher) {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        every { profileRepo.getProfile() } returns PoisonProfile(enabled = true)
        val clock = FakeClock(1_700_000_000_000L)
        val resumeAt = clock.nowMs + 8 * 60 * 60_000L
        val pending = MutableStateFlow<PendingResume?>(PendingResume(EngineState.PAUSED_QUIET_HOURS, resumeAt))
        val vm = pendingResumeViewModel(clock, pending)
        val job = launch { vm.uiState.collect {} }
        testScheduler.runCurrent()

        assertEquals(PendingResume(EngineState.PAUSED_QUIET_HOURS, resumeAt), vm.uiState.value.pendingResume)

        // The engine comes back and takes over from the resume, which retires the record.
        pending.value = null
        testScheduler.runCurrent()
        assertEquals(null, vm.uiState.value.pendingResume)
        job.cancel()
    }

    @Test
    fun `a constraint resume has no time and stays until it is retired`() = runTest(testDispatcher) {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        every { profileRepo.getProfile() } returns PoisonProfile(enabled = true)
        val clock = FakeClock(1_700_000_000_000L)
        val pending = MutableStateFlow<PendingResume?>(PendingResume(EngineState.PAUSED_WIFI, null))
        val vm = pendingResumeViewModel(clock, pending)
        val job = launch { vm.uiState.collect {} }
        testScheduler.runCurrent()

        clock.nowMs += 24 * 60 * 60_000L
        testScheduler.advanceTimeBy(24 * 60 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(PendingResume(EngineState.PAUSED_WIFI, null), vm.uiState.value.pendingResume)
        job.cancel()
    }

    @Test
    fun `a time-based resume that is overdue no longer reads as paused`() = runTest(testDispatcher) {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        every { profileRepo.getProfile() } returns PoisonProfile(enabled = true)
        val clock = FakeClock(1_700_000_000_000L)
        // A loop-failure restart one minute out (#197).
        val resumeAt = clock.nowMs + 60_000L
        val pending = MutableStateFlow<PendingResume?>(PendingResume(EngineState.STOPPED, resumeAt))
        val vm = pendingResumeViewModel(clock, pending)
        val job = launch { vm.uiState.collect {} }
        testScheduler.runCurrent()
        assertEquals(PendingResume(EngineState.STOPPED, resumeAt), vm.uiState.value.pendingResume)

        // The time passes with the engine still off (an alarm the OEM blocked): no "back at" any more.
        clock.nowMs += 61_000L
        testScheduler.advanceTimeBy(61_000L)
        testScheduler.runCurrent()
        assertEquals(null, vm.uiState.value.pendingResume)

        // A record whose time has already passed is never shown at all.
        pending.value = PendingResume(EngineState.PAUSED_QUIET_HOURS, clock.nowMs - 1)
        testScheduler.runCurrent()
        assertEquals(null, vm.uiState.value.pendingResume)
        job.cancel()
    }
}
