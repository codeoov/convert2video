package com.example.convert2video.ui.screens.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.convert2video.R
import com.example.convert2video.billing.BillingFailureKind
import com.example.convert2video.ui.components.buttons.AccentCtaButton
import com.example.convert2video.ui.components.buttons.SoftChipButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.theme.C2vTheme

@Composable
internal fun OptionsProUpgradeContent(
    proStatus: Boolean?,
    billingPurchaseState: BillingPurchaseUiState,
    onUpgradeClick: () -> Unit,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = billingPurchaseState == BillingPurchaseUiState.Preparing ||
        billingPurchaseState == BillingPurchaseUiState.AwaitingResolution ||
        billingPurchaseState == BillingPurchaseUiState.Pending
    val isUpgradeEnabled = proStatus == false && !isBusy
    val isRestoreEnabled = !isBusy
    val statusResource = when (billingPurchaseState) {
        BillingPurchaseUiState.Idle -> when (proStatus) {
            null -> R.string.options_pro_status_checking
            false -> R.string.options_pro_status_inactive
            true -> R.string.options_pro_status_active
        }
        BillingPurchaseUiState.Preparing,
        BillingPurchaseUiState.AwaitingResolution,
        -> R.string.options_pro_purchase_in_progress
        BillingPurchaseUiState.Pending -> R.string.options_pro_purchase_pending
        BillingPurchaseUiState.Purchased -> R.string.options_pro_status_active
        BillingPurchaseUiState.Cancelled -> R.string.options_pro_purchase_cancelled
        is BillingPurchaseUiState.Failed -> when (billingPurchaseState.kind) {
            BillingFailureKind.Unavailable -> R.string.options_pro_purchase_failed_unavailable
            BillingFailureKind.InvalidProduct -> R.string.options_pro_purchase_failed_product
            BillingFailureKind.Network -> R.string.options_pro_purchase_failed_network
            BillingFailureKind.LaunchFailed -> R.string.options_pro_purchase_failed_launch
            BillingFailureKind.InvalidResponse -> R.string.options_pro_purchase_failed_response
            BillingFailureKind.ProviderError -> R.string.options_pro_purchase_failed_provider
        }
    }

    C2vCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.options_pro_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(statusResource),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("pro_status_text"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccentCtaButton(
                    text = stringResource(R.string.options_pro_upgrade),
                    onClick = { if (isUpgradeEnabled) onUpgradeClick() },
                    enabled = isUpgradeEnabled,
                    modifier = Modifier.weight(1f).testTag("pro_upgrade_button"),
                    height = 48.dp,
                )
                SoftChipButton(
                    text = stringResource(R.string.options_pro_restore),
                    onClick = { if (isRestoreEnabled) onRestoreClick() },
                    enabled = isRestoreEnabled,
                    modifier = Modifier.testTag("pro_restore_button"),
                )
            }
            Text(
                text = stringResource(R.string.options_pro_restore_policy_notice),
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
            )
        }
    }
}
