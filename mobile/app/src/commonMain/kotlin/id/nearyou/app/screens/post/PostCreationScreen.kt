package id.nearyou.app.screens.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.PostCreationOutcome
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_privacy_shield
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.post_create_char_counter
import id.nearyou.resources.generated.resources.post_create_content_placeholder
import id.nearyou.resources.generated.resources.post_create_error_empty
import id.nearyou.resources.generated.resources.post_create_error_location
import id.nearyou.resources.generated.resources.post_create_error_moderated
import id.nearyou.resources.generated.resources.post_create_error_rate_limited
import id.nearyou.resources.generated.resources.post_create_error_too_long
import id.nearyou.resources.generated.resources.post_create_loading
import id.nearyou.resources.generated.resources.post_create_location_chip
import id.nearyou.resources.generated.resources.post_create_location_unavailable
import id.nearyou.resources.generated.resources.post_create_privacy_note
import id.nearyou.resources.generated.resources.post_create_title
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.theme.locationPin
import id.nearyou.resources.theme.locationPinContainer
import id.nearyou.resources.theme.onLocationPinContainer
import id.nearyou.resources.theme.success
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the multiline content field — lets the screen test target it for text input. */
const val POST_CONTENT_FIELD_TAG: String = "postContentField"

/**
 * The first content-creation surface — the post composer
 * ([PostCreationRoute][id.nearyou.app.screens.routing.PostCreationRoute]), appended to the back stack
 * by the home-surface FAB. Renders a title, a multiline content field, the coral location chip
 * (static label — no city/coordinate) + the UU-PDP privacy note (mockup frame 6,
 * mobile-mockup-visual-conformance), a live Unicode-code-point `N/280` counter in the bottom bar,
 * an outcome-driven error banner, and a "Posting" CTA, all under `NearYouTheme`
 * (applied by the host) with every string via `stringResource` (zero literals).
 *
 * Layering (design D2): injects the [CreatePostFlow] seam (production binding: `CreatePostRepository`)
 * and [LocationPermissionController] (for the denial banner's settings deep link); holds `content` /
 * `outcome` / `inFlight` in `remember`; projects them through the pure [postCreationUiState]. The
 * coordinate is acquired and the POST issued entirely inside `flow.submit(content)` — this screen
 * never sees the `LatLng` (PII discipline, design D7): no coordinate is rendered and the success
 * path pops without echoing the actual location. Automatic-location-only — there is deliberately NO
 * map, draggable pin, manual coordinate-entry field, or place search (design D1 / #144).
 *
 * On `Success` the composer invokes [onPostCreated], wired by
 * [appEntryProvider][id.nearyou.app.screens.routing.appEntryProvider] to `backStack.removeLastOrNull()`
 * (the Nav3 equivalent of pop) to return to the home surface.
 */
