package id.nearyou.app.screens.paywall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.ic_action_clear
import id.nearyou.resources.generated.resources.ic_premium_star
import id.nearyou.resources.generated.resources.ic_workspace_premium
import id.nearyou.resources.generated.resources.paywall_benefit_hide_distance
import id.nearyou.resources.generated.resources.paywall_benefit_no_ads
import id.nearyou.resources.generated.resources.paywall_benefit_radius
import id.nearyou.resources.generated.resources.paywall_benefit_search_edit
import id.nearyou.resources.generated.resources.paywall_benefit_unlimited
import id.nearyou.resources.generated.resources.paywall_benefit_username
import id.nearyou.resources.generated.resources.paywall_best_value
import id.nearyou.resources.generated.resources.paywall_disclosure
import id.nearyou.resources.generated.resources.paywall_per_day
import id.nearyou.resources.generated.resources.paywall_period_monthly
import id.nearyou.resources.generated.resources.paywall_period_weekly
import id.nearyou.resources.generated.resources.paywall_period_yearly
import id.nearyou.resources.generated.resources.paywall_purchase_error
import id.nearyou.resources.generated.resources.paywall_savings
import id.nearyou.resources.generated.resources.paywall_subhead_default
import id.nearyou.resources.generated.resources.paywall_subhead_like_cap
import id.nearyou.resources.generated.resources.paywall_subhead_search
import id.nearyou.resources.generated.resources.paywall_title
import id.nearyou.resources.generated.resources.paywall_unavailable_body
import id.nearyou.resources.generated.resources.paywall_unavailable_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tags for the Robolectric screen test. */
const val PAYWALL_SCREEN_TAG: String = "paywall_screen"
const val PAYWALL_CTA_TAG: String = "paywall_cta"
const val PAYWALL_CLOSE_TAG: String = "paywall_close"
const val PAYWALL_UNCONFIGURED_TAG: String = "paywall_unconfigured"

/**
 * The Premium paywall (mockup frame 17, the `mobile-paywall` capability). Navigation-free: it holds no
 * back-stack reference; the close affordance invokes [onClose] and a confirmed purchase invokes
 * [onPurchaseComplete] (the host pops). The route-scoped [PaywallViewModel] (resolved over the
 * vendor-free [PurchaseController] seam) loads the current offering → [PaywallUiState]; [entry] tailors
 * only the hero subheadline (the full offering always shows). Every string resolves via `stringResource`
 * and every color via `MaterialTheme` tokens (no literals); renders under `NearYouTheme` light + dark.
 */
@Composable
fun PaywallScreen(
    entry: PaywallEntry,
    onClose: () -> Unit,
    onPurchaseComplete: () -> Unit = onClose,
) {
    val controller = koinInject<PurchaseController>()
    val viewModel = viewModel { PaywallViewModel(controller) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // One-shot return: when the purchase is confirmed, consume the flag and let the host pop.
    val succeeded = (state as? PaywallUiState.Content)?.purchaseSucceeded == true
    LaunchedEffect(succeeded) {
        if (succeeded) {
            viewModel.onReturnConsumed()
            onPurchaseComplete()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(PAYWALL_SCREEN_TAG), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            PaywallTopBar(onClose = onClose)
            when (val s = state) {
                PaywallUiState.Loading -> CenteredProgress()
                PaywallUiState.Unconfigured -> UnconfiguredState(onClose = onClose)
                is PaywallUiState.Content ->
                    PaywallContent(
                        content = s,
                        entry = entry,
                        onSelectPeriod = viewModel::onSelectPeriod,
                        onSubscribe = viewModel::onSubscribe,
                    )
            }
        }
    }
}

@Composable
private fun PaywallTopBar(onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(PAYWALL_CLOSE_TAG)) {
            Icon(
                painter = painterResource(Res.drawable.ic_action_clear),
                contentDescription = stringResource(Res.string.cta_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun UnconfiguredState(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(PAYWALL_UNCONFIGURED_TAG).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.paywall_unavailable_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.paywall_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onClose) { Text(text = stringResource(Res.string.cta_close)) }
    }
}

@Composable
private fun PaywallContent(
    content: PaywallUiState.Content,
    entry: PaywallEntry,
    onSelectPeriod: (PaywallPeriod) -> Unit,
    onSubscribe: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
    ) {
        PaywallHero(entry = entry)
        Spacer(Modifier.height(16.dp))
        BenefitsList()
        Spacer(Modifier.height(20.dp))
        PricingCards(content = content, onSelectPeriod = onSelectPeriod)
        Spacer(Modifier.height(20.dp))
        if (content.purchaseError) {
            Text(
                text = stringResource(Res.string.paywall_purchase_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = onSubscribe,
            enabled = !content.purchaseInProgress,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PAYWALL_CTA_TAG),
        ) {
            if (content.purchaseInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(text = stringResource(Res.string.cta_activate_premium), fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.paywall_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PaywallHero(entry: PaywallEntry) {
    val subhead: StringResource =
        when (entry) {
            PaywallEntry.LIKE_CAP -> Res.string.paywall_subhead_like_cap
            PaywallEntry.SEARCH_GATE -> Res.string.paywall_subhead_search
            // image-attached-posts: the composer image-attach gate reuses the generic Premium subhead
            // (no image-specific hero copy — the upsell context is enough), mirroring USERNAME.
            PaywallEntry.USERNAME, PaywallEntry.IMAGE_ATTACH -> Res.string.paywall_subhead_default
        }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(Res.drawable.ic_workspace_premium),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.paywall_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(subhead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BenefitsList() {
    val benefits =
        listOf(
            Res.string.paywall_benefit_unlimited,
            Res.string.paywall_benefit_radius,
            Res.string.paywall_benefit_hide_distance,
            Res.string.paywall_benefit_username,
            Res.string.paywall_benefit_search_edit,
            Res.string.paywall_benefit_no_ads,
        )
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        benefits.forEach { benefit ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(Res.drawable.ic_premium_star),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(benefit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PricingCards(
    content: PaywallUiState.Content,
    onSelectPeriod: (PaywallPeriod) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        content.cards.forEach { card ->
            PriceCard(
                card = card,
                selected = card.period == content.selectedPeriod,
                onClick = { onSelectPeriod(card.period) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PriceCard(
    card: PaywallCardPricing,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = modifier.selectable(selected = selected, onClick = onClick).testTag(priceCardTag(card.period)),
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (card.period == PaywallPeriod.YEARLY) {
                Text(
                    text = stringResource(Res.string.paywall_best_value),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = stringResource(periodLabel(card.period)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            card.anchorMicros?.let { anchor ->
                Text(
                    text = formatPriceMicros(anchor, card.currencyCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
            Text(
                text = card.localizedPrice,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            card.perDayMicros?.let { perDay ->
                Text(
                    text = stringResource(Res.string.paywall_per_day, formatPriceMicros(perDay, card.currencyCode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.savingsPercent?.let { percent ->
                Text(
                    text = stringResource(Res.string.paywall_savings, "$percent%"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun priceCardTag(period: PaywallPeriod): String = "paywall_card_${period.name.lowercase()}"

private fun periodLabel(period: PaywallPeriod): StringResource =
    when (period) {
        PaywallPeriod.WEEKLY -> Res.string.paywall_period_weekly
        PaywallPeriod.MONTHLY -> Res.string.paywall_period_monthly
        PaywallPeriod.YEARLY -> Res.string.paywall_period_yearly
    }
