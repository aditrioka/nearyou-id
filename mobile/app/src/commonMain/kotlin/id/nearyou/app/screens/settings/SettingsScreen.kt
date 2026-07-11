package id.nearyou.app.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.data.accountdeletion.AccountDeletionFlow
import id.nearyou.app.data.accountdeletion.deletionDateLabel
import id.nearyou.app.data.dataexport.DataExportFlow
import id.nearyou.app.data.dataexport.exportDeadlineLabel
import id.nearyou.app.hidedistance.HideDistanceRepository
import id.nearyou.app.privateprofile.PrivateProfileRepository
import id.nearyou.app.push.FcmTokenProvider
import id.nearyou.app.push.NotificationContentPreference
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.blocked_users_title
import id.nearyou.resources.generated.resources.consent_title
import id.nearyou.resources.generated.resources.data_export_cancel
import id.nearyou.resources.generated.resources.data_export_confirm
import id.nearyou.resources.generated.resources.data_export_dialog_body
import id.nearyou.resources.generated.resources.data_export_dialog_title
import id.nearyou.resources.generated.resources.data_export_error
import id.nearyou.resources.generated.resources.data_export_expired
import id.nearyou.resources.generated.resources.data_export_failed
import id.nearyou.resources.generated.resources.data_export_in_progress
import id.nearyou.resources.generated.resources.data_export_ready_banner
import id.nearyou.resources.generated.resources.data_export_ready_open
import id.nearyou.resources.generated.resources.delete_account_banner
import id.nearyou.resources.generated.resources.delete_account_banner_cancel
import id.nearyou.resources.generated.resources.delete_account_cancel
import id.nearyou.resources.generated.resources.delete_account_confirm
import id.nearyou.resources.generated.resources.delete_account_dialog_body
import id.nearyou.resources.generated.resources.delete_account_dialog_title
import id.nearyou.resources.generated.resources.delete_account_error
import id.nearyou.resources.generated.resources.ic_alternate_email
import id.nearyou.resources.generated.resources.ic_block
import id.nearyou.resources.generated.resources.ic_credit_card
import id.nearyou.resources.generated.resources.ic_description
import id.nearyou.resources.generated.resources.ic_lock
import id.nearyou.resources.generated.resources.ic_logout
import id.nearyou.resources.generated.resources.ic_nav_notifications
import id.nearyou.resources.generated.resources.ic_nav_profile
import id.nearyou.resources.generated.resources.ic_person_add
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_privacy_shield
import id.nearyou.resources.generated.resources.ic_workspace_premium
import id.nearyou.resources.generated.resources.settings_coming_soon
import id.nearyou.resources.generated.resources.settings_hide_distance_error
import id.nearyou.resources.generated.resources.settings_hide_distance_premium_only
import id.nearyou.resources.generated.resources.settings_logout_body
import id.nearyou.resources.generated.resources.settings_logout_cancel
import id.nearyou.resources.generated.resources.settings_logout_confirm
import id.nearyou.resources.generated.resources.settings_logout_title
import id.nearyou.resources.generated.resources.settings_private_profile_error
import id.nearyou.resources.generated.resources.settings_private_profile_premium_only
import id.nearyou.resources.generated.resources.settings_row_change_username
import id.nearyou.resources.generated.resources.settings_row_change_username_sub
import id.nearyou.resources.generated.resources.settings_row_chat_preview
import id.nearyou.resources.generated.resources.settings_row_data_export
import id.nearyou.resources.generated.resources.settings_row_data_export_sub
import id.nearyou.resources.generated.resources.settings_row_delete_account
import id.nearyou.resources.generated.resources.settings_row_edit_profile
import id.nearyou.resources.generated.resources.settings_row_hide_distance
import id.nearyou.resources.generated.resources.settings_row_hide_distance_sub
import id.nearyou.resources.generated.resources.settings_row_invite_friends
import id.nearyou.resources.generated.resources.settings_row_invite_friends_sub
import id.nearyou.resources.generated.resources.settings_row_legal
import id.nearyou.resources.generated.resources.settings_row_logout
import id.nearyou.resources.generated.resources.settings_row_manage_subscription
import id.nearyou.resources.generated.resources.settings_row_manage_subscription_sub
import id.nearyou.resources.generated.resources.settings_row_premium_journey
import id.nearyou.resources.generated.resources.settings_row_premium_journey_sub
import id.nearyou.resources.generated.resources.settings_row_privacy_data_sub
import id.nearyou.resources.generated.resources.settings_row_private_profile
import id.nearyou.resources.generated.resources.settings_row_private_profile_sub
import id.nearyou.resources.generated.resources.settings_section_account
import id.nearyou.resources.generated.resources.settings_section_other
import id.nearyou.resources.generated.resources.settings_section_premium
import id.nearyou.resources.generated.resources.settings_section_privacy
import id.nearyou.resources.generated.resources.settings_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * The Terms / Privacy Policy URL opened by the LAINNYA row. A non-secret string constant (the project's
 * "URLs in source, secrets in Secret Manager" posture) — opened via Compose's [LocalUriHandler], no new
 * dependency. The page's existence is a content/ops concern, not this change's.
 */
