package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.billing.BillingFailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionsProUpgradeContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertFixedTagsAppearExactlyOnce() {
        composeTestRule.onAllNodesWithTag("pro_upgrade_button").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("pro_restore_button").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("pro_status_text").assertCountEquals(1)
    }

    private fun assertStatusResource(resourceId: Int) {
        composeTestRule.onNodeWithTag("pro_status_text").assertTextEquals(
            appContext.getString(resourceId),
        )
    }

    @Test
    fun success_nullProStatusShowsCheckingResourceAndRestoreIsEligible() {
        // Given: Pro 상태를 아직 읽지 못한 Idle 상태
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = null,
                billingPurchaseState = BillingPurchaseUiState.Idle,
                onUpgradeClick = {},
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 확인 가능한 복원 버튼을 한 번 탭함
        assertStatusResource(R.string.options_pro_status_checking)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: 확인 중 상태에서도 복원 callback만 호출됨
        composeTestRule.runOnIdle { assertEquals(1, restoreCalls) }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_falseProStatusShowsInactiveResourceAndBothActionsAreEligible() {
        // Given: Pro 미활성 + 결제 대기 없음
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Idle,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 활성화된 두 버튼을 각각 한 번 탭함
        assertStatusResource(R.string.options_pro_status_inactive)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: 업그레이드와 복원 callback이 각각 한 번 호출됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_trueProStatusShowsActiveResourceAndOnlyRestoreIsEligible() {
        // Given: Pro 활성 + 결제 대기 없음
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = true,
                billingPurchaseState = BillingPurchaseUiState.Idle,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 활성화된 복원 버튼만 한 번 탭함
        assertStatusResource(R.string.options_pro_status_active)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: 복원만 callback을 호출하고 업그레이드는 호출되지 않음
        composeTestRule.runOnIdle {
            assertEquals(0, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_preparingDisablesBothButtonsWithoutCallbacks() {
        // Given: Preparing 상태와 callback 감시자
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Preparing,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: disabled control은 탭하지 않고 enabled 상태만 확인함
        assertStatusResource(R.string.options_pro_purchase_in_progress)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsNotEnabled()

        // Then: callback은 호출되지 않음
        composeTestRule.runOnIdle {
            assertEquals(0, upgradeCalls)
            assertEquals(0, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_awaitingResolutionDisablesBothButtonsWithoutCallbacks() {
        // Given: AwaitingResolution 상태와 callback 감시자
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = null,
                billingPurchaseState = BillingPurchaseUiState.AwaitingResolution,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: disabled control은 탭하지 않고 enabled 상태만 확인함
        assertStatusResource(R.string.options_pro_purchase_in_progress)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsNotEnabled()

        // Then: callback은 호출되지 않음
        composeTestRule.runOnIdle {
            assertEquals(0, upgradeCalls)
            assertEquals(0, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_pendingDisablesBothButtonsWithoutCallbacks() {
        // Given: Pending 상태와 callback 감시자
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Pending,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: disabled control은 탭하지 않고 enabled 상태만 확인함
        assertStatusResource(R.string.options_pro_purchase_pending)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsNotEnabled()

        // Then: callback은 호출되지 않음
        composeTestRule.runOnIdle {
            assertEquals(0, upgradeCalls)
            assertEquals(0, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_purchasedFalseProStatusShowsActiveResourceAndBothActionsAreEligible() {
        // Given: 구매 완료 상태지만 외부 Pro 상태는 아직 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Purchased,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: non-busy 상태의 두 활성 버튼을 각각 탭함
        assertStatusResource(R.string.options_pro_status_active)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: 명시적 proStatus=false 행렬에서 두 callback이 호출됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_purchasedTrueProStatusKeepsRestoreEligibleAndUpgradeDisabled() {
        // Given: 구매 완료 + Pro 활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = true,
                billingPurchaseState = BillingPurchaseUiState.Purchased,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: enabled restore만 한 번 탭함
        assertStatusResource(R.string.options_pro_status_active)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: restore callback만 호출됨
        composeTestRule.runOnIdle {
            assertEquals(0, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_cancelledFalseProStatusShowsCancelledAndBothActionsAreEligible() {
        // Given: 구매 취소 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Cancelled,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 enabled action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_cancelled)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: Cancelled + proStatus=false에서 두 callback이 호출됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_cancelledTrueProStatusKeepsRestoreEligibleAndUpgradeDisabled() {
        // Given: 구매 취소 + Pro 활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = true,
                billingPurchaseState = BillingPurchaseUiState.Cancelled,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: enabled restore만 한 번 탭함
        assertStatusResource(R.string.options_pro_purchase_cancelled)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: restore callback만 호출됨
        composeTestRule.runOnIdle {
            assertEquals(0, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_unavailableFailureWithFalseProStatusShowsLocalizedFailureAndBothActionsAreEligible() {
        // Given: Unavailable 실패 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Failed(BillingFailureKind.Unavailable),
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 non-busy action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_failed_unavailable)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: localized failure과 두 callback이 확인됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_invalidProductFailureWithFalseProStatusShowsLocalizedFailureAndBothActionsAreEligible() {
        // Given: InvalidProduct 실패 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Failed(BillingFailureKind.InvalidProduct),
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 non-busy action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_failed_product)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: localized failure과 두 callback이 확인됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_networkFailureWithFalseProStatusShowsLocalizedFailureAndBothActionsAreEligible() {
        // Given: Network 실패 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Failed(BillingFailureKind.Network),
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 non-busy action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_failed_network)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: localized failure과 두 callback이 확인됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_launchFailureWithFalseProStatusShowsLocalizedFailureAndBothActionsAreEligible() {
        // Given: LaunchFailed 실패 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Failed(BillingFailureKind.LaunchFailed),
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 non-busy action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_failed_launch)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: localized failure과 두 callback이 확인됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_invalidResponseFailureWithFalseProStatusShowsLocalizedFailureAndBothActionsAreEligible() {
        // Given: InvalidResponse 실패 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Failed(BillingFailureKind.InvalidResponse),
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 non-busy action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_failed_response)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: localized failure과 두 callback이 확인됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_providerFailureWithFalseProStatusShowsLocalizedFailureAndBothActionsAreEligible() {
        // Given: ProviderError 실패 + Pro 비활성
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Failed(BillingFailureKind.ProviderError),
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: 두 non-busy action을 각각 탭함
        assertStatusResource(R.string.options_pro_purchase_failed_provider)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: localized failure과 두 callback이 확인됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_recompositionFromBusyToInactiveEnablesCallbacks() {
        // Given: 외부에서 공급되는 busy 상태와 callback 감시자
        var proStatus by mutableStateOf<Boolean?>(false)
        var billingPurchaseState by mutableStateOf<BillingPurchaseUiState>(
            BillingPurchaseUiState.Preparing,
        )
        var upgradeCalls = 0
        var restoreCalls = 0
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = proStatus,
                billingPurchaseState = billingPurchaseState,
                onUpgradeClick = { upgradeCalls++ },
                onRestoreClick = { restoreCalls++ },
                modifier = Modifier,
            )
        }

        // When: busy 상태에서 non-busy inactive 상태로 전환한 뒤 두 버튼을 탭함
        assertStatusResource(R.string.options_pro_purchase_in_progress)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsNotEnabled()
        composeTestRule.runOnIdle {
            billingPurchaseState = BillingPurchaseUiState.Idle
        }
        assertStatusResource(R.string.options_pro_status_inactive)
        composeTestRule.onNodeWithTag("pro_upgrade_button").assertIsEnabled().performClick()
        composeTestRule.onNodeWithTag("pro_restore_button").assertIsEnabled().performClick()

        // Then: recomposition 후 callback eligibility가 최신 상태로 반영됨
        composeTestRule.runOnIdle {
            assertEquals(1, upgradeCalls)
            assertEquals(1, restoreCalls)
        }
        assertFixedTagsAppearExactlyOnce()
    }

    @Test
    fun success_tagsAppearExactlyOnceAndPolicyNoticeUsesResource() {
        // Given: 기본 Pro 카드
        composeTestRule.setContent {
            OptionsProUpgradeContent(
                proStatus = false,
                billingPurchaseState = BillingPurchaseUiState.Idle,
                onUpgradeClick = {},
                onRestoreClick = {},
                modifier = Modifier,
            )
        }

        // When: 별도 조작 없음

        // Then: 고정 태그와 제목/정책 안내가 정확히 표시됨
        assertFixedTagsAppearExactlyOnce()
        composeTestRule.onNodeWithText(appContext.getString(R.string.options_pro_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_pro_restore_policy_notice),
        ).assertIsDisplayed()
    }
}
