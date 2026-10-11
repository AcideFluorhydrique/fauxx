package com.fauxx.engine.webview

import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics behind the dashboard's WebView warning (#336): they must name the provider and
 * the exact missing features, and a check that throws must read as "missing", not crash.
 */
class WebViewCapabilitiesTest {

    @Test
    fun `a WebView with every feature needs no warning`() {
        val found = WebViewCapabilities.diagnose(
            provider = { "com.google.android.webview" to "151.0.7000.1" },
            check = { true },
        )
        assertEquals("com.google.android.webview", found.providerPackage)
        assertEquals("151.0.7000.1", found.providerVersion)
        assertTrue(found.missingFeatures.isEmpty())
        assertTrue(found.canHidePackageName)
        assertFalse(found.checkFailed)
    }

    @Test
    fun `names exactly the features that are missing`() {
        val found = WebViewCapabilities.diagnose(
            provider = { "com.google.android.webview" to "140.0.1" },
            check = { it != WebViewFeature.CUSTOM_REQUEST_HEADERS },
        )
        assertFalse(found.canHidePackageName)
        assertEquals(listOf(WebViewFeature.CUSTOM_REQUEST_HEADERS), found.missingForPackageNameHiding)
        assertEquals(listOf(WebViewFeature.CUSTOM_REQUEST_HEADERS), found.missingFeatures)
    }

    @Test
    fun `a proxy-only gap does not trigger the package-name warning`() {
        val found = WebViewCapabilities.diagnose(
            provider = { "com.android.webview" to "113.0" },
            check = { it != WebViewFeature.PROXY_OVERRIDE },
        )
        assertTrue(found.canHidePackageName)
        assertEquals(listOf(WebViewFeature.PROXY_OVERRIDE), found.missingFeatures)
    }

    @Test
    fun `a feature check that throws counts as missing and is reported`() {
        val found = WebViewCapabilities.diagnose(
            provider = { "com.android.webview" to "113.0" },
            check = { if (it == WebViewFeature.MULTI_PROFILE) error("provider failed to load") else true },
        )
        assertTrue(found.checkFailed)
        assertFalse(found.canHidePackageName)
        assertEquals(listOf(WebViewFeature.MULTI_PROFILE), found.missingForPackageNameHiding)
    }

    @Test
    fun `a provider lookup that throws leaves the provider unknown`() {
        val found = WebViewCapabilities.diagnose(
            provider = { throw IllegalStateException("no WebView provider") },
            check = { true },
        )
        assertTrue(found.checkFailed)
        assertNull(found.providerPackage)
        assertNull(found.providerVersion)
        // The features themselves answered, so the warning follows them.
        assertTrue(found.canHidePackageName)
    }
}
