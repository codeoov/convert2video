package com.example.convert2video.ui.screens.options

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.store.StoreCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OptionsScreenBillingTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun success_shouldShowBillingOptions_googlePlay() {
        // Given: Google Play store capability SSOT
        // Helper-only: do not setContent(OptionsScreen).
        val capabilities = StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID)

        // When: Options billing gate is evaluated
        val visible = shouldShowBillingOptions(capabilities)

        // Then: Google flavor exposes the Pro section
        assertTrue(visible)
    }

    @Test
    fun success_shouldShowBillingOptions_huawei() {
        // Given: Huawei store capability SSOT
        // Helper-only: do not setContent(OptionsScreen).
        val capabilities = StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID)

        // When: Options billing gate is evaluated
        val visible = shouldShowBillingOptions(capabilities)

        // Then: Huawei flavor exposes the Pro section
        assertTrue(visible)
    }

    @Test
    fun success_shouldShowBillingOptions_unknownStore() {
        // Given: unknown store is fail-closed
        // Helper-only: do not setContent(OptionsScreen).
        val capabilities = StoreCapabilities.forStoreId("unknown_store")

        // When: Options billing gate is evaluated
        val visible = shouldShowBillingOptions(capabilities)

        // Then: unknown store hides the Pro section
        assertFalse(visible)
    }

    @Test
    fun success_shouldAcceptProBillingClick_languageApplyingIsFalse() {
        // Given: language apply is in-flight
        // Helper-only: do not setContent(OptionsScreen).

        // When: Upgrade/Restore click gate is evaluated
        val accepted = shouldAcceptProBillingClick(isLanguageApplying = true)

        // Then: language applying must not start purchase/restore
        assertFalse(accepted)
    }

    @Test
    fun success_shouldAcceptProBillingClick_idleIsTrue() {
        // Given: language apply is idle
        // Helper-only: do not setContent(OptionsScreen).

        // When: Upgrade/Restore click gate is evaluated
        val accepted = shouldAcceptProBillingClick(isLanguageApplying = false)

        // Then: idle language apply accepts the click
        assertTrue(accepted)
    }

    @Test
    fun success_purchaseProActivityOrNull_applicationReturnsNull() {
        // Given: Application context has no Activity
        // Helper-only: do not setContent(OptionsScreen).
        val app = ApplicationProvider.getApplicationContext<Application>()

        // When: Upgrade 경로에서 Activity를 찾음
        val activity = purchaseProActivityOrNull(app)

        // Then: purchasePro를 호출할 Activity가 없어 null
        assertNull(activity)
    }

    @Test
    fun success_purchaseProActivityOrNull_finishingReturnsNull() {
        // Given: composeTestRule.activity, then finish().
        // ActivityScenario.launch(ComponentActivity) relaunch is forbidden.
        // isFinishing/isDestroyed override is forbidden (both are final).
        // Helper-only: do not setContent(OptionsScreen).
        val activity = composeTestRule.activity
        activity.finish()

        // When: helper is called with the finishing activity
        val result = purchaseProActivityOrNull(activity)

        // Then: finishing Activity must not start a purchase
        assertTrue(activity.isFinishing)
        assertNull(result)
    }

    @Test
    fun success_purchaseProActivityOrNull_destroyedReturnsNull() {
        // Given: composeTestRule.activity, then DESTROYED.
        // ActivityScenario.launch(ComponentActivity) relaunch is forbidden.
        // isFinishing/isDestroyed override is forbidden (both are final).
        // Helper-only: do not setContent(OptionsScreen).
        val activity = composeTestRule.activity
        val scenario = composeTestRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.DESTROYED)

        // When: helper is called with the destroyed activity
        val result = purchaseProActivityOrNull(activity)

        // Then: destroyed Activity must not start a purchase
        assertTrue(activity.isDestroyed)
        assertNull(result)
    }

    @Test
    fun success_actualOptionsScreen_showsProSectionWhenSupportsBilling() {
        // Given: 실 OptionsScreen (section presence + countdown/YouTube order).
        // Upgrade/Restore performClick is forbidden here (purchase/restore side effects).
        composeTestRule.setContent {
            OptionsScreen(onOpenDrawer = {})
        }

        // When: flavor capability가 billing을 허용하는지 확인
        val supportsBilling = StoreCapabilities.current.supportsBilling
        val expectedCount = if (supportsBilling) 1 else 0

        // Then: supportsBilling이면 pro_* 태그 1, 아니면 0
        composeTestRule.onAllNodesWithTag("pro_upgrade_button").assertCountEquals(expectedCount)
        composeTestRule.onAllNodesWithTag("pro_restore_button").assertCountEquals(expectedCount)
        composeTestRule.onAllNodesWithTag("pro_status_text").assertCountEquals(expectedCount)

        if (!supportsBilling) return

        val supportsYouTube = StoreCapabilities.current.supportsYouTube
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            val countdownReady = composeTestRule.onAllNodesWithTag("recording_countdown_card")
                .fetchSemanticsNodes().isNotEmpty()
            val proReady = composeTestRule.onAllNodesWithTag("pro_status_text")
                .fetchSemanticsNodes().isNotEmpty()
            val youtubeReady = !supportsYouTube ||
                composeTestRule.onAllNodesWithTag("youtube_auto_upload_switch")
                    .fetchSemanticsNodes().isNotEmpty()
            countdownReady && proReady && youtubeReady
        }
        composeTestRule.onNodeWithTag("recording_countdown_card").performScrollTo()
        composeTestRule.onNodeWithTag("pro_status_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_upgrade_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_restore_button").performScrollTo().assertIsDisplayed()
        if (supportsYouTube) {
            composeTestRule.onNodeWithTag("youtube_auto_upload_switch").performScrollTo()
        }

        val countdownY = composeTestRule
            .onNodeWithTag("recording_countdown_card")
            .fetchSemanticsNode()
            .positionInRoot.y
        val proY = composeTestRule
            .onNodeWithTag("pro_status_text")
            .fetchSemanticsNode()
            .positionInRoot.y
        assertTrue(countdownY < proY)

        if (supportsYouTube) {
            val youtubeY = composeTestRule
                .onNodeWithTag("youtube_auto_upload_switch")
                .fetchSemanticsNode()
                .positionInRoot.y
            assertTrue(proY < youtubeY)
        }
    }
}
