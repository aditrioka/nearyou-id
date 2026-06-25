package id.nearyou.app.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.data.block.BlockedUser
import id.nearyou.app.data.block.BlockedUsersFlow
import id.nearyou.app.data.block.BlockedUsersOutcome
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.blocked_users_empty
import id.nearyou.resources.generated.resources.blocked_users_title
import id.nearyou.resources.generated.resources.blocked_users_unblock
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable block list — lets the screen test target rows and the empty/error states. */
const val BLOCKED_USERS_LIST_TAG: String = "blockedUsersList"

/**
 * The block-list management surface (`mobile-settings` § "Block-list management") — Settings › Privasi ›
 * "Pengguna diblokir". A pushed root-stack overlay owning its own Scaffold + back bar. Lists the viewer's
 * outbound blocks (`GET /api/v1/blocks`) via a `BlockedUsersRoute`-scoped [BlockedUsersViewModel] and
 * unblocks (`DELETE /api/v1/blocks/{userId}`). Each row shows the blocked user's display name + @handle —
 * NEVER the UUID (PII discipline). A terminal `401` routes to sign-in via [onTokenInvalid]; an unblock
 * failure keeps the row and shows a non-trapping snackbar (NOT an optimistic drop). All copy via
 * `:shared:resources`, under `NearYouTheme`.
 */
@Composable
fun BlockedUsersScreen(
    onBack: () -> Unit,
    onTokenInvalid: () -> Unit,
) {
    val flow = koinInject<BlockedUsersFlow>()
    val viewModel = viewModel { BlockedUsersViewModel(flow) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Kept alongside uiState only for the terminal-401 → sign-in navigation side-effect below (the raw
    // domain-state seam); the rendered state is the single uiState, not an in-composable re-projection.
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val unblockError by viewModel.unblockError.collectAsStateWithLifecycle()
    val unblocking by viewModel.unblocking.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val unblockErrorText = stringResource(Res.string.signin_error_network)

    // Terminal 401 → route to sign-in (the SessionInvalidator re-route is already in flight).
    LaunchedEffect(outcome) {
        if (outcome is BlockedUsersOutcome.TokenInvalid) onTokenInvalid()
    }
    // One-shot unblock-failure snackbar (row kept) → then clear the flag.
    LaunchedEffect(unblockError) {
        if (unblockError) {
            snackbarHostState.showSnackbar(unblockErrorText)
            viewModel.consumeUnblockError()
        }
    }

    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(Res.string.blocked_users_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            // Bind to a local so the Content branch can smart-cast (uiState is a delegated property).
            when (val state = uiState) {
                BlockedUsersUiState.Loading -> CenteredLoading()
                BlockedUsersUiState.Empty -> SettingsCenteredMessage(stringResource(Res.string.blocked_users_empty))
                BlockedUsersUiState.Error -> ErrorState(onRetry = viewModel::reload)
                // Terminal 401 → neutral placeholder while the sign-in re-route lands.
                BlockedUsersUiState.SessionRedirect -> CenteredLoading()
                is BlockedUsersUiState.Content ->
                    BlockedUsersList(
                        rows = state.rows,
                        unblocking = unblocking,
                        onUnblock = viewModel::unblock,
                    )
            }
        }
    }
}

@Composable
private fun CenteredLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.timeline_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.signin_error_network),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    }
}

@Composable
private fun BlockedUsersList(
    rows: List<BlockedUser>,
    unblocking: Set<String>,
    onUnblock: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(BLOCKED_USERS_LIST_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = rows, key = { it.userId }, contentType = { "blockedUser" }) { user ->
            BlockedUserRow(
                user = user,
                inFlight = user.userId in unblocking,
                onUnblock = { onUnblock(user.userId) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * One blocked-user row: display name + @handle + an "Buka blokir" affordance. Renders NEITHER the
 * [BlockedUser.userId] UUID nor any coordinate (PII discipline). The unblock control disables while a
 * `DELETE` for this row is in flight (the VM's in-flight guard).
 */
@Composable
private fun BlockedUserRow(
    user: BlockedUser,
    inFlight: Boolean,
    onUnblock: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.post_card_handle, user.username),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onUnblock, enabled = !inFlight) {
            Text(text = stringResource(Res.string.blocked_users_unblock))
        }
    }
}
