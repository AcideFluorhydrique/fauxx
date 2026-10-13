package com.fauxx.ui.viewmodels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.PreferenceKeys
import com.fauxx.engine.EngineState
import com.fauxx.engine.IntensityPolicy
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.service.PendingResume
import com.fauxx.service.PendingResumeSource
import com.fauxx.service.PhantomForegroundService
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer2.DriftResult
import com.fauxx.targeting.layer2.DriftState
import com.fauxx.targeting.layer2.ProfileDriftMetric
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer2.SnapshotSeries
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.webview.TrackerBlockKind
import com.fauxx.engine.webview.TrackerBlockSource
import com.fauxx.ui.InstalledApps
import com.fauxx.engine.webview.TrackerBlockVerdict
import com.fauxx.engine.webview.WebViewCapabilities
import com.fauxx.engine.webview.WebViewDiagnostics
import com.fauxx.engine.webview.WebViewUpdateAdvice
import com.fauxx.network.dns.DegradeCause
import com.fauxx.network.dns.DnsHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The quiet custom-DNS dashboard line (#227). */
enum class CustomDnsNotice {
    FALLBACK,

    /** Strict mode (#333): pages are not loading, because the resolver is not answering... */
    BLOCKED_RESOLVER,

    /** ...because Fauxx's WebView cannot be routed through the proxy... */
    BLOCKED_ROUTING,

    /** ...or because the saved settings are incomplete. */
    BLOCKED_SETTINGS,
    INTERCEPTED,
    CERTIFICATE_CHANGED,
}

/**
 * The quiet blocked-trackers dashboard line (#341): something on the phone is stopping Fauxx's
 * requests to trackers, which is where the decoy signal travels. Worded by what will fix it.
 */
enum class TrackerBlockNotice {
    /**
     * A blocker app on the phone: a VPN carries Fauxx's traffic and either declares its own DNS
     * (AdGuard, Rethink, Blokada) or the blocks are at the connection. Excluding Fauxx from it is the
     * fix; Custom DNS is not mentioned, because such apps also block by the name in the TLS hello.
     */
    BLOCKER_APP,

    /** DNS filtering on the phone or network (Pi-hole, NextDNS, Private DNS). Exclude Fauxx, or Custom DNS. */
    NETWORK,

    /**
     * Connections to trackers are cut and no VPN is involved: a router firewall or similar.
     * Custom DNS cannot get around that, so the line does not suggest it.
     */
    NETWORK_CONNECTION,

    /** Custom DNS is working: the resolver the user chose may filter trackers itself. */
    CUSTOM_RESOLVER,

    /**
     * Nothing confirmed, but DuckDuckGo is installed and a VPN covers Fauxx: App Tracking Protection
     * may be blocking. It cannot be confirmed from inside Fauxx (see [com.fauxx.engine.webview.TrackerDnsProbe]),
     * so the line says "may" and can be dismissed.
     */
    DUCKDUCKGO_HINT,
}

/** The dashboard's WebView warning: what was found, and where to send the user (#332, #336). */
data class WebViewWarning(val found: WebViewDiagnostics, val advice: WebViewUpdateAdvice)

data class DashboardUiState(
    val engineEnabled: Boolean = false,
    val engineState: EngineState = EngineState.STOPPED,
    val actionsToday: Int = 0,
    val actionsThisWeek: Int = 0,
    val categoryDistribution: Map<CategoryPool, Float> = emptyMap(),
    val currentPersona: SyntheticPersona? = null,
    val syntheticActionsPerHour: Float = 0f,
    val healthWarnings: List<String> = emptyList(),
    /** Profile-drift (issue #171 E2): KL divergence of the latest import from the baseline. */
    val driftState: DriftState = DriftState.COLLECTING,
    val driftKlDivergence: Double? = null,
    /** Poisoned-vs-control divergence (issue #172 E3): KL of the latest poisoned profile vs the
     *  latest control profile. [hasControlSeries] gates the dashboard card so it appears only once
     *  a control profile has been imported. */
    val controlDivergenceState: DriftState = DriftState.COLLECTING,
    val controlDivergenceKl: Double? = null,
    val hasControlSeries: Boolean = false,
    /** Mobile-data tier (issue #62); gates the "use mobile data" one-tap action — the
     *  button only helps when this is null (mobile off), not when there's no network. */
    val mobileIntensity: IntensityLevel? = null,
    /** The resume a resigned engine is waiting on (#346), set only while the switch is on, the
     *  engine is stopped and the resume is still ahead; the card then reads as paused. */
    val pendingResume: PendingResume? = null,
    /** The level the charging boost (issue #316) is raising the Wi-Fi rate to right now, or null
     *  when it does not apply. Shown on the status card so a change of pace has a visible cause. */
    val chargingBoost: IntensityLevel? = null,
)

/** The profile with the charging boost it yields under the engine's current conditions (#316). */
private data class ProfileBundle(
    val profile: PoisonProfile,
    val chargingBoost: IntensityLevel?,
)

/** Bundles the two snapshot-derived dashboard metrics so combine() keeps a single snapshot flow. */
private data class DriftBundle(
    val drift: DriftResult,
    val control: DriftResult,
    val hasControl: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionLogDao: ActionLogDao,
    private val profileRepo: PoisonProfileRepository,
    private val poisonEngine: PoisonEngine,
    private val targetingEngine: TargetingEngine,
    private val personaLayer: PersonaRotationLayer,
    private val dataStore: DataStore<Preferences>,
    private val profileSnapshotDao: ProfileSnapshotDao,
    private val profileDriftMetric: ProfileDriftMetric,
    private val clock: Clock = SystemClockImpl(),
    private val webViewCapabilities: WebViewCapabilities = WebViewCapabilities.SYSTEM,
    customDns: CustomDns = CustomDns.NONE,
    private val trackerBlocks: TrackerBlockSource = TrackerBlockSource.NONE,
    private val installedApps: InstalledApps = InstalledApps.NONE,
    pendingResumes: PendingResumeSource = PendingResumeSource.NONE,
) : ViewModel() {

    /**
     * The recorded pending resume (#346), dropped once its time has passed: a time-based resume
     * that should already have happened (an alarm the OEM blocked, a start Android denied) is
     * not "back at" anything any more.
     */
    private val pendingResume: Flow<PendingResume?> = pendingResumes.pendingResume.flatMapLatest { pending ->
        flow {
            val remainingMs = pending?.resumeAtMs?.let { it - clock.currentTimeMillis() }
            if (remainingMs == null || remainingMs > 0) emit(pending)
            if (remainingMs != null) {
                if (remainingMs > 0) delay(remainingMs)
                emit(null)
            }
        }
    }

    /**
     * Which quiet custom-DNS line to show, or null for none (#227). [CustomDnsNotice.INTERCEPTED] is
     * worded apart because then Fauxx is NOT on the device's DNS: another app is answering.
     * [CustomDnsNotice.CERTIFICATE_CHANGED] is worded apart because the user may have caused it
     * (a replaced certificate) and has a fix to make. The BLOCKED lines are strict mode (#333):
     * lookups fail instead of falling back, so Fauxx's pages are not loading, which outranks every
     * other line, worded by cause so the user knows what to fix. Shown as one quiet line, per the
     * owner's fail-open decision: browsing continues (unless the user chose strict mode), the user
     * just gets to know.
     */
    val customDnsNotice: StateFlow<CustomDnsNotice?> = customDns.health
        .map { h ->
            when {
                h !is DnsHealth.Degraded -> null
                h.blocked -> when (h.cause) {
                    DegradeCause.RESOLVER -> CustomDnsNotice.BLOCKED_RESOLVER
                    DegradeCause.ROUTING -> CustomDnsNotice.BLOCKED_ROUTING
                    DegradeCause.SETTINGS -> CustomDnsNotice.BLOCKED_SETTINGS
                }
                h.intercepted -> CustomDnsNotice.INTERCEPTED
                h.certificateChanged -> CustomDnsNotice.CERTIFICATE_CHANGED
                else -> CustomDnsNotice.FALLBACK
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _webViewWarning = MutableStateFlow<WebViewWarning?>(null)
    private val webViewLock = Any()
    private var webViewFound: WebViewDiagnostics? = null
    private var playStoreInstalled = false

    /**
     * What the installed WebView lacks, and what the user can do about it, when it cannot stop
     * `X-Requested-With` from naming Fauxx so every site it visits can tell the requests come from
     * the app; null when it can. The dashboard warns (and keeps browsing) and names the WebView and
     * the missing features, since an update does not always add them (#336).
     *
     * The WebView is checked once, off the main thread, because the first feature query can load
     * the WebView provider, which is what hung the main thread in #55; its answer holds for the
     * life of the process (see [WebViewCapabilities]). Whether Google Play is installed can change
     * while Fauxx runs, so [refreshStoreState] re-reads that and the advice follows it (#332).
     * Published straight from the background thread: nothing here has to resume on Main.
     */
    val webViewWarning: StateFlow<WebViewWarning?> = _webViewWarning

    init {
        viewModelScope.launch(Dispatchers.Default) {
            // Play state first, so the card never appears with a route that flips a moment later.
            val play = webViewCapabilities.isPlayStoreInstalled(context)
            synchronized(webViewLock) { playStoreInstalled = play }
            val found = webViewCapabilities.diagnostics(context)
            synchronized(webViewLock) {
                webViewFound = found
                publishWebViewWarning()
            }
        }
    }

    /**
     * Re-read whether Google Play is installed and enabled. The dashboard calls this on resume, so
     * a user who enables or disables Play and comes back gets a button that goes somewhere.
     */
    fun refreshStoreState() {
        viewModelScope.launch(Dispatchers.Default) {
            val play = webViewCapabilities.isPlayStoreInstalled(context)
            synchronized(webViewLock) {
                playStoreInstalled = play
                publishWebViewWarning()
            }
        }
        // #341: DuckDuckGo may have been installed or removed, a VPN switched, meanwhile.
        refreshTrackerChecks()
    }

    private fun refreshTrackerChecks() {
        viewModelScope.launch(Dispatchers.Default) {
            trackerBlocks.refresh()
            _duckDuckGoInstalled.value = installedApps.isInstalled(DUCKDUCKGO_PACKAGE)
        }
    }

    /** Caller holds [webViewLock]. */
    private fun publishWebViewWarning() {
        _webViewWarning.value = webViewFound?.takeUnless { it.canHidePackageName }
            ?.let { WebViewWarning(it, WebViewUpdateAdvice.choose(it.providerPackage, playStoreInstalled)) }
    }

    private val _enabled = MutableStateFlow(profileRepo.getProfile().enabled)

    /** Whether the DuckDuckGo app is installed (#341); read off the main thread, on start and resume. */
    private val _duckDuckGoInstalled = MutableStateFlow(false)

    /**
     * Which quiet blocked-trackers line to show, or null for none (#341). See [trackerNoticeFor]
     * for how it shares the dashboard with the custom-DNS line.
     */
    val trackerBlockNotice: StateFlow<TrackerBlockNotice?> =
        combine(
            _enabled,
            trackerBlocks.verdict,
            customDns.health,
            _duckDuckGoInstalled,
            dataStore.data.map { it[PreferenceKeys.DDG_TRACKER_HINT_DISMISSED] == true },
            ::trackerNoticeFor,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Persists the user's choice to dismiss the DuckDuckGo hint (#341). Stronger lines still show. */
    fun dismissDuckDuckGoHint() {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.DDG_TRACKER_HINT_DISMISSED] = true }
        }
    }

    /** Whether to show the consent dialog (first-time activation). */
    private val _showConsentDialog = MutableStateFlow(false)
    val showConsentDialog: StateFlow<Boolean> = _showConsentDialog

    /**
     * Whether this is a Samsung device whose user has not yet dismissed the sleeping-apps steps
     * (#335). The dashboard also requires protection to be on; see
     * [com.fauxx.ui.BackgroundGuidance.showSamsungSleepCard].
     */
    val samsungSleepGuidanceDismissed: StateFlow<Boolean> = dataStore.data
        .map { it[PreferenceKeys.SAMSUNG_SLEEP_GUIDANCE_DISMISSED] == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Persists the user's choice to dismiss the Samsung sleeping-apps steps. */
    fun dismissSamsungSleepGuidance() {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.SAMSUNG_SLEEP_GUIDANCE_DISMISSED] = true }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        _enabled,
        windowedActionCount(DAY_MS),
        windowedActionCount(WEEK_MS),
        targetingEngine.getWeights(),
        personaLayer.currentPersona,
        poisonEngine.healthWarnings,
        poisonEngine.engineState,
        // Same decision the engine paces by, so the card and the real rate cannot disagree.
        combine(profileRepo.profiles, poisonEngine.rateConditions) { profile, conditions ->
            ProfileBundle(profile, IntensityPolicy.chargingBoost(profile, conditions))
        },
        profileSnapshotDao.observeAll().map { snaps ->
            DriftBundle(
                // E2 profile-drift is the poisoned series only; control snapshots must not skew it.
                drift = profileDriftMetric.compute(snaps.filter { it.series == SnapshotSeries.POISONED }),
                control = profileDriftMetric.computeControlDivergence(snaps),
                hasControl = snaps.any { it.series == SnapshotSeries.CONTROL },
            )
        },
        pendingResume,
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val enabled = flows[0] as Boolean
        val today = flows[1] as Int
        val week = flows[2] as Int
        @Suppress("UNCHECKED_CAST")
        val weights = flows[3] as Map<CategoryPool, Float>
        val persona = flows[4] as SyntheticPersona?
        @Suppress("UNCHECKED_CAST")
        val warnings = flows[5] as List<String>
        val state = flows[6] as EngineState
        val bundle = flows[7] as ProfileBundle
        val profile = bundle.profile
        val drift = flows[8] as DriftBundle
        val pending = flows[9] as PendingResume?
        DashboardUiState(
            engineEnabled = enabled,
            engineState = state,
            actionsToday = today,
            actionsThisWeek = week,
            categoryDistribution = weights,
            currentPersona = persona,
            syntheticActionsPerHour = computeSyntheticActionRate(today),
            healthWarnings = warnings,
            driftState = drift.drift.state,
            driftKlDivergence = drift.drift.klDivergence,
            controlDivergenceState = drift.control.state,
            controlDivergenceKl = drift.control.klDivergence,
            hasControlSeries = drift.hasControl,
            mobileIntensity = profile.mobileIntensity,
            pendingResume = pendingResumeShown(enabled, state, pending),
            chargingBoost = bundle.chargingBoost,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun toggleEngine(enabled: Boolean) {
        if (enabled) {
            // Check if user has accepted consent before first activation
            viewModelScope.launch {
                val prefs = dataStore.data.first()
                val consented = prefs[PreferenceKeys.CONSENT_ACCEPTED] ?: false
                if (!consented) {
                    _showConsentDialog.value = true
                    return@launch
                }
                activateEngine()
            }
        } else {
            deactivateEngine()
        }
    }

    /** User accepted the consent dialog. */
    fun acceptConsent() {
        _showConsentDialog.value = false
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.CONSENT_ACCEPTED] = true }
            activateEngine()
        }
    }

    /** User dismissed the consent dialog. */
    fun dismissConsent() {
        _showConsentDialog.value = false
    }

    /**
     * One-tap opt-in to mobile data, exposed on the Dashboard for a user who sees the
     * engine paused waiting for a network — the Settings control is the same action, but
     * discoverability there is poor (see issue #38). Sets the mobile tier to LOW (issue
     * #62 replaced the old wifi-only toggle with a per-network rate): conservative on
     * data, and adjustable upward in Settings.
     */
    fun enableMobileData() {
        viewModelScope.launch {
            // Never clobber an already-configured tier: the button is only SHOWN when the
            // tier is null, but a stale tap (state changed under the user's finger) must
            // not downgrade a deliberate HIGH/EXTREME mobile choice to LOW.
            profileRepo.updateProfile { it.copy(mobileIntensity = it.mobileIntensity ?: IntensityLevel.LOW) }
        }
    }

    private fun activateEngine() {
        viewModelScope.launch {
            profileRepo.saveProfile(profileRepo.getProfile().copy(enabled = true))
        }
        _enabled.value = true
        context.startForegroundService(PhantomForegroundService.startIntent(context))
    }

    private fun deactivateEngine() {
        viewModelScope.launch {
            profileRepo.saveProfile(profileRepo.getProfile().copy(enabled = false))
        }
        _enabled.value = false
        context.startService(PhantomForegroundService.stopIntent(context))
    }

    private fun computeSyntheticActionRate(actionsToday: Int): Float {
        // Average synthetic actions per hour over the last 24h: an honest measure of how much
        // cover traffic Fauxx adds. NOT a ratio against the user's real traffic, which Android
        // exposes no per-app provenance for (issue #160).
        return actionsToday / 24f
    }

    /**
     * Count of successful actions within a rolling [windowMs] window. The window boundary is
     * recomputed every [WINDOW_REFRESH_MS] via a ticker so it actually SLIDES. Computing the
     * boundary once at flow-construction time froze it, so "today" / "this week" silently
     * widened the longer the dashboard stayed open.
     */
    private fun windowedActionCount(windowMs: Long): Flow<Int> =
        flow {
            while (true) {
                emit(Unit)
                delay(WINDOW_REFRESH_MS)
            }
        }.flatMapLatest {
            actionLogDao.countSince(clock.currentTimeMillis() - windowMs)
        }

    companion object {
        /** DuckDuckGo's Android app, which carries App Tracking Protection (#341). */
        const val DUCKDUCKGO_PACKAGE = "com.duckduckgo.mobile.android"

        /**
         * The blocked-trackers line for the current state (#341), or null.
         *
         * - Only while protection is on: with the engine off nothing is browsing, and an old verdict
         *   would be about traffic that is not happening.
         * - A confirmed block outranks the DuckDuckGo hint. It is hidden while custom DNS is
         *   degraded: that line ([customDnsNotice]) is already showing and names the actual cause.
         * - A VPN carries Fauxx's traffic and either declares its own DNS servers or the blocks are at
         *   the connection: a blocker app, [TrackerBlockNotice.BLOCKER_APP]. A VPN without DNS servers
         *   (DuckDuckGo's) leaves Fauxx's lookups on the underlying network, so a DNS-level block
         *   there is the network's, worded as below.
         * - Custom DNS working: only the chosen resolver is left to blame,
         *   [TrackerBlockNotice.CUSTOM_RESOLVER].
         * - Otherwise [TrackerBlockNotice.NETWORK] for DNS-level blocks, and
         *   [TrackerBlockNotice.NETWORK_CONNECTION] for connection-level ones, which Custom DNS
         *   cannot get around.
         * - Nothing confirmed (or the confirmed line hidden): DuckDuckGo installed and a VPN covering
         *   Fauxx gives the softer [TrackerBlockNotice.DUCKDUCKGO_HINT], unless dismissed.
         */
        internal fun trackerNoticeFor(
            enabled: Boolean,
            verdict: TrackerBlockVerdict,
            health: DnsHealth,
            duckDuckGoInstalled: Boolean,
            duckDuckGoHintDismissed: Boolean,
        ): TrackerBlockNotice? {
            if (!enabled) return null
            val confirmed = if (!verdict.blocked || health is DnsHealth.Degraded) {
                null
            } else when {
                verdict.vpn.active && (verdict.vpn.declaresDns || verdict.kind == TrackerBlockKind.CONNECTION) ->
                    TrackerBlockNotice.BLOCKER_APP
                health is DnsHealth.Healthy -> TrackerBlockNotice.CUSTOM_RESOLVER
                verdict.kind == TrackerBlockKind.CONNECTION -> TrackerBlockNotice.NETWORK_CONNECTION
                else -> TrackerBlockNotice.NETWORK
            }
            return confirmed ?: TrackerBlockNotice.DUCKDUCKGO_HINT.takeIf {
                duckDuckGoInstalled && verdict.vpn.active && !duckDuckGoHintDismissed
            }
        }

        /**
         * The pending resume the protection card shows (#346), or null. Only while the switch is
         * on and the engine is [EngineState.STOPPED]: a running engine reports its own state, and
         * with the switch off a leftover record must not make the card read as paused.
         */
        internal fun pendingResumeShown(
            enabled: Boolean,
            state: EngineState,
            pending: PendingResume?,
        ): PendingResume? = pending.takeIf { enabled && state == EngineState.STOPPED }

        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val WEEK_MS = 7 * DAY_MS
        /** How often the rolling-window boundary is recomputed (so the window slides). */
        private const val WINDOW_REFRESH_MS = 60_000L
    }
}
