package id.nearyou.app.screens.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.image.ImagePicker
import id.nearyou.app.image.ImageUploader
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.PostCreationOutcome
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_action_clear
import id.nearyou.resources.generated.resources.ic_post_image
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_privacy_shield
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.post_create_attach_image
import id.nearyou.resources.generated.resources.post_create_attach_image_cd
import id.nearyou.resources.generated.resources.post_create_char_counter
import id.nearyou.resources.generated.resources.post_create_content_placeholder
import id.nearyou.resources.generated.resources.post_create_error_empty
import id.nearyou.resources.generated.resources.post_create_error_location
import id.nearyou.resources.generated.resources.post_create_error_moderated
import id.nearyou.resources.generated.resources.post_create_error_rate_limited
import id.nearyou.resources.generated.resources.post_create_error_too_long
import id.nearyou.resources.generated.resources.post_create_image_error_feature_disabled
import id.nearyou.resources.generated.resources.post_create_image_error_moderation_rejected
import id.nearyou.resources.generated.resources.post_create_image_error_network
import id.nearyou.resources.generated.resources.post_create_image_error_premium_required
import id.nearyou.resources.generated.resources.post_create_image_error_quota_exceeded
import id.nearyou.resources.generated.resources.post_create_image_error_throttled
import id.nearyou.resources.generated.resources.post_create_image_error_too_large
import id.nearyou.resources.generated.resources.post_create_image_error_unavailable
import id.nearyou.resources.generated.resources.post_create_image_remove_cd
import id.nearyou.resources.generated.resources.post_create_image_uploading
import id.nearyou.resources.generated.resources.post_create_loading
import id.nearyou.resources.generated.resources.post_create_location_chip
import id.nearyou.resources.generated.resources.post_create_location_unavailable
import id.nearyou.resources.generated.resources.post_create_privacy_note
import id.nearyou.resources.generated.resources.post_create_title
import id.nearyou.resources.generated.resources.post_image_alt
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.theme.locationPin
import id.nearyou.resources.theme.locationPinContainer
import id.nearyou.resources.theme.onLocationPinContainer
import id.nearyou.resources.theme.success
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the multiline content field — lets the screen test target it for text input. */
const val POST_CONTENT_FIELD_TAG: String = "postContentField"

/** Test tag on the image-attach affordance (`image-attached-posts`, mockup frame 6 image button). */
const val POST_ATTACH_IMAGE_TAG: String = "postAttachImage"

/** Test tag on the in-composer image preview thumbnail (present only on a successful upload). */
const val POST_IMAGE_PREVIEW_TAG: String = "postImagePreview"

/** Test tag on the remove-image affordance (present only while an image is previewed). */
const val POST_IMAGE_REMOVE_TAG: String = "postImageRemove"

