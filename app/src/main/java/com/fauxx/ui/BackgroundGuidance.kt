package com.fauxx.ui

/**
 * When the dashboard tells the user what stops Fauxx working with the screen off (#335). Kept
 * apart from the composables so the rules can be unit tested.
 */
object BackgroundGuidance {

    /** Step-by-step Samsung instructions, maintained per One UI version. */
    const val SAMSUNG_GUIDE_URL = "https://dontkillmyapp.com/samsung"

    /**
     * The battery-optimization card. Shown for as long as protection is on and Android may still
     * restrict Fauxx; it has no dismiss, because the restriction is what stops the engine. The
     * one-time dialog only runs when protection is turned on, so declining it leaves this card
     * as the standing reminder rather than a dialog on every launch.
     */
    fun showBatteryOptimizationCard(engineEnabled: Boolean, ignoringBatteryOptimizations: Boolean): Boolean =
        engineEnabled && !ignoringBatteryOptimizations

    /**
     * The Samsung sleeping-apps card. Samsung's own app sleeping runs on top of Android's battery
     * optimization, so a Samsung user needs these steps even after allowing unrestricted use.
     * Shown on Samsung devices while protection is on, until the user dismisses it.
     */
    fun showSamsungSleepCard(manufacturer: String?, engineEnabled: Boolean, dismissed: Boolean): Boolean =
        engineEnabled && !dismissed && manufacturer.equals("samsung", ignoreCase = true)
}