private const val LEGAL_URL: String = "https://nearyou.id/kebijakan-privasi"

/**
 * The Settings surface (`mobile-settings` capability — mockup frame 16 "Pengaturan"). A pushed root-stack
 * overlay owning its own Scaffold + back bar, rendering the grouped AKUN / PREMIUM / PRIVASI / LAINNYA
 * list. **Backed** rows act: "Privasi & data" → [onOpenConsent], "Pengguna diblokir" → [onOpenBlocked],
 * the legal row opens [LEGAL_URL], "Keluar" → a confirm dialog → best-effort server revoke, then the
 * client-side token wipe → [onLoggedOut] (logout-revocation: the wipe never waits on a failed call).
 * **Deferred** rows (Premium/DESIGN — no backend yet) render their mockup chrome but on activation show a
 * non-trapping "Segera hadir" snackbar and perform NO backend write and NO navigation. The PRIVASI
 * toggles are now BACKED: "Sembunyikan jarak" (the `hide-distance` capability, `PATCH /api/v1/user/hide-distance`)
 * and "Profil privat" (the `private-profile` capability, `PATCH /api/v1/user/private-profile` — the
 * sanctioned `user_settings` privacy-flag write; the interactive write goes through that endpoint ONLY).
 * Both are Premium-gated (Free callers see the upsell + issue no write) and revert on a failed PATCH.
 * "Tampilkan preview pesan chat di notifikasi" is a third, DEVICE-LOCAL switch (all tiers, no backend —
 * mobile-notification-preview-toggle) writing through `NotificationContentPreference` only. All
 * copy via `:shared:resources`, under `NearYouTheme`.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenUsernameCustomization: () -> Unit = {},
    onOpenBlocked: () -> Unit,
    onOpenConsent: () -> Unit,
    onOpenReferral: () -> Unit = {},
    onLoggedOut: () -> Unit,
) {
    val tokenStore = koinInject<TokenStore>()
    val hideDistanceRepository = koinInject<HideDistanceRepository>()
    val privateProfileRepository = koinInject<PrivateProfileRepository>()
    // logout-revocation: fail-safe resolution (the TimelineAds getOrNull idiom) — a screen test not
    // exercising the server logout binds neither; the VM degrades to the client-side-only wipe.
    val koin = getKoin()
    val logoutAuthApi = remember(koin) { koin.getOrNull<AuthApiClient>() }
    val logoutFcmTokenProvider = remember(koin) { koin.getOrNull<FcmTokenProvider>() }
    // mobile-notification-preview-toggle: same fail-safe resolution — a test not wiring the
    // preference gets an inert OFF row, never a resolution crash.
    val notificationContentPreference = remember(koin) { koin.getOrNull<NotificationContentPreference>() }
    val viewModel =
        viewModel {
            SettingsViewModel(
                tokenStore,
                hideDistanceRepository,
                privateProfileRepository,
                logoutAuthApi,
                logoutFcmTokenProvider,
                notificationContentPreference,
            )
        }
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()
    val hideDistanceChecked by viewModel.hideDistanceChecked.collectAsStateWithLifecycle()
    val hideDistanceEvent by viewModel.hideDistanceEvent.collectAsStateWithLifecycle()
    val privateProfileChecked by viewModel.privateProfileChecked.collectAsStateWithLifecycle()
    val privateProfileEvent by viewModel.privateProfileEvent.collectAsStateWithLifecycle()
    val chatPreviewChecked by viewModel.chatPreviewChecked.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val comingSoonText = stringResource(Res.string.settings_coming_soon)
    val hideDistanceUpsellText = stringResource(Res.string.settings_hide_distance_premium_only)
    val hideDistanceErrorText = stringResource(Res.string.settings_hide_distance_error)
    val privateProfileUpsellText = stringResource(Res.string.settings_private_profile_premium_only)
    val privateProfileErrorText = stringResource(Res.string.settings_private_profile_error)
    var showLogoutDialog by remember { mutableStateOf(false) }

    // account-deletion-tombstone — the "Hapus Akun" + restore-banner state holder, NavEntry-scoped.
    val accountDeletionFlow = koinInject<AccountDeletionFlow>()
    val accountDeletionVm = viewModel { SettingsAccountDeletionViewModel(accountDeletionFlow) }
    val deletionBanner by accountDeletionVm.banner.collectAsStateWithLifecycle()
    val deletionTerminal401 by accountDeletionVm.terminal401.collectAsStateWithLifecycle()
    val deletionRequestError by accountDeletionVm.requestError.collectAsStateWithLifecycle()
    val deletionCancelError by accountDeletionVm.cancelError.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val deleteErrorText = stringResource(Res.string.delete_account_error)

    // mobile-data-export-entry — the "Unduh Data Saya" request + status holder, NavEntry-scoped.
    val dataExportFlow = koinInject<DataExportFlow>()
    val dataExportVm = viewModel { SettingsDataExportViewModel(dataExportFlow) }
    val dataExportState by dataExportVm.uiState.collectAsStateWithLifecycle()
    val dataExportTerminal401 by dataExportVm.terminal401.collectAsStateWithLifecycle()
    val dataExportRequestError by dataExportVm.requestError.collectAsStateWithLifecycle()
    val dataExportStatusError by dataExportVm.statusError.collectAsStateWithLifecycle()
    var showDataExportDialog by remember { mutableStateOf(false) }
    val dataExportErrorText = stringResource(Res.string.data_export_error)

    LaunchedEffect(loggedOut) {
        if (loggedOut) onLoggedOut()
    }
    // A 401 on any data-export call is terminal → route to sign-in (same destination as logout).
    LaunchedEffect(dataExportTerminal401) {
        if (dataExportTerminal401) onLoggedOut()
    }
    // Request / status-read failures surface a non-trapping snackbar; the screen stays functional.
    LaunchedEffect(dataExportRequestError) {
        if (dataExportRequestError) {
            snackbarHostState.showSnackbar(dataExportErrorText)
            dataExportVm.consumeRequestError()
        }
    }
    LaunchedEffect(dataExportStatusError) {
        if (dataExportStatusError) {
            snackbarHostState.showSnackbar(dataExportErrorText)
            dataExportVm.consumeStatusError()
        }
    }
    // A 401 on any account-deletion call is terminal → route to sign-in (same destination as logout).
    LaunchedEffect(deletionTerminal401) {
        if (deletionTerminal401) onLoggedOut()
    }
    // Request / cancel failures surface a non-trapping snackbar (the cancel keeps its banner).
    LaunchedEffect(deletionRequestError) {
        if (deletionRequestError) {
            snackbarHostState.showSnackbar(deleteErrorText)
            accountDeletionVm.consumeRequestError()
        }
    }
    LaunchedEffect(deletionCancelError) {
        if (deletionCancelError) {
            snackbarHostState.showSnackbar(deleteErrorText)
            accountDeletionVm.consumeCancelError()
        }
    }

    // hide-distance one-shot events → a non-trapping snackbar, then cleared (docs/11 §2.2 event-as-state).
    LaunchedEffect(hideDistanceEvent) {
        when (hideDistanceEvent) {
            HideDistanceEvent.Upsell -> {
                snackbarHostState.showSnackbar(hideDistanceUpsellText)
                viewModel.onHideDistanceEventShown()
            }
            HideDistanceEvent.WriteFailed -> {
                snackbarHostState.showSnackbar(hideDistanceErrorText)
                viewModel.onHideDistanceEventShown()
            }
            null -> Unit
        }
    }

    // private-profile one-shot events → a non-trapping snackbar, then cleared (docs/11 §2.2 event-as-state).
    LaunchedEffect(privateProfileEvent) {
        when (privateProfileEvent) {
            PrivateProfileEvent.Upsell -> {
                snackbarHostState.showSnackbar(privateProfileUpsellText)
                viewModel.onPrivateProfileEventShown()
            }
            PrivateProfileEvent.WriteFailed -> {
                snackbarHostState.showSnackbar(privateProfileErrorText)
                viewModel.onPrivateProfileEventShown()
            }
            null -> Unit
        }
    }

    val onComingSoon: () -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(comingSoonText) }
    }

    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(Res.string.settings_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()),
        ) {
            // account-deletion-tombstone — non-blocking restore banner when a deletion is scheduled.
            deletionBanner?.let { banner ->
                DeletionScheduledBanner(
                    restoreByDate = deletionDateLabel(banner.scheduledHardDeleteAt),
                    onCancel = { accountDeletionVm.cancelDeletion() },
                )
            }

            // mobile-data-export-entry — non-blocking ready banner when an export is ready to download. The
            // signed downloadUrl is held only transiently in UI state and handed to the platform open-URL
            // affordance (the same LocalUriHandler path the legal row uses) — never persisted, never logged.
            (dataExportState as? DataExportUiState.Ready)?.let { ready ->
                DataExportReadyBanner(
                    deadlineDate = exportDeadlineLabel(ready.downloadExpiresAt),
                    onOpen = { uriHandler.openUri(ready.downloadUrl) },
                )
            }

            // AKUN — "Edit profil" deferred (no profile-write backend); "Ganti username" is BACKED
            // (mobile-premium-username): the row pushes UsernameCustomizationRoute UNCONDITIONALLY — the
            // route-scoped screen owns the Free/Premium gate, so Settings reads no isPremium signal here.
            SettingsSectionHeader(stringResource(Res.string.settings_section_account))
            SettingsRow(
                icon = Res.drawable.ic_nav_profile,
                title = stringResource(Res.string.settings_row_edit_profile),
                onClick = onComingSoon,
            )
            SettingsRow(
                icon = Res.drawable.ic_alternate_email,
                title = stringResource(Res.string.settings_row_change_username),
                subtitle = stringResource(Res.string.settings_row_change_username_sub),
                onClick = onOpenUsernameCustomization,
            )

            // PREMIUM — deferred (Phase 4 tenure + billing).
            SettingsSectionHeader(stringResource(Res.string.settings_section_premium))
            SettingsRow(
                icon = Res.drawable.ic_workspace_premium,
                title = stringResource(Res.string.settings_row_premium_journey),
                subtitle = stringResource(Res.string.settings_row_premium_journey_sub),
                onClick = onComingSoon,
            )
            SettingsRow(
                icon = Res.drawable.ic_credit_card,
                title = stringResource(Res.string.settings_row_manage_subscription),
                subtitle = stringResource(Res.string.settings_row_manage_subscription_sub),
                onClick = onComingSoon,
            )

            // PRIVASI — "Profil privat" + "Sembunyikan jarak" are backed Premium toggles, the
            // chat-preview row is a backed device-local toggle; "Privasi & data" + "Pengguna
            // diblokir" navigate.
            SettingsSectionHeader(stringResource(Res.string.settings_section_privacy))
            // "Profil privat" is BACKED (private-profile capability): a Premium-gated toggle wired to
            // PATCH /api/v1/user/private-profile (the sanctioned `user_settings` privacy-flag write —
            // the interactive write goes through this endpoint ONLY). Free callers get the Premium upsell
            // (no write); the VM gates on the GET's `premium` flag and reverts on a failed PATCH.
            SettingsRow(
                icon = Res.drawable.ic_lock,
                title = stringResource(Res.string.settings_row_private_profile),
                subtitle = stringResource(Res.string.settings_row_private_profile_sub),
                onClick = { viewModel.onPrivateProfileToggle(!privateProfileChecked) },
                trailing = SettingsRowTrailing.SwitchControl,
                switchChecked = privateProfileChecked,
                onSwitchChange = { newValue -> viewModel.onPrivateProfileToggle(newValue) },
            )
            // "Sembunyikan jarak" is BACKED (hide-distance capability): a Premium-gated toggle wired to
            // PATCH /api/v1/user/hide-distance. Free callers get the Premium upsell (no write); the VM
            // gates on the GET's `premium` flag and reverts on a failed PATCH.
            SettingsRow(
                icon = Res.drawable.ic_post_location,
                title = stringResource(Res.string.settings_row_hide_distance),
                subtitle = stringResource(Res.string.settings_row_hide_distance_sub),
                onClick = { viewModel.onHideDistanceToggle(!hideDistanceChecked) },
                trailing = SettingsRowTrailing.SwitchControl,
                switchChecked = hideDistanceChecked,
                onSwitchChange = { newValue -> viewModel.onHideDistanceToggle(newValue) },
            )
            // "Tampilkan preview pesan chat di notifikasi" is a DEVICE-LOCAL toggle
            // (mobile-notification-preview-toggle, docs/03 § "User Toggle in Settings"): all tiers,
            // no backend call — the write goes through NotificationContentPreference ONLY, so on iOS
            // it lands in the App-Group suite the NSE reads. Default OFF (content-private).
            SettingsRow(
                icon = Res.drawable.ic_nav_notifications,
                title = stringResource(Res.string.settings_row_chat_preview),
                onClick = { viewModel.onChatPreviewToggle(!chatPreviewChecked) },
                trailing = SettingsRowTrailing.SwitchControl,
                switchChecked = chatPreviewChecked,
                onSwitchChange = { newValue -> viewModel.onChatPreviewToggle(newValue) },
            )
            SettingsRow(
                icon = Res.drawable.ic_privacy_shield,
                title = stringResource(Res.string.consent_title),
                subtitle = stringResource(Res.string.settings_row_privacy_data_sub),
                onClick = onOpenConsent,
            )
            SettingsRow(
                icon = Res.drawable.ic_block,
                title = stringResource(Res.string.blocked_users_title),
                onClick = onOpenBlocked,
            )

            // LAINNYA — "Undang teman" navigates to the referral surface (mobile-referral, open to ALL
            // tiers — NOT a paywall divert); legal opens the external policy URL; logout confirms then
            // wipes the token.
            SettingsSectionHeader(stringResource(Res.string.settings_section_other))
            SettingsRow(
                icon = Res.drawable.ic_person_add,
                title = stringResource(Res.string.settings_row_invite_friends),
                subtitle = stringResource(Res.string.settings_row_invite_friends_sub),
                onClick = onOpenReferral,
            )
            SettingsRow(
                icon = Res.drawable.ic_description,
                title = stringResource(Res.string.settings_row_legal),
                onClick = { uriHandler.openUri(LEGAL_URL) },
            )
            // mobile-data-export-entry — "Unduh Data Saya" (docs/03 § Data Export; absent from mockup frame
            // 16 per design D5). The subtitle is status-driven; the row opens the confirmation dialog only
            // when a fresh request is allowed (canRequest() — the single source of truth, false while the
            // seed GET is loading or while single-active in progress, so the dialog never opens then).
            SettingsRow(
                icon = Res.drawable.ic_description,
                title = stringResource(Res.string.settings_row_data_export),
                subtitle = dataExportRowSubtitle(dataExportState),
                onClick = { if (dataExportVm.canRequest()) showDataExportDialog = true },
            )
            SettingsRow(
                icon = Res.drawable.ic_logout,
                title = stringResource(Res.string.settings_row_logout),
                onClick = { showLogoutDialog = true },
                trailing = SettingsRowTrailing.None,
            )

            // account-deletion-tombstone — destructive "Hapus Akun" (docs/03 § Account Deletion;
            // absent from mockup frame 16 per design D8). Opens a 30-day-grace confirmation.
            SettingsRow(
                icon = Res.drawable.ic_block,
                title = stringResource(Res.string.settings_row_delete_account),
                onClick = { showDeleteDialog = true },
                trailing = SettingsRowTrailing.None,
            )
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(Res.string.settings_logout_title)) },
            text = { Text(stringResource(Res.string.settings_logout_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.confirmLogout()
                }) {
                    Text(stringResource(Res.string.settings_logout_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(Res.string.settings_logout_cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(Res.string.delete_account_dialog_title)) },
            text = { Text(stringResource(Res.string.delete_account_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    accountDeletionVm.confirmDeletion()
                }) {
                    Text(stringResource(Res.string.delete_account_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.delete_account_cancel))
                }
            },
        )
    }

    if (showDataExportDialog) {
        AlertDialog(
            onDismissRequest = { showDataExportDialog = false },
            title = { Text(stringResource(Res.string.data_export_dialog_title)) },
            text = { Text(stringResource(Res.string.data_export_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDataExportDialog = false
                    dataExportVm.confirmExport()
                }) {
                    Text(stringResource(Res.string.data_export_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDataExportDialog = false }) {
                    Text(stringResource(Res.string.data_export_cancel))
                }
            },
        )
    }
}

/**
 * Status-driven subtitle for the "Unduh Data Saya" row, exhaustive over [DataExportUiState]. [Loading]
 * shows the default request prompt (the seed `GET` is in flight); [InProgress] the single-active
 * "sedang diproses"; [Expired]/[Failed] their re-request note; [None]/[Ready] the default prompt (the
 * ready state's detail lives in the banner above the list). All copy via `:shared:resources`.
 */