/**
 * The first content-creation surface — the post composer
 * ([PostCreationRoute][id.nearyou.app.screens.routing.PostCreationRoute]), appended to the back stack
 * by the home-surface FAB. Renders a title, a multiline content field, the coral location chip
 * (static label — no city/coordinate) + the UU-PDP privacy note (mockup frame 6,
 * mobile-mockup-visual-conformance), a **Premium image-attach affordance** (image-attached-posts,
 * the activated mockup frame 6 image button) with its two-step upload → preview/remove sub-UI, a live
 * Unicode-code-point `N/280` counter in the bottom bar, an outcome-driven error banner, and a "Posting"
 * CTA, all under `NearYouTheme` (applied by the host) with every string via `stringResource` (zero literals).
 *
 * Layering (design D2): observes a route-scoped [PostCreationViewModel] (production deps: [CreatePostFlow],
 * [ImagePicker], [ImageUploadRepository], [ProfileFlow], [SelfUserIdProvider]) that owns content / the
 * create lifecycle / the on-entry self-Premium gate / the image-attach two-step flow. The coordinate is
 * acquired and the POST issued entirely inside `flow.submit(...)` — this screen never sees the `LatLng`
 * (PII discipline, design D7): no coordinate is rendered and the success path pops without echoing the
 * actual location. The image bytes never reach this screen (the VM uploads them; only the opaque delivery
 * URL reaches the thumbnail). Automatic-location-only — there is deliberately NO map, draggable pin, manual
 * coordinate-entry field, or place search (design D1 / #144).
 *
 * Gating (mobile-image-attachment): the attach affordance routes a Premium viewer to the OS picker and a
 * Free viewer to the shared paywall (the hoisted [onActivatePremium], wired by `appEntryProvider` to
 * `PaywallRoute(IMAGE_ATTACH)`) — the picker is NEVER invoked for a Free viewer. No Firebase Remote Config
 * client is introduced; the backend stays the authority on the `image_upload_enabled` flag (a
 * `FeatureDisabled` upload outcome renders the "not available yet" state).
 *
 * On `Success` the composer invokes [onPostCreated], wired by
 * [appEntryProvider][id.nearyou.app.screens.routing.appEntryProvider] to `backStack.removeLastOrNull()`
 * (the Nav3 equivalent of pop) to return to the home surface.
 */
@Composable
fun PostCreationScreen(
    onPostCreated: () -> Unit,
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<CreatePostFlow>()
    val imagePicker = koinInject<ImagePicker>()
    val imageUploader = koinInject<ImageUploader>()
    val profileFlow = koinInject<ProfileFlow>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    val permissionController = koinInject<LocationPermissionController>()

    val viewModel =
        viewModel {
            PostCreationViewModel(flow, imagePicker, imageUploader, profileFlow, selfUserIdProvider)
        }
    val content by viewModel.content.collectAsStateWithLifecycle()
    val outcome by viewModel.createOutcome.collectAsStateWithLifecycle()
    val inFlight by viewModel.createInFlight.collectAsStateWithLifecycle()
    val imageAttach by viewModel.imageAttach.collectAsStateWithLifecycle()
    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()
    val routeToPaywall by viewModel.routeToPaywall.collectAsStateWithLifecycle()

    val uiState =
        postCreationUiState(
            content = content,
            outcome = outcome,
            inFlight = inFlight,
            imageUploading = imageAttach is ImageAttachUiState.Uploading,
        )

    // Navigate from an effect (never mutate the back stack during composition). On Success the
    // composer pops back to the home surface (via onPostCreated → backStack.removeLastOrNull());
    // Nearby auto-refresh on return is deferred (mobile-post-creation-refresh-nearby-on-return) —
    // NO Nearby feed signal is emitted here.
    LaunchedEffect(outcome) {
        if (outcome is PostCreationOutcome.Success) {
            onPostCreated()
        }
    }
    // The Free-viewer upsell one-shot (mobile-image-attachment): when the VM raises routeToPaywall (a Free
    // viewer tapped attach — the picker was NOT invoked), route to the shared paywall via the host, then
    // clear the flag. Mirrors the timeline cap dialog's onActivatePremium hand-off.
    LaunchedEffect(routeToPaywall) {
        if (routeToPaywall) {
            onActivatePremium()
            viewModel.onPaywallRouted()
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
                onValueChange = viewModel::onContentChange,
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
            // image-attached-posts (mockup frame 6 image button, now activated): the Premium image-attach
            // affordance + its two-step sub-UI. The affordance renders once the on-entry Premium status is
            // resolved (isPremium != null — a sub-second window; the rest of the composer is usable
            // immediately). The Free/Premium decision lives in the VM's onAttachClick — Premium opens the
            // picker, Free routes to the paywall and never opens the picker. Below the affordance, the picked
            // image's lifecycle: in-progress → thumbnail preview + remove → or a mapped error message.
            ImageAttachSection(
                state = imageAttach,
                isPremiumResolved = isPremium != null,
                onAttach = viewModel::onAttachClick,
                onRemove = viewModel::onRemoveImage,
            )
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
                            onClick = viewModel::onSubmit,
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
        // IME present).
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
            onClick = viewModel::onSubmit,
            enabled = uiState.submitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = ctaText)
        }
    }
}

