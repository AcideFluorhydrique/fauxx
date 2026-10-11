package com.fauxx.engine.webview

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import timber.log.Timber

/**
 * What the installed WebView lets Fauxx do, for code that has to act on or explain its limits.
 * Support depends on the installed WebView APK, not the Android version, and a WebView update
 * restarts the app process, so an answer holds for the life of the process: androidx.webkit reads
 * the provider's feature list once, and a provider that failed to load stays failed.
 */
interface WebViewCapabilities {

    /**
     * Whether the pool can stop `X-Requested-With` from naming Fauxx on every request. False means
     * every site Fauxx visits can see the requests come from `com.fauxx.full`.
     */
    fun canHidePackageName(): Boolean

    /** Whether custom DNS (#227) can work: it routes the WebView through a loopback proxy. */
    fun canUseProxy(): Boolean

    /**
     * Which WebView is installed and which of the features Fauxx relies on it lacks, so a warning
     * can name the actual cause instead of guessing "too old" (#336). The default derives the
     * answer from [canHidePackageName] alone, which is enough for fakes.
     */
    fun diagnostics(context: Context): WebViewDiagnostics = WebViewDiagnostics(
        providerPackage = null,
        providerVersion = null,
        missingFeatures = if (canHidePackageName()) emptyList() else PACKAGE_NAME_HIDING_FEATURES,
        checkFailed = false,
    )

    /**
     * Whether the Google Play Store app is installed and enabled, which decides where the warning
     * sends the user (#332). Unlike the WebView answer this can change while Fauxx runs, so the
     * dashboard re-reads it on resume. De-Googled phones often keep Play installed but disabled,
     * which counts as absent. A PackageManager call: keep it off the main thread.
     */
    fun isPlayStoreInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(WebViewUpdateRoute.PLAY_STORE_PACKAGE, 0).enabled
        }.getOrDefault(false)

    companion object {
        /**
         * The pool blanks the header with a per-profile custom header, which needs both features.
         * Measured: WebView 151 has both; WebView 113 (the API 34 AOSP image) has neither. The
         * first stable version to ship them is not known, so nothing here promises one.
         */
        val PACKAGE_NAME_HIDING_FEATURES: List<String> =
            listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.CUSTOM_REQUEST_HEADERS)

        /** The real answer from the installed WebView. */
        val SYSTEM: WebViewCapabilities = object : WebViewCapabilities {
            override fun canHidePackageName(): Boolean = supportsPackageNameHiding()
            override fun canUseProxy(): Boolean = isSupported(WebViewFeature.PROXY_OVERRIDE)
            override fun diagnostics(context: Context): WebViewDiagnostics = diagnose(
                provider = {
                    WebViewCompat.getCurrentWebViewPackage(context)?.let { it.packageName to it.versionName }
                },
                check = { WebViewFeature.isFeatureSupported(it) },
            )
        }

        fun supportsPackageNameHiding(): Boolean = PACKAGE_NAME_HIDING_FEATURES.all { isSupported(it) }

        /** Feature check that answers false instead of throwing where no WebView provider exists (Robolectric). */
        fun isSupported(feature: String): Boolean =
            runCatching { WebViewFeature.isFeatureSupported(feature) }
                .onFailure { Timber.w(it, "WebView feature check for %s failed; treating it as unsupported", feature) }
                .getOrDefault(false)

        /**
         * Build [WebViewDiagnostics] from a [provider] lookup and a per-feature [check]. Either may
         * throw (no provider, a provider that fails to load): that is recorded as [WebViewDiagnostics.checkFailed]
         * and logged, never rethrown. Split out so the failure handling is unit-testable.
         */
        internal fun diagnose(
            provider: () -> Pair<String, String?>?,
            check: (String) -> Boolean,
        ): WebViewDiagnostics {
            var failed = false
            val found = runCatching(provider)
                .onFailure {
                    failed = true
                    Timber.w(it, "Could not read the current WebView provider")
                }
                .getOrNull()
            val missing = (PACKAGE_NAME_HIDING_FEATURES + WebViewFeature.PROXY_OVERRIDE).filterNot { feature ->
                runCatching { check(feature) }
                    .onFailure {
                        failed = true
                        Timber.w(it, "WebView feature check for %s failed; treating it as unsupported", feature)
                    }
                    .getOrDefault(false)
            }
            return WebViewDiagnostics(
                providerPackage = found?.first,
                providerVersion = found?.second,
                missingFeatures = missing,
                checkFailed = failed,
            )
        }
    }
}

/**
 * What [WebViewCapabilities.diagnostics] found.
 *
 * @property providerPackage the WebView provider in use, or null if none could be read.
 * @property providerVersion its versionName, or null if unknown.
 * @property missingFeatures the `WebViewFeature` names Fauxx relies on that it lacks.
 * @property checkFailed true if reading the provider or a feature threw. A feature whose check
 *   threw is listed as missing, because the pool treats it the same way.
 */
data class WebViewDiagnostics(
    val providerPackage: String?,
    val providerVersion: String?,
    val missingFeatures: List<String>,
    val checkFailed: Boolean,
) {
    /** Whether the pool can blank `X-Requested-With`. */
    val canHidePackageName: Boolean
        get() = WebViewCapabilities.PACKAGE_NAME_HIDING_FEATURES.none { it in missingFeatures }

    /** The missing features that stop the package name being hidden, for the dashboard warning. */
    val missingForPackageNameHiding: List<String>
        get() = missingFeatures.filter { it in WebViewCapabilities.PACKAGE_NAME_HIDING_FEATURES }
}
