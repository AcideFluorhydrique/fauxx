package com.fauxx.engine.webview

import android.annotation.SuppressLint
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Points every WebView in the process at Fauxx's loopback proxy, or back at the network (#227).
 * An interface so the routing logic can be unit-tested without a WebView provider.
 */
interface WebViewProxyOverride {
    /** Whether the installed WebView supports a proxy override at all (`PROXY_OVERRIDE`). */
    fun isSupported(): Boolean

    /** Route all WebView traffic through `127.0.0.1:[port]`. True once WebView confirms it applied. */
    suspend fun set(port: Int): Boolean

    /** Restore direct connections. True once WebView confirms it applied. */
    suspend fun clear(): Boolean

    companion object {
        /**
         * The real `ProxyController`. Called on the main thread with a direct executor, the
         * combination spike S1 verified on WebView 113 and 151. The override is process-wide and
         * applies to WebViews that already exist; the callback says when it has taken effect.
         */
        val SYSTEM: WebViewProxyOverride = object : WebViewProxyOverride {
            override fun isSupported(): Boolean = WebViewCapabilities.isSupported(WebViewFeature.PROXY_OVERRIDE)

            // Callers check isSupported() first (CustomDnsRouter), which lint cannot follow.
            @SuppressLint("RequiresFeature")
            override suspend fun set(port: Int): Boolean = awaitApplied { done ->
                val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").build()
                ProxyController.getInstance().setProxyOverride(config, { it.run() }, done)
            }

            @SuppressLint("RequiresFeature")
            override suspend fun clear(): Boolean = awaitApplied { done ->
                ProxyController.getInstance().clearProxyOverride({ it.run() }, done)
            }

            private suspend fun awaitApplied(apply: (Runnable) -> Unit): Boolean =
                withTimeoutOrNull(APPLY_TIMEOUT_MS) {
                    withContext(Dispatchers.Main.immediate) {
                        suspendCancellableCoroutine { cont ->
                            runCatching { apply(Runnable { if (cont.isActive) cont.resume(true) }) }
                                .onFailure { if (cont.isActive) cont.resume(false) }
                        }
                    }
                } ?: false
        }

        private const val APPLY_TIMEOUT_MS = 5_000L
    }
}

/**
 * Whether Fauxx may browse at all right now (#333). Strict custom DNS promises that no lookup for
 * a site Fauxx visits reaches the device's DNS, and the only thing that keeps that promise is the
 * WebView being routed through Fauxx's proxy. When it cannot be (a WebView without
 * `PROXY_OVERRIDE`, a proxy that would not start, an override WebView refused), the pool refuses
 * to hand out a WebView instead of browsing direct.
 */
fun interface PhantomBrowsingGate {
    /** Why browsing is blocked, or null when it may go ahead. */
    fun browsingBlockedReason(): String?

    companion object {
        val NONE = PhantomBrowsingGate { null }
    }
}

/** Thrown by the pool while a [PhantomBrowsingGate] blocks browsing; modules log it as a failed action. */
class BrowsingBlockedException(reason: String) : IllegalStateException(reason)

/**
 * Answers the loopback proxy's password challenge inside [PhantomWebViewClient] (#227). Returns the
 * user/secret pair only for the proxy's own challenge, so a site's HTTP auth never sees them.
 */
interface PhantomProxyAuth {
    fun credentialsFor(host: String, realm: String): Pair<String, String>?

    companion object {
        /** No proxy in use: every challenge is cancelled, WebView's default behaviour. */
        val NONE: PhantomProxyAuth = object : PhantomProxyAuth {
            override fun credentialsFor(host: String, realm: String): Pair<String, String>? = null
        }
    }
}
