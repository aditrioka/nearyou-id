package id.nearyou.app.screens.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_reply
import id.nearyou.resources.generated.resources.ic_send_filled
import id.nearyou.resources.generated.resources.post_detail_post_gone
import id.nearyou.resources.generated.resources.post_detail_reply_counter
import id.nearyou.resources.generated.resources.post_detail_reply_placeholder
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The reply composer — the Scaffold bottom bar, mockup frame 7's `.chatinput`: a pill-shaped multiline
 * [TextField] (`surfaceContainerHigh`, no indicator line, bounded growth) + a filled circular send action —
 * the "Balas" CTA, labelled via its contentDescription and disabled while the draft is empty / over-limit /
 * in-flight (the [replyComposerUiState] gate, so the client never submits invalid content). While the draft
 * is non-empty a live `N/280` code-point counter renders above the row (error-tinted past the limit). A
 * post-gone / network [banner] renders above too; for the network case the send action itself is the retry
 * (the draft is preserved on failure). Keeps itself above the navigation bar AND the IME (06-#4). The frame's
 * leading self-avatar is the deferred #569.
 */
@Composable
internal fun ReplyComposer(
    content: String,
    onContentChange: (String) -> Unit,
    inFlight: Boolean,
    banner: PostDetailBanner?,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    val composer = replyComposerUiState(content, inFlight)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (banner != null) {
            BannerText(banner = banner, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (content.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.post_detail_reply_counter, composer.charCount),
                style = MaterialTheme.typography.labelSmall,
                color = if (composer.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(end = 64.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = content,
                onValueChange = onContentChange,
                placeholder = { Text(text = stringResource(Res.string.post_detail_reply_placeholder)) },
                enabled = !inFlight,
                maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag(POST_DETAIL_REPLY_FIELD_TAG),
            )
            FilledIconButton(
                onClick = onSubmit,
                enabled = composer.submitEnabled,
                modifier = Modifier.size(48.dp).testTag(POST_DETAIL_REPLY_SEND_TAG),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_send_filled),
                    contentDescription = stringResource(Res.string.cta_reply),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** Renders a [PostDetailBanner] — the terminal post-gone copy or the generic retryable network copy (the
 *  like / reply caps are the cap dialog, not a banner). Always via `stringResource` (no literals). */
@Composable
internal fun BannerText(
    banner: PostDetailBanner,
    modifier: Modifier = Modifier,
) {
    val message =
        when (banner) {
            PostDetailBanner.PostGone -> stringResource(Res.string.post_detail_post_gone)
            PostDetailBanner.Network -> stringResource(Res.string.signin_error_network)
        }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth(),
    )
}
