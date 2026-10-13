package com.fauxx.engine

import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rate decision behind per-network intensity (#62) and the charging boost (#316). Every
 * combination the engine and the dashboard rely on, without any Android state.
 */
class IntensityPolicyTest {

    private val lowWithBoost = PoisonProfile(
        intensity = IntensityLevel.LOW,
        mobileIntensity = null,
        chargingIntensity = IntensityLevel.HIGH,
    )

    private fun effective(
        profile: PoisonProfile = lowWithBoost,
        transport: NetworkTransport = NetworkTransport.WIFI,
        networkBlocked: Boolean = false,
        charging: Boolean = true,
        batterySaver: Boolean = false,
    ): IntensityLevel? = IntensityPolicy.effectiveIntensity(profile, transport, networkBlocked, charging, batterySaver)

    @Test
    fun `charging on unmetered Wi-Fi raises the rate to the charging tier`() {
        assertEquals(IntensityLevel.HIGH, effective())
    }

    @Test
    fun `unplugged on Wi-Fi runs the normal Wi-Fi tier`() {
        assertEquals(IntensityLevel.LOW, effective(charging = false))
    }

    @Test
    fun `Off means charging changes nothing`() {
        val profile = lowWithBoost.copy(chargingIntensity = null)
        assertEquals(IntensityLevel.LOW, effective(profile = profile, charging = true))
        assertEquals(IntensityLevel.LOW, effective(profile = profile, charging = false))
    }

    @Test
    fun `charging never turns an Off mobile tier on`() {
        // The #316 commenter: no Fauxx on cellular, even while charging.
        assertNull(effective(transport = NetworkTransport.CELLULAR))
        assertNull(effective(transport = NetworkTransport.METERED_WIFI))
    }

    @Test
    fun `charging never raises a mobile tier`() {
        val profile = lowWithBoost.copy(mobileIntensity = IntensityLevel.LOW)
        assertEquals(IntensityLevel.LOW, effective(profile = profile, transport = NetworkTransport.CELLULAR))
        assertEquals(IntensityLevel.LOW, effective(profile = profile, transport = NetworkTransport.METERED_WIFI))
    }

    @Test
    fun `no network or a blocked network pauses whatever the charging state`() {
        assertNull(effective(transport = NetworkTransport.NONE))
        assertNull(effective(networkBlocked = true))
    }

    @Test
    fun `Battery Saver holds the boost back`() {
        assertEquals(IntensityLevel.LOW, effective(batterySaver = true))
    }

    @Test
    fun `a charging tier at or below the Wi-Fi tier never lowers the rate`() {
        val same = PoisonProfile(intensity = IntensityLevel.HIGH, chargingIntensity = IntensityLevel.HIGH)
        val lower = PoisonProfile(intensity = IntensityLevel.HIGH, chargingIntensity = IntensityLevel.LOW)
        assertEquals(IntensityLevel.HIGH, effective(profile = same))
        assertEquals(IntensityLevel.HIGH, effective(profile = lower))
    }

    @Test
    fun `chargingBoost names the boost only when it actually raises the rate`() {
        fun boost(
            wifi: IntensityLevel = IntensityLevel.LOW,
            chargingTier: IntensityLevel? = IntensityLevel.EXTREME,
            charging: Boolean = true,
            unmetered: Boolean = true,
            batterySaver: Boolean = false,
        ) = IntensityPolicy.chargingBoost(wifi, chargingTier, charging, unmetered, batterySaver)

        assertEquals(IntensityLevel.EXTREME, boost())
        assertNull("Off", boost(chargingTier = null))
        assertNull("unplugged", boost(charging = false))
        assertNull("metered or mobile", boost(unmetered = false))
        assertNull("Battery Saver", boost(batterySaver = true))
        assertNull("not higher", boost(wifi = IntensityLevel.EXTREME))
        assertNull("lower", boost(wifi = IntensityLevel.HIGH, chargingTier = IntensityLevel.MEDIUM))
    }

    @Test
    fun `the conditions overload matches the explicit one`() {
        val on = RateConditions(charging = true, unmetered = true, batterySaver = false)
        assertEquals(IntensityLevel.HIGH, IntensityPolicy.chargingBoost(lowWithBoost, on))
        assertNull(IntensityPolicy.chargingBoost(lowWithBoost, on.copy(charging = false)))
        assertNull(IntensityPolicy.chargingBoost(lowWithBoost, on.copy(unmetered = false)))
        assertNull(IntensityPolicy.chargingBoost(lowWithBoost, on.copy(batterySaver = true)))
        assertNull(IntensityPolicy.chargingBoost(lowWithBoost, RateConditions()))
    }
}
