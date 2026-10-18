package id.nearyou.app.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.nearyou.app.ui.components.BackNavigationIcon
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.PremiumBadgeIcon
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_error_too_long
import id.nearyou.resources.generated.resources.chat_send
import id.nearyou.resources.generated.resources.chat_send_blocked
import id.nearyou.resources.generated.resources.chat_thread_input_placeholder
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_send_filled
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The frame-5 partner header: an M3 TopAppBar (its own status-bar inset — this overlay owns its Scaffold, 06-#4)
 * with the shared back arrow, then the partner [LetterAvatar] (32dp) + bold display name and [PremiumBadgeIcon]
 * over the `@username`. The deleted / shadow-banned mask ([isMaskedPartner]: the backend `akun_dihapus`
 * placeholder, or a blank route name) renders the `chat_account_deleted` label with no handle and no badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatThreadTopBar(
    partnerDisplayName: String,
    partnerUsername: String,
    partnerIsPremium: Boolean,
    onBack: () -> Unit,
) {
    val masked = isMaskedPartner(partnerUsername, partnerDisplayName)
    val name = if (masked) stringResource(Res.string.chat_account_deleted) else partnerDisplayName
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LetterAvatar(displayName = name, username = partnerUsername, modifier = Modifier.size(32.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (partnerIsPremium && !masked) PremiumBadgeIcon(modifier = Modifier.testTag(CHAT_THREAD_PREMIUM_BADGE_TAG))
                    }
                    if (!masked) {
                        Text(
                            text = stringResource(Res.string.post_card_handle, partnerUsername),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = { BackNavigationIcon(onBack = onBack, modifier = Modifier.testTag(CHAT_THREAD_BACK_TAG)) },
    )
}

@Composable
internal fun SendBlockedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(Res.string.chat_send_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(CHAT_THREAD_BLOCKED_BANNER_TAG),
        )
    }
}

/** A failed send (transport / 5xx): the connectivity copy + a retry that re-sends the kept input. */
@Composable
internal fun SendRetryBanner(
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag(CHAT_THREAD_RETRY_BANNER_TAG),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.signin_error_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onRetry, enabled = canRetry, modifier = Modifier.testTag(CHAT_THREAD_RETRY_TAG)) {
                Text(text = stringResource(Res.string.cta_retry))
            }
        }
    }
}

/** The over-limit input hint (> 2000 code points) — send is already disabled; this says why. */
@Composable
internal fun TooLongHint() {
    Text(
        text = stringResource(Res.string.chat_error_too_long),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(CHAT_THREAD_TOO_LONG_TAG),
    )
}

@Composable
internal fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
) {
    // Frame 5 input row (the frame-7 composer idiom, PostDetailComposer): a borderless surfaceContainerHigh pill
    // field + a filled circular send button, 10dp apart, 12/10 padding.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(text = stringResource(Res.string.chat_thread_input_placeholder)) },
            // Bounded: a long draft scrolls inside the field instead of growing the bottom bar over the thread.
            maxLines = 5,
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            modifier = Modifier.weight(1f).testTag(CHAT_THREAD_INPUT_TAG),
        )
        FilledIconButton(
            onClick = onSend,
            enabled = canSend && !sending,
            modifier = Modifier.size(48.dp).testTag(CHAT_THREAD_SEND_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_send_filled),
                contentDescription = stringResource(Res.string.chat_send),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
