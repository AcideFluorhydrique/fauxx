package com.fauxx.engine.webview

/**
 * The outcome of the LATEST main-frame navigation on one pooled WebView (#353). Thread-safe:
 * navigation starts arrive on WebView's IO thread (shouldInterceptRequest), errors on the main
 * thread, and the module reads the result from the engine's dispatcher.
 *
 * #268 kept the FIRST main-frame error since acquire, and that turned visits that worked into
 * failures. The common case is a bot-management challenge: Cloudflare answers the first request
 * with a 403 "Just a moment..." page (header `cf-mitigated: challenge`), the challenge script
 * passes in a real WebView, and the page navigates again to the real content with a 200. The
 * 403 stayed recorded and the visit was logged as failed. About 10% of a random sample of the
 * English crawl list answers a first visit that way. The same applied to any page that loaded
 * after an earlier main-frame failure in the same visit.
 *
 * Rules:
 * - A main-frame navigation start clears the recorded failure: what the WebView finally shows is
 *   what counts.
 * - Within one navigation the first failure wins; a failed load can report more than once and
 *   the first report describes the cause.
 * - A failure reported before this visit's first navigation started is dropped. It belongs to
 *   the WebView's previous page (callbacks are posted, so one can land after [reset]).
 *
 * Aborted navigations (a page replaced by a redirect, [android.webkit.WebView.stopLoading], a
 * 204) never reach here: WebView does not report ERR_ABORTED to onReceivedError at all
 * (AwContentsClientBridge.onReceivedError in Chromium).
 */
class MainFrameLoadTracker {
    private val lock = Any()
    private var navigationStarted = false
    private var failure: PageLoadFailure? = null

    /** A new visit (acquire), or a new deliberate load on the same visit. */
    fun reset() = synchronized(lock) {
        navigationStarted = false
        failure = null
    }

    /** A main-frame request is about to go out: a new navigation, its outcome not known yet. */
    fun onNavigationStart() = synchronized(lock) {
        navigationStarted = true
        failure = null
    }

    fun onFailure(reported: PageLoadFailure) = synchronized(lock) {
        if (navigationStarted && failure == null) failure = reported
    }

    /** The current navigation's failure, or null if it loaded (or has not failed yet). */
    fun current(): PageLoadFailure? = synchronized(lock) { failure }
}