@Composable
fun PostCreationScreen(onPostCreated: () -> Unit) {
    val flow = koinInject<CreatePostFlow>()
    val permissionController = koinInject<LocationPermissionController>()
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<PostCreationOutcome?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    val uiState = postCreationUiState(content = content, outcome = outcome, inFlight = inFlight)

    // Submit (CTA + retry both call this). Re-entrancy is blocked while a submit is in flight;
    // `inFlight` is reset in `finally` so the CTA never sticks on "loading" even if the launch
    // job is cancelled (screen disposal / config change). The coordinate + POST live in submit().
    val onSubmit: () -> Unit = {
        if (!inFlight) {
            // Claim the in-flight slot SYNCHRONOUSLY: setting it inside the launch
            // left a same-frame double-tap window where both taps passed the guard
            // and double-POSTed the non-idempotent create (2026-06-10 audit, 05-#10).
            inFlight = true
            scope.launch {
                try {
                    outcome = flow.submit(content)
                } finally {
                    inFlight = false
                }
            }
        }
    }

    // Navigate from an effect (never mutate the back stack during composition). On Success the
    // composer pops back to the home surface (via onPostCreated → backStack.removeLastOrNull());
    // Nearby auto-refresh on return is deferred (mobile-post-creation-refresh-nearby-on-return) —
    // NO Nearby reload (and no Nav3 ResultEventBus signal) is emitted here.
    LaunchedEffect(outcome) {
        if (outcome is PostCreationOutcome.Success) {
            onPostCreated()
        }
    }

    val ctaText =
        if (uiState.loading) {
            stringResource(Res.string.post_create_loading)
        } else {
            stringResource(Res.string.cta_post)
        }

    val bannerText: String? =
        uiState.banner?.let { banner ->
            when (banner) {
                PostCreationBanner.CONTENT_EMPTY -> stringResource(Res.string.post_create_error_empty)
                PostCreationBanner.CONTENT_TOO_LONG -> stringResource(Res.string.post_create_error_too_long)
                PostCreationBanner.LOCATION_OUT_OF_BOUNDS -> stringResource(Res.string.post_create_error_location)
                PostCreationBanner.CONTENT_REJECTED -> stringResource(Res.string.post_create_error_moderated)
                PostCreationBanner.LOCATION_UNAVAILABLE -> stringResource(Res.string.post_create_location_unavailable)
                PostCreationBanner.NETWORK -> stringResource(Res.string.signin_error_network)
                PostCreationBanner.RATE_LIMITED -> stringResource(Res.string.post_create_error_rate_limited)
            }
        }

    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The content area scrolls when the banner + retry control + IME leave too little room
        // (the bottom bar below stays pinned and reachable — the CTA must never be pushed
        // off-screen by an open keyboard).
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(Res.string.post_create_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text(text = stringResource(Res.string.post_create_content_placeholder)) },
                enabled = !uiState.loading,
                minLines = 4,
                modifier = Modifier.fillMaxWidth().testTag(POST_CONTENT_FIELD_TAG),
            )
            // Frame 6 .chip.loc — the coral location chip (32dp / radius 8 / 12dp pad / 6dp gap per
            // the measurement annex). STATIC label only: no reverse-geocoding is shipped and the PII
            // discipline (D7) forbids rendering the coordinate, so no city/coordinate appears here.
            Surface(
                color = MaterialTheme.colorScheme.locationPinContainer,
                contentColor = MaterialTheme.colorScheme.onLocationPinContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_post_location),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.locationPin,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(Res.string.post_create_location_chip),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            // Frame 6 .privacy-note — the UU-PDP fuzzing-transparency note (15dp success-tinted
            // verified_user shield + 12sp onSurfaceVariant text, 8dp gap per the annex).
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_privacy_shield),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.success,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(Res.string.post_create_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (bannerText != null) {
                Text(
                    text = bannerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The LocationUnavailable banner offers the OS-settings deep link (the terminal-denial
                // path forward); NetworkError/Error offer a retry that re-submits. The other banners
                // (content_empty / too_long / out_of_bounds / moderated) are resolved by re-editing +
                // re-tapping the CTA, so they carry no extra control.
                when (uiState.banner) {
                    PostCreationBanner.LOCATION_UNAVAILABLE ->
                        OutlinedButton(
                            onClick = { permissionController.openAppSettings() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(Res.string.location_open_settings))
                        }
                    PostCreationBanner.NETWORK ->
                        OutlinedButton(
                            onClick = onSubmit,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(Res.string.cta_retry))
                        }
                    else -> Unit
                }
            }
        }
        // Frame 6 .composer-bar — the counter + CTA are the pinned bottom bar (the weighted
        // scrollable content column above keeps them on-screen even with the banner + retry +
        // IME present; the attachment icons in the mockup's bar are media — Month 6 roadmap —
        // and are deliberately NOT rendered).
        Text(
            text = stringResource(Res.string.post_create_char_counter, uiState.charCount),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (uiState.overLimit) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSubmit,
            enabled = uiState.submitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = ctaText)
        }
    }
}
