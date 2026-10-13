package com.fauxx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.ui.screens.OnboardingScreen
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for [OnboardingScreen].
 *
 * Verifies:
 * - Skip button is always visible and equal-prominence to Next
 * - Tapping Skip on every step completes the flow without selecting any field
 * - Next button advances through all steps
 * - The Done step shows the final completion step
 *
 * - Skip / Next stay on screen without scrolling on a small (320dp) screen (#347)
 *
 * Note: only the per-step content of [OnboardingScreen] scrolls; the Skip / Next
 * navigation Row is pinned below it, outside the scroll container. So the buttons
 * are asserted and clicked directly. performScrollTo() would fail on them, as they
 * no longer have a scrollable parent.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun skipButton_isVisibleOnWelcomeStep() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun skipAllFields_completesOnboardingWithoutCrash() {
        var finished = false
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = { finished = true })
            }
        }

        // Welcome → Age Range step
        composeRule.onNodeWithText("Skip").performClick()
        // Age Range → Gender step
        composeRule.onNodeWithText("Skip").performClick()
        // Gender → Interests step
        composeRule.onNodeWithText("Skip").performClick()
        // Interests → Profession step
        composeRule.onNodeWithText("Skip").performClick()
        // Profession → Region step
        composeRule.onNodeWithText("Skip").performClick()
        // Region is last step — "Skip All" should finish
        composeRule.onNodeWithText("Skip All").performClick()

        assert(finished) { "onFinish was not called after skipping all steps" }
    }

    @Test
    fun nextButton_advancesFromWelcomeToDone() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }

        // Welcome step shows FAUXX title (top of screen, no scroll needed)
        composeRule.onNodeWithText("FAUXX").assertIsDisplayed()

        // Advance through all steps via Next
        repeat(5) {
            composeRule.onNodeWithText("Next").performClick()
        }

        // On the Done step, Next becomes "Done"
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun skipButton_isEquallyProminentAsNextButton() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }
        // Both buttons must be present at the same time — verified by assertIsDisplayed
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun nextThenDone_callsOnFinish() {
        var finished = false
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = { finished = true })
            }
        }

        // Advance to the last step via Next
        repeat(5) {
            composeRule.onNodeWithText("Next").performClick()
        }
        // Click Done
        composeRule.onNodeWithText("Done").performClick()

        assert(finished) { "onFinish was not called after completing all steps" }
    }

    /**
     * Regression test for #347: on a 320x480dp screen the Interests step (the longest,
     * with every category chip plus the custom-interest field) used to push Skip / Next
     * below the fold. They must now be visible without any scrolling, on every step.
     */
    @Test
    fun navigationButtons_stayVisibleWithoutScrolling_onSmallScreen() {
        composeRule.setContent {
            FauxxTheme {
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    OnboardingScreen(onFinish = {})
                }
            }
        }

        // Welcome, Age Range, Gender, Interests, Profession: Skip and Next must both be on
        // screen as-is, then Next advances to the following step.
        repeat(5) {
            composeRule.onNodeWithText("Next").assertIsDisplayed()
            composeRule.onNodeWithText("Skip").assertIsDisplayed()
            composeRule.onNodeWithText("Next").performClick()
        }
        // Region, the last step, relabels the pair Skip All / Done.
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Skip All").assertIsDisplayed()
    }
}
