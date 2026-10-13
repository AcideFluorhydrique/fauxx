package com.fauxx.engine

import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile

/**
 * Which action rate applies right now: the pure decision behind per-network intensity (issue #62)
 * and the charging boost (issue #316). Kept free of Android state so every combination can be
 * unit tested; the engine feeds it the conditions its receivers cached, and the dashboard feeds
 * it the same conditions to say why the rate changed.
 *
 * It only picks a rate. Whether the engine may act at all (quiet hours, the battery-level
 * thresholds, pause on Battery Saver) is decided separately by the constraint check, which runs
 * first, so no rate chosen here can ever override a pause.
 */
internal object IntensityPolicy {

    /**
     * The level the charging boost raises the rate to, or null when it does not apply. It applies
     * only when all of these hold:
     *  - the user chose a [chargingIntensity] (null means Off),
     *  - the device is charging,
     *  - the network is unmetered Wi-Fi or ethernet. Mobile data and metered Wi-Fi keep their own
     *    tier whether or not the phone is plugged in: charging pays for the battery, not for data,
     *  - Battery Saver is off: the user, or the system, asked the phone to save power,
     *  - the chosen level is higher than [wifiIntensity]. A boost never lowers the rate.
     */
    fun chargingBoost(
        wifiIntensity: IntensityLevel,
        chargingIntensity: IntensityLevel?,
        isCharging: Boolean,
        isUnmetered: Boolean,
        batterySaver: Boolean,
    ): IntensityLevel? {
        val boost = chargingIntensity ?: return null
        if (!isCharging || !isUnmetered || batterySaver) return null
        return boost.takeIf { it.actionsPerHour > wifiIntensity.actionsPerHour }
    }

    /** [chargingBoost] for [profile] under [conditions]. */
    fun chargingBoost(profile: PoisonProfile, conditions: RateConditions): IntensityLevel? =
        chargingBoost(
            wifiIntensity = profile.intensity,
            chargingIntensity = profile.chargingIntensity,
            isCharging = conditions.charging,
            isUnmetered = conditions.unmetered,
            batterySaver = conditions.batterySaver,
        )

    /**
     * The action rate for the current network, or null when the engine must pause: no network,
     * Android blocking this app's network (#335), or mobile data or metered Wi-Fi with
     * [PoisonProfile.mobileIntensity] Off (issues #62, #288). On unmetered Wi-Fi the charging
     * boost may raise [PoisonProfile.intensity]; nothing here can raise the mobile tier or turn
     * an Off mobile tier on.
     */
    fun effectiveIntensity(
        profile: PoisonProfile,
        transport: NetworkTransport,
        networkBlocked: Boolean,
        isCharging: Boolean,
        batterySaver: Boolean,
    ): IntensityLevel? {
        if (networkBlocked) return null
        return when (transport) {
            NetworkTransport.WIFI -> chargingBoost(
                wifiIntensity = profile.intensity,
                chargingIntensity = profile.chargingIntensity,
                isCharging = isCharging,
                isUnmetered = true,
                batterySaver = batterySaver,
            ) ?: profile.intensity
            // Metered Wi-Fi spends the user's data allowance, so it is governed by the mobile
            // budget, not the Wi-Fi one (issue #288).
            NetworkTransport.METERED_WIFI, NetworkTransport.CELLULAR -> profile.mobileIntensity
            NetworkTransport.NONE -> null
        }
    }
}

/**
 * The device conditions the charging boost depends on (issue #316), as the engine's receivers
 * last saw them. Published by [PoisonEngine.rateConditions] so the dashboard can show the boost
 * with the same [IntensityPolicy] the engine paces by.
 *
 * @property charging Plugged in (AC, USB or wireless).
 * @property unmetered On unmetered Wi-Fi or ethernet that Android is not blocking for this app.
 * @property batterySaver Android's Battery Saver is on.
 */
data class RateConditions(
    val charging: Boolean = false,
    val unmetered: Boolean = false,
    val batterySaver: Boolean = false,
)