@Composable
private fun dataExportRowSubtitle(state: DataExportUiState): String =
    when (state) {
        DataExportUiState.InProgress -> stringResource(Res.string.data_export_in_progress)
        DataExportUiState.Expired -> stringResource(Res.string.data_export_expired)
        DataExportUiState.Failed -> stringResource(Res.string.data_export_failed)
        DataExportUiState.Loading,
        DataExportUiState.None,
        is DataExportUiState.Ready,
        -> stringResource(Res.string.settings_row_data_export_sub)
    }

/**
 * Non-blocking banner shown when a data export is ready to download. States the download deadline and
 * offers an in-app affordance to open the freshly-signed `downloadUrl` (the convenience path complementing
 * the email delivery — design D2). Rendered in the primary-container tone (a non-destructive, positive cue,
 * distinct from the destructive error-container of the deletion banner).
 */
@Composable
private fun DataExportReadyBanner(
    deadlineDate: String,
    onOpen: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.data_export_ready_banner, deadlineDate),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpen) {
                Text(stringResource(Res.string.data_export_ready_open))
            }
        }
    }
}

/**
 * Non-blocking banner shown while a deletion is scheduled (in the 30-day grace). States the restore-by
 * date and offers "Batalkan" (→ `DELETE` restore). The account stays fully functional during grace — this
 * is NOT a suspension. Rendered in the error-container tone (the closest design-system destructive cue).
 */
@Composable
private fun DeletionScheduledBanner(
    restoreByDate: String,
    onCancel: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.delete_account_banner, restoreByDate),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.delete_account_banner_cancel))
            }
        }
    }
}
