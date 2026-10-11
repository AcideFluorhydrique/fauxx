package com.fauxx.engine.webview

import com.fauxx.engine.webview.WebViewUpdateRoute.AppDetails
import com.fauxx.engine.webview.WebViewUpdateRoute.None
import com.fauxx.engine.webview.WebViewUpdateRoute.PlayStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the dashboard's WebView warning sends the user and what it tells them (#332). A bare
 * `market://` link reached F-Droid on de-Googled phones, which carries no WebView and answered
 * "No such app".
 */
class WebViewUpdateRouteTest {

    private fun advice(pkg: String?, play: Boolean) = WebViewUpdateAdvice.choose(pkg, playInstalled = play)

    @Test
    fun `Google's WebView with Play installed opens its Play listing`() {
        assertEquals(
            WebViewUpdateAdvice(PlayStore("com.google.android.webview"), WebViewUpdateHint.PLAY, "Android System WebView"),
            advice("com.google.android.webview", play = true)
        )
    }

    @Test
    fun `Google's other WebView channels also go to Play`() {
        val found = advice("com.google.android.webview.beta", play = true)
        assertEquals(PlayStore("com.google.android.webview.beta"), found.route)
        assertEquals(WebViewUpdateHint.PLAY, found.hint)
        assertEquals("Android System WebView", found.appName)
    }

    @Test
    fun `Chrome as the WebView goes to Play and is named as Chrome`() {
        listOf("com.android.chrome", "com.chrome.beta").forEach { pkg ->
            assertEquals(WebViewUpdateAdvice(PlayStore(pkg), WebViewUpdateHint.PLAY, "Chrome"), advice(pkg, play = true))
        }
    }

    @Test
    fun `Google's WebView without Play opens app info and points at a Play-compatible store`() {
        // The F-Droid-only phone from #332 (or microG, or Play disabled): never another store,
        // and never the claim that it updates with the ROM.
        assertEquals(
            WebViewUpdateAdvice(
                AppDetails("com.google.android.webview"),
                WebViewUpdateHint.GOOGLE_WITHOUT_PLAY,
                "Android System WebView"
            ),
            advice("com.google.android.webview", play = false)
        )
        assertEquals(
            WebViewUpdateAdvice(AppDetails("com.android.chrome"), WebViewUpdateHint.GOOGLE_WITHOUT_PLAY, "Chrome"),
            advice("com.android.chrome", play = false)
        )
    }

    @Test
    fun `a ROM's own WebView opens app info even when Play is installed`() {
        listOf("com.android.webview", "app.vanadium.webview").forEach { pkg ->
            listOf(true, false).forEach { play ->
                val found = advice(pkg, play)
                assertEquals(AppDetails(pkg), found.route)
                assertEquals(WebViewUpdateHint.OTHER, found.hint)
            }
        }
    }

    @Test
    fun `an unknown provider falls back to Google's WebView on Play`() {
        assertEquals(
            WebViewUpdateAdvice(PlayStore("com.google.android.webview"), WebViewUpdateHint.PLAY, "Android System WebView"),
            advice(null, play = true)
        )
    }

    @Test
    fun `an unknown provider without Play gets no button and no claims about it`() {
        val found = advice(null, play = false)
        assertEquals(None, found.route)
        assertEquals(WebViewUpdateHint.UNKNOWN, found.hint)
    }
}
