package com.fauxx.engine.webview

import android.webkit.WebViewClient

/**
 * What kind of failure a main-frame load ended in (#353). Decides two things: how the action log
 * describes the failure, and whether the crawler should hold the page back for a while
 * ([com.fauxx.data.crawllist.UrlHealthTracker]).
 */
enum class LoadFailureKind(val label: String) {
    /** The page or the site is gone: HTTP 404/410, or the name does not exist (NXDOMAIN). */
    DEAD("dead page"),

    /** The site refused the visit: a bot check (Cloudflare, DataDome), 401/403/429/451. */
    BOT_WALL("blocked by bot check"),

    /** Probably temporary: a timeout, a reset or refused connection, a TLS failure, a 5xx. */
    TRANSIENT("network error"),

    /**
     * Caused on this device, not by the site: no connection, Fauxx's own DNS proxy, a sinkholed
     * address from a DNS blocker, an app-link redirect Fauxx refused. Never held against the page.
     */
    LOCAL("blocked on this device"),
}

/**
 * One main-frame load failure (#353). [code] is the raw cause, `http(403)` or
 * `net(-2): net::ERR_NAME_NOT_RESOLVED`, the same shape the action log used before kinds existed.
 * [nameNotResolved] marks the one network error that needs a DNS check before it can be called
 * dead, since a DNS blocker answering NXDOMAIN looks exactly like a domain that is gone.
 */
data class PageLoadFailure(
    val kind: LoadFailureKind,
    val code: String,
    val nameNotResolved: Boolean = false,
    val connectionRefused: Boolean = false,
) {
    /** Short, scalar description for the action log: `blocked by bot check, http(403)`. */
    fun describe(): String = "${kind.label}, $code"
}

/**
 * Pure classifier from what WebView reports to a [PageLoadFailure] (#353).
 *
 * The network side keys on the Chromium error NAME in the description (`net::ERR_...`), which
 * WebView passes through unchanged, and falls back to the coarser [WebViewClient] code. The
 * WebView code alone cannot tell NXDOMAIN from "no network" (both are `ERROR_HOST_LOOKUP`).
 */
object LoadFailureClassifier {

    /** Response headers that only a bot-management challenge or block page carries. */
    private val CHALLENGE_HEADERS = setOf("cf-mitigated", "x-datadome")

    fun fromHttp(status: Int, headers: Map<String, String>? = null): PageLoadFailure {
        val challenge = headers?.keys?.any { it.lowercase() in CHALLENGE_HEADERS } == true
        val kind = when {
            challenge -> LoadFailureKind.BOT_WALL
            status == 404 || status == 410 -> LoadFailureKind.DEAD
            // 999: Yahoo's (and LinkedIn's) "request denied" for suspected automation.
            status == 401 || status == 403 || status == 429 || status == 451 || status == 999 -> LoadFailureKind.BOT_WALL
            else -> LoadFailureKind.TRANSIENT
        }
        return PageLoadFailure(kind, "http($status)")
    }

    fun fromNetwork(errorCode: Int, description: String?): PageLoadFailure {
        val name = description.orEmpty()
        val code = "net($errorCode): ${name.ifBlank { "unknown error" }}"
        fun has(error: String) = name.contains(error)
        return when {
            // Safe Browsing reports its block as ERR_BLOCKED_BY_CLIENT with this code. A page
            // Google flags as unsafe is one to stay away from, not a local block.
            errorCode == WebViewClient.ERROR_UNSAFE_RESOURCE -> PageLoadFailure(LoadFailureKind.DEAD, code)
            // The site sends Fauxx to plain http, which the app never loads: it cannot work.
            has("ERR_CLEARTEXT_NOT_PERMITTED") -> PageLoadFailure(LoadFailureKind.DEAD, code)
            has("ERR_NAME_NOT_RESOLVED") ->
                PageLoadFailure(LoadFailureKind.DEAD, code, nameNotResolved = true)
            // The device itself, or Fauxx's own loopback DNS proxy (#227): its CONNECT failures
            // surface as tunnel errors whatever the target's real state, so they say nothing
            // about the page.
            LOCAL_ERRORS.any(::has) -> PageLoadFailure(LoadFailureKind.LOCAL, code)
            has("ERR_CONNECTION_REFUSED") ->
                PageLoadFailure(LoadFailureKind.TRANSIENT, code, connectionRefused = true)
            has("ERR_") -> PageLoadFailure(LoadFailureKind.TRANSIENT, code)
            errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME || errorCode == WebViewClient.ERROR_PROXY_AUTHENTICATION ->
                PageLoadFailure(LoadFailureKind.LOCAL, code)
            else -> PageLoadFailure(LoadFailureKind.TRANSIENT, code)
        }
    }

    /** Fauxx refused a main-frame redirect itself (blocklisted host, or an app link). */
    fun refusedRedirect(): PageLoadFailure = PageLoadFailure(LoadFailureKind.LOCAL, "redirect refused by Fauxx")

    private val LOCAL_ERRORS = listOf(
        "ERR_INTERNET_DISCONNECTED",
        "ERR_NETWORK_CHANGED",
        "ERR_NETWORK_IO_SUSPENDED",
        "ERR_NETWORK_ACCESS_DENIED",
        // 0.0.0.0, `::` and friends: what Pi-hole, NextDNS and AdGuard answer for a blocked name.
        "ERR_ADDRESS_INVALID",
        "ERR_ADDRESS_UNREACHABLE",
        "ERR_PROXY_",
        "ERR_TUNNEL_CONNECTION_FAILED",
        "ERR_BLOCKED_BY_ADMINISTRATOR",
        "ERR_BLOCKED_BY_CLIENT",
        "ERR_UNKNOWN_URL_SCHEME",
        "ERR_DISALLOWED_URL_SCHEME",
    )
}
