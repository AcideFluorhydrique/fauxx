package com.fauxx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the dashboard shows the screen-off guidance cards (#335). */
class BackgroundGuidanceTest {

    @Test
    fun `battery card shows while protection is on and Android may restrict Fauxx`() {
        assertTrue(BackgroundGuidance.showBatteryOptimizationCard(engineEnabled = true, ignoringBatteryOptimizations = false))
    }

    @Test
    fun `battery card hides once exempt or with protection off`() {
        assertFalse(BackgroundGuidance.showBatteryOptimizationCard(engineEnabled = true, ignoringBatteryOptimizations = true))
        assertFalse(BackgroundGuidance.showBatteryOptimizationCard(engineEnabled = false, ignoringBatteryOptimizations = false))
    }

    @Test
    fun `Samsung card shows on Samsung devices whatever the casing of the maker`() {
        assertTrue(BackgroundGuidance.showSamsungSleepCard("samsung", engineEnabled = true, dismissed = false))
        assertTrue(BackgroundGuidance.showSamsungSleepCard("Samsung", engineEnabled = true, dismissed = false))
    }

    @Test
    fun `Samsung card stays away once dismissed, with protection off, and on other makers`() {
        assertFalse(BackgroundGuidance.showSamsungSleepCard("samsung", engineEnabled = true, dismissed = true))
        assertFalse(BackgroundGuidance.showSamsungSleepCard("samsung", engineEnabled = false, dismissed = false))
        assertFalse(BackgroundGuidance.showSamsungSleepCard("Google", engineEnabled = true, dismissed = false))
        assertFalse(BackgroundGuidance.showSamsungSleepCard(null, engineEnabled = true, dismissed = false))
    }
}