/**
 * The image-attach affordance + its two-step sub-UI (mobile-image-attachment). The attach button is always
 * shown; the [state] drives what renders beneath it:
 *  - [ImageAttachUiState.None] — just the attach affordance.
 *  - [ImageAttachUiState.Uploading] — a spinner + the uploading label; the CTA is disabled upstream.
 *  - [ImageAttachUiState.Attached] — a Coil thumbnail preview + a remove affordance.
 *  - [ImageAttachUiState.Failed] — the mapped error message (the attachment is cleared, so a post can never
 *    reference a failed image) + the attach affordance to retry.
 * Every string is `stringResource` (no literals).
 */
@Composable
private fun ImageAttachSection(
    state: ImageAttachUiState,
    isPremiumResolved: Boolean,
    onAttach: () -> Unit,
    onRemove: () -> Unit,
) {
    when (state) {
        is ImageAttachUiState.Attached ->
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = state.deliveryUrl,
                    contentDescription = stringResource(Res.string.post_image_alt),
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MaterialTheme.shapes.medium)
                            .testTag(POST_IMAGE_PREVIEW_TAG),
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd).testTag(POST_IMAGE_REMOVE_TAG),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_action_clear),
                        contentDescription = stringResource(Res.string.post_create_image_remove_cd),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        ImageAttachUiState.Uploading ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(Res.string.post_create_image_uploading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        is ImageAttachUiState.Failed -> {
            Text(
                text = uploadErrorMessage(state.error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPremiumResolved) ImageAttachButton(onAttach = onAttach)
        }
        // The attach affordance appears once the on-entry Premium status resolves (isPremiumResolved) — the
        // tap can then deterministically open the picker (Premium) or route to the paywall (Free).
        ImageAttachUiState.None -> if (isPremiumResolved) ImageAttachButton(onAttach = onAttach)
    }
}

/** The tappable "Tambah gambar" affordance (icon + label). The gate (Premium → picker, Free → paywall)
 *  lives in the VM, so this is a plain trigger. */
@Composable
private fun ImageAttachButton(onAttach: () -> Unit) {
    OutlinedButton(onClick = onAttach, modifier = Modifier.testTag(POST_ATTACH_IMAGE_TAG)) {
        Icon(
            painter = painterResource(Res.drawable.ic_post_image),
            contentDescription = stringResource(Res.string.post_create_attach_image_cd),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(Res.string.post_create_attach_image),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Maps each [ImageUploadError] to its distinct user-facing message (mobile-image-attachment: every
 *  `ImageUploadOutcome` renders a distinct state). Exhaustive — no `else`. */
@Composable
private fun uploadErrorMessage(error: ImageUploadError): String =
    when (error) {
        ImageUploadError.PREMIUM_REQUIRED -> stringResource(Res.string.post_create_image_error_premium_required)
        ImageUploadError.FEATURE_DISABLED -> stringResource(Res.string.post_create_image_error_feature_disabled)
        ImageUploadError.QUOTA_EXCEEDED -> stringResource(Res.string.post_create_image_error_quota_exceeded)
        ImageUploadError.THROTTLED -> stringResource(Res.string.post_create_image_error_throttled)
        ImageUploadError.MODERATION_REJECTED -> stringResource(Res.string.post_create_image_error_moderation_rejected)
        ImageUploadError.TOO_LARGE -> stringResource(Res.string.post_create_image_error_too_large)
        ImageUploadError.UNAVAILABLE -> stringResource(Res.string.post_create_image_error_unavailable)
        ImageUploadError.NETWORK -> stringResource(Res.string.post_create_image_error_network)
    }
