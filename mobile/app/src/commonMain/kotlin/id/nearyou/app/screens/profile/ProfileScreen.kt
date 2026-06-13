package id.nearyou.app.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ReportReasonCategory
import id.nearyou.app.profile.UserProfile
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_block
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_more_vert
import id.nearyou.resources.generated.resources.ic_premium_star
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.profile_action_failed
import id.nearyou.resources.generated.resources.profile_action_user_unavailable
import id.nearyou.resources.generated.resources.profile_actions_menu_description
import id.nearyou.resources.generated.resources.profile_block_action
import id.nearyou.resources.generated.resources.profile_block_confirm_body
import id.nearyou.resources.generated.resources.profile_block_confirm_title
import id.nearyou.resources.generated.resources.profile_block_rate_limited
import id.nearyou.resources.generated.resources.profile_block_success_toast
import id.nearyou.resources.generated.resources.profile_follow
import id.nearyou.resources.generated.resources.profile_follow_rate_limited
import id.nearyou.resources.generated.resources.profile_followers_count
import id.nearyou.resources.generated.resources.profile_following_count
import id.nearyou.resources.generated.resources.profile_not_found
import id.nearyou.resources.generated.resources.profile_premium_badge
import id.nearyou.resources.generated.resources.profile_premium_badge_icon_description
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.generated.resources.profile_report_duplicate
import id.nearyou.resources.generated.resources.profile_report_note_placeholder
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_reason_title
import id.nearyou.resources.generated.resources.profile_report_submit
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.profile_unfollow
import id.nearyou.resources.generated.resources.report_reason_adult_content
import id.nearyou.resources.generated.resources.report_reason_harassment
import id.nearyou.resources.generated.resources.report_reason_hate_speech_sara
import id.nearyou.resources.generated.resources.report_reason_misinformation
import id.nearyou.resources.generated.resources.report_reason_other
import id.nearyou.resources.generated.resources.report_reason_spam
import id.nearyou.resources.generated.resources.section_profile
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tags for the profile surface (Robolectric `ProfileScreenTest`). */
const val PROFILE_FOLLOW_TOGGLE_TAG: String = "profileFollowToggle"
const val PROFILE_ACTIONS_MENU_TAG: String = "profileActionsMenu"
const val PROFILE_BLOCK_DIALOG_TAG: String = "profileBlockDialog"
const val PROFILE_REPORT_DIALOG_TAG: String = "profileReportDialog"
const val PROFILE_FOLLOWERS_TAG: String = "profileFollowers"
const val PROFILE_FOLLOWING_TAG: String = "profileFollowing"
const val PROFILE_NOT_FOUND_TAG: String = "profileNotFound"
const val PROFILE_BACK_TAG: String = "profileBack"

