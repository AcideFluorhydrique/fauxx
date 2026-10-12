package com.fauxx

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.fauxx.data.model.PoisonProfile
import com.fauxx.di.PreferenceKeys
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.support.MainDispatcherRule
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Region
import com.fauxx.ui.viewmodels.OnboardingViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Finishing first-run onboarding with answers must switch Layer 1 on. It used to save the profile
 * and leave Layer 1 off, so the answers had no effect until the user found the Targeting switch.
 * The same screen edits the profile later (#29); an edit must leave the user's Layer 1 choice alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    /** In-memory Preferences store; `edit {}` goes through [updateData]. */
    private class FakePrefs(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: StateFlow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val dao: DemographicProfileDao = mockk(relaxed = true) {
        coEvery { get() } returns null
    }
    private val profile = MutableStateFlow(PoisonProfile())
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true) {
        coEvery { updateProfile(any()) } coAnswers {
            val transform = firstArg<(PoisonProfile) -> PoisonProfile>()
            profile.value = transform(profile.value)
        }
    }
    private val targetingEngine: TargetingEngine = mockk(relaxed = true)

    private fun viewModel(prefs: FakePrefs) =
        OnboardingViewModel(dao, prefs, mockk<CustomInterestMapper>(relaxed = true), profileRepo, targetingEngine)

    @Test
    fun `first-run onboarding with answers switches Layer 1 on`() = runTest(testDispatcher) {
        val prefs = FakePrefs()
        val vm = viewModel(prefs)
        vm.setAgeRange(AgeRange.AGE_25_34)
        vm.setRegion(Region.WESTERN_EUROPE)

        vm.saveAndFinish()

        assertTrue(profile.value.layer1Enabled)
        verify { targetingEngine.setLayer1Enabled(true) }
        coVerify { dao.upsert(any()) }
        assertEquals(true, prefs.data.value[PreferenceKeys.ONBOARDING_COMPLETED])
    }

    @Test
    fun `first-run onboarding skipped entirely leaves Layer 1 off`() = runTest(testDispatcher) {
        val prefs = FakePrefs()
        viewModel(prefs).saveAndFinish()

        assertFalse(profile.value.layer1Enabled)
        verify(exactly = 0) { targetingEngine.setLayer1Enabled(any()) }
        assertEquals(true, prefs.data.value[PreferenceKeys.ONBOARDING_COMPLETED])
    }

    @Test
    fun `editing the profile later keeps a Layer 1 the user turned off`() = runTest(testDispatcher) {
        val prefs = FakePrefs(mutablePreferencesOf(PreferenceKeys.ONBOARDING_COMPLETED to true))
        val vm = viewModel(prefs)
        vm.setAgeRange(AgeRange.AGE_35_44)

        vm.saveAndFinish()

        assertFalse(profile.value.layer1Enabled)
        verify(exactly = 0) { targetingEngine.setLayer1Enabled(any()) }
        coVerify { dao.upsert(any()) }
    }
}
