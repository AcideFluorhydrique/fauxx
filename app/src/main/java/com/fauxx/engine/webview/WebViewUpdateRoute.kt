package com.fauxx.engine.webview

/**
 * Where the dashboard's WebView warning should send the user (#332).
 *
 * Only Google's WebView builds are on Google Play, and none are on F-Droid's main repository, so
 * a bare `market://` link sent de-Googled users to an F-Droid "No such app" page. Play is used
 * only when both the provider is Google's and Play itself is installed and enabled; everything
 * else gets the provider's system app-info page.
 */
sealed interface WebViewUpdateRoute {

    /** Open [packageName]'s Google Play listing in the Play Store app. */
    data class PlayStore(val packageName: String) : WebViewUpdateRoute

    /** Open the system app-info screen for [packageName], where the user can see its version. */
    data class AppDetails(val packageName: String) : WebViewUpdateRoute

    /** No WebView provider could be read and there is no store to send the user to. */
    data object None : WebViewUpdateRoute

    companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val GOOGLE_WEBVIEW_PACKAGE = "com.google.android.webview"

        /**
         * Pick the route for [providerPackage] (null if unknown), given whether Google Play is
         * installed and enabled.
         */
        fun choose(providerPackage: String?, playInstalled: Boolean): WebViewUpdateRoute = when {
            providerPackage == null ->
                if (playInstalled) PlayStore(GOOGLE_WEBVIEW_PACKAGE) else None
            isOnGooglePlay(providerPackage) && playInstalled -> PlayStore(providerPackage)
            else -> AppDetails(providerPackage)
        }

        /**
         * Google's WebView in all channels (stable, beta, dev, canary) and Chrome, which served as
         * the WebView on Android 7 to 9. All of these are listed on Google Play.
         */
        fun isOnGooglePlay(providerPackage: String): Boolean =
            providerPackage.startsWith(GOOGLE_WEBVIEW_PACKAGE) || isChrome(providerPackage)

        fun isChrome(providerPackage: String): Boolean =
            providerPackage == "com.android.chrome" || providerPackage.startsWith("com.chrome.")
    }
}

/** Which explanation the warning gives next to its [WebViewUpdateRoute]. */
enum class WebViewUpdateHint {
    /** Update it from Google Play, or try its Beta or Dev channel. */
    PLAY,

    /** Google's WebView or Chrome without the Play Store: a Play-compatible store can update it. */
    GOOGLE_WITHOUT_PLAY,

    /** Any other WebView: it usually updates with the system, the ROM, or wherever it came from. */
    OTHER,

    /** The WebView could not be identified, so nothing is asserted about it. */
    UNKNOWN,
}

/**
 * Everything the warning needs to tell the user what to do: the button's [route], the [hint] text
 * to show, and the Google Play app to name, "Chrome" where Chrome is the WebView (Android 8 and 9)
 * and "Android System WebView" otherwise. Product names, so not translated.
 */
data class WebViewUpdateAdvice(
    val route: WebViewUpdateRoute,
    val hint: WebViewUpdateHint,
    val appName: String,
) {
    companion object {
        fun choose(providerPackage: String?, playInstalled: Boolean): WebViewUpdateAdvice {
            val google = providerPackage == null || WebViewUpdateRoute.isOnGooglePlay(providerPackage)
            val hint = when {
                google && playInstalled -> WebViewUpdateHint.PLAY
                providerPackage == null -> WebViewUpdateHint.UNKNOWN
                google -> WebViewUpdateHint.GOOGLE_WITHOUT_PLAY
                else -> WebViewUpdateHint.OTHER
            }
            val appName =
                if (providerPackage != null && WebViewUpdateRoute.isChrome(providerPackage)) "Chrome"
                else "Android System WebView"
            return WebViewUpdateAdvice(WebViewUpdateRoute.choose(providerPackage, playInstalled), hint, appName)
        }
    }
}