/**
 * The profile surface (`mobile-profile`). Renders a user from `GET /api/v1/users/{id}` via a
 * `viewModel { }`-scoped [ProfileViewModel]. Two modes (design D1):
 *  - **other-user overlay** ([onBack] non-null) — a root-stack `ProfileRoute` overlay owning its own
 *    `Scaffold` + back `TopAppBar` + `SnackbarHost`; shows the follow toggle + the Blokir/Laporkan kebab
 *    when the loaded profile's `isSelf` is false.
 *  - **self section** ([onBack] null, [targetUserId] null) — rendered inset-free in the shell's Profil
 *    section (no own `Scaffold`/`TopAppBar`); the loaded `isSelf = true` profile shows no actions.
 *
 * One-shot events: [ProfileUiState.message] → a snackbar (overlay only — the self read raises none);
 * [ProfileUiState.navigateBack] (block success) → [onBack] then cleared. The target `userId` is the
 * route resource key, never rendered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    targetUserId: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val flow = koinInject<ProfileFlow>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    val viewModel = viewModel { ProfileViewModel(flow, selfUserIdProvider, targetUserId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot navigate-back after a successful block (other-user overlay only).
    LaunchedEffect(uiState.navigateBack) {
        if (uiState.navigateBack) {
            onBack?.invoke()
            viewModel.onNavigatedBack()
        }
    }
    // One-shot message → snackbar. messageText resolves the enum to a string; null → nothing.
    val message = uiState.message
    val messageText = message?.let { stringResource(it.resource(), *it.formatArgs()) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    if (onBack != null) {
        // Other-user overlay: own chrome (back bar + snackbar host).
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(Res.string.section_profile)) },
                    navigationIcon = {
                        TextButton(onClick = onBack, modifier = Modifier.testTag(PROFILE_BACK_TAG)) {
                            Text(text = stringResource(Res.string.cta_close))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            ProfileBody(
                uiState = uiState,
                onToggleFollow = viewModel::onToggleFollow,
                onBlockConfirmed = viewModel::onBlockConfirmed,
                onReportSubmitted = viewModel::onReportSubmitted,
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    } else {
        // Self section: inset-free body under the shell's Scaffold, which already applies + consumes the
        // window insets (mobile-design-system single-Scaffold contract) — so NO own Scaffold/TopAppBar
        // and NO statusBarsPadding here (that would double-pad below the status bar).
        ProfileBody(
            uiState = uiState,
            onToggleFollow = viewModel::onToggleFollow,
            onBlockConfirmed = viewModel::onBlockConfirmed,
            onReportSubmitted = viewModel::onReportSubmitted,
            onRetry = viewModel::retry,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ProfileBody(
    uiState: ProfileUiState,
    onToggleFollow: () -> Unit,
    onBlockConfirmed: () -> Unit,
    onReportSubmitted: (ReportReasonCategory, String?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val phase = uiState.phase) {
            ProfilePhase.Loading -> CircularProgressIndicator()
            ProfilePhase.NotFound ->
                CenteredMessage(stringResource(Res.string.profile_not_found), Modifier.testTag(PROFILE_NOT_FOUND_TAG))
            ProfilePhase.Error -> ErrorRetry(onRetry)
            is ProfilePhase.Content ->
                ProfileContent(
                    profile = phase.profile,
                    followedByViewer = uiState.followedByViewer,
                    isFollowInFlight = uiState.isFollowInFlight,
                    onToggleFollow = onToggleFollow,
                    onBlockConfirmed = onBlockConfirmed,
                    onReportSubmitted = onReportSubmitted,
                )
        }
    }
}

@Composable
private fun ProfileContent(
    profile: UserProfile,
    followedByViewer: Boolean,
    isFollowInFlight: Boolean,
    onToggleFollow: () -> Unit,
    onBlockConfirmed: () -> Unit,
    onReportSubmitted: (ReportReasonCategory, String?) -> Unit,
) {
    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LetterAvatar(displayName = profile.displayName, username = profile.username, modifier = Modifier.size(72.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (profile.isPremium) {
                Icon(
                    painter = painterResource(Res.drawable.ic_premium_star),
                    contentDescription = stringResource(Res.string.profile_premium_badge_icon_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(Res.string.profile_premium_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = stringResource(Res.string.post_card_handle, profile.username),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!profile.bio.isNullOrEmpty()) {
            Text(
                text = profile.bio,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        // Static (non-tappable) follower / following counts — no clickable node (lists are deferred).
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = stringResource(Res.string.profile_followers_count, profile.followerCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(PROFILE_FOLLOWERS_TAG),
            )
            Text(
                text = stringResource(Res.string.profile_following_count, profile.followingCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(PROFILE_FOLLOWING_TAG),
            )
        }
        // Actions are other-user only (the endpoint's isSelf is authoritative).
        if (!profile.isSelf) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FollowButton(followedByViewer, isFollowInFlight, onToggleFollow)
                ProfileActionsMenu(
                    username = profile.username,
                    onBlock = { showBlockDialog = true },
                    onReport = { showReportDialog = true },
                )
            }
        }
    }

    if (showBlockDialog) {
        BlockConfirmDialog(
            username = profile.username,
            onConfirm = {
                showBlockDialog = false
                onBlockConfirmed()
            },
            onDismiss = { showBlockDialog = false },
        )
    }
    if (showReportDialog) {
        ReportReasonDialog(
            onSubmit = { category, note ->
                showReportDialog = false
                onReportSubmitted(category, note)
            },
            onDismiss = { showReportDialog = false },
        )
    }
}

@Composable
private fun FollowButton(
    followedByViewer: Boolean,
    isFollowInFlight: Boolean,
    onToggleFollow: () -> Unit,
) {
    if (followedByViewer) {
        OutlinedButton(
            onClick = onToggleFollow,
            enabled = !isFollowInFlight,
            modifier = Modifier.testTag(PROFILE_FOLLOW_TOGGLE_TAG),
        ) {
            Text(text = stringResource(Res.string.profile_unfollow))
        }
    } else {
        Button(
            onClick = onToggleFollow,
            enabled = !isFollowInFlight,
            modifier = Modifier.testTag(PROFILE_FOLLOW_TOGGLE_TAG),
        ) {
            Text(text = stringResource(Res.string.profile_follow))
        }
    }
}

@Composable
private fun ProfileActionsMenu(
    username: String,
    onBlock: () -> Unit,
    onReport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(PROFILE_ACTIONS_MENU_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.profile_actions_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.profile_block_action, username)) },
                onClick = {
                    expanded = false
                    onBlock()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.profile_report_action)) },
                onClick = {
                    expanded = false
                    onReport()
                },
            )
        }
    }
}

@Composable
private fun BlockConfirmDialog(
    username: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(PROFILE_BLOCK_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_block_confirm_title, username)) },
        text = { Text(stringResource(Res.string.profile_block_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.cta_block), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cta_cancel)) }
        },
    )
}

@Composable
private fun ReportReasonDialog(
    onSubmit: (ReportReasonCategory, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(ReportReasonCategory.SPAM) }
    var note by remember { mutableStateOf("") }
    // The submit is gated by the pure, unit-tested ≤200-code-point check (matches the server bound).
    val noteWithinLimit = isReportNoteWithinLimit(note)
    AlertDialog(
        modifier = Modifier.testTag(PROFILE_REPORT_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_report_reason_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ReportReasonCategory.entries.forEach { category ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == category, onClick = { selected = category })
                        Text(stringResource(category.label()))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(Res.string.profile_report_note_placeholder)) },
                    isError = !noteWithinLimit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(selected, note.ifBlank { null }) },
                // The submit is disabled past 200 Unicode code points (matching the server bound).
                enabled = noteWithinLimit,
            ) {
                Text(stringResource(Res.string.profile_report_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cta_cancel)) }
        },
    )
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
    )
}

@Composable
private fun ErrorRetry(onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(
            text = stringResource(Res.string.signin_error_network),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    }
}

private fun ReportReasonCategory.label(): StringResource =
    when (this) {
        ReportReasonCategory.SPAM -> Res.string.report_reason_spam
        ReportReasonCategory.HATE_SPEECH_SARA -> Res.string.report_reason_hate_speech_sara
        ReportReasonCategory.HARASSMENT -> Res.string.report_reason_harassment
        ReportReasonCategory.ADULT_CONTENT -> Res.string.report_reason_adult_content
        ReportReasonCategory.MISINFORMATION -> Res.string.report_reason_misinformation
        ReportReasonCategory.OTHER -> Res.string.report_reason_other
    }

private fun ProfileMessage.resource(): StringResource =
    when (this) {
        ProfileMessage.FOLLOW_RATE_LIMITED -> Res.string.profile_follow_rate_limited
        ProfileMessage.TARGET_UNAVAILABLE -> Res.string.profile_action_user_unavailable
        ProfileMessage.BLOCK_SUCCESS -> Res.string.profile_block_success_toast
        ProfileMessage.BLOCK_RATE_LIMITED -> Res.string.profile_block_rate_limited
        ProfileMessage.REPORT_SUCCESS -> Res.string.profile_report_success_toast
        ProfileMessage.REPORT_DUPLICATE -> Res.string.profile_report_duplicate
        ProfileMessage.REPORT_RATE_LIMITED -> Res.string.profile_report_rate_limited
        ProfileMessage.ACTION_FAILED -> Res.string.profile_action_failed
    }

private fun ProfileMessage.formatArgs(): Array<Any> = emptyArray()
