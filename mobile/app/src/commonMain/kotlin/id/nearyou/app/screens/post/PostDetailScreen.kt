package id.nearyou.app.screens.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import id.nearyou.app.post.LikeCountOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_reply
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.post_detail_like_count
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_post_gone
import id.nearyou.resources.generated.resources.post_detail_posted_from
import id.nearyou.resources.generated.resources.post_detail_posted_from_no_city
import id.nearyou.resources.generated.resources.post_detail_replies_empty
import id.nearyou.resources.generated.resources.post_detail_reply_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_reply_counter
import id.nearyou.resources.generated.resources.post_detail_reply_placeholder
import id.nearyou.resources.generated.resources.post_detail_reset_hours
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.theme.locationPin
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the clickable like control (the toggle target). */
const val POST_DETAIL_LIKE_TOGGLE_TAG: String = "postDetailLikeToggle"

/** Test tag on the like-state indicator when LIKED (asserts the optimistic flip). */
const val POST_DETAIL_LIKE_LIKED_TAG: String = "postDetailLikeLiked"

/** Test tag on the like-state indicator when NOT liked (asserts the revert). */
const val POST_DETAIL_LIKE_NOT_LIKED_TAG: String = "postDetailLikeNotLiked"

/** Test tag on the displayed reply-count node (asserts the local +1 on a 201). */
const val POST_DETAIL_REPLY_COUNT_TAG: String = "postDetailReplyCount"

/** Test tag on the multiline reply field — lets the screen test target it for text input. */
const val POST_DETAIL_REPLY_FIELD_TAG: String = "postDetailReplyField"

/** Test tag on the back/close affordance. */
const val POST_DETAIL_BACK_TAG: String = "postDetailBack"

/** Test tag on the replies-error retry control. */
const val POST_DETAIL_REPLIES_RETRY_TAG: String = "postDetailRepliesRetry"

/**
 * The post-detail surface ([PostDetailRoute]) — "everything you do on a single post" — opened by tapping
 * a feed card and overlaid on the tab bar via the ROOT back stack (design D1). Renders, all under
 * `NearYouTheme` with every string via `stringResource` (zero literals):
 *  - the post **header** ([route].content + a "Diposting dari {city}, {date}" line; the empty-`cityName`
 *    convention `""` renders without the city fragment via `post_detail_posted_from_no_city`);
 *  - a **like control** — initial state from [PostDetailRoute.likedByViewer]; the tap flips optimistically
 *    (+/- the count when available) and reverts on a non-`Liked`/`Unliked` outcome; a 429 surfaces the
 *    like-cap upsell; the numeric count comes from `likeCount()` and degrades gracefully when unavailable;
 *  - a **replies list** (loading / empty / error states; reply cards render content + the `created_at`
 *    treatment ONLY — no `author_id`);
 *  - a **reply composer** (placeholder + a live `N/280` Unicode-code-point counter + a "Balas" CTA disabled
 *    while empty / over-limit / in-flight; a 201 appends the returned reply locally + bumps the count with
 *    NO list re-fetch; a 429 surfaces the reply-cap upsell; an `InvalidContent`/network/post-gone failure
 *    shows the generic retryable banner).
 *
 * The screen is navigation-free (design Decision 6): it holds NO back-stack reference; its back affordance
 * invokes the hoisted [onBack]. The header is built SOLELY from the [route] payload — there is NO
 * single-post by-id GET (none exists; design D2); the only outbound calls are to the like + reply
 * sub-resources. PII discipline: no `author_id` and no coordinate is rendered (the route carries no
 * coordinate, and [ReplyUi] drops `authorId`); this screen never `println`s/logs.
 */
@Composable
fun PostDetailScreen(
    route: PostDetailRoute,
    onBack: () -> Unit,
) {
    val flow = koinInject<PostDetailFlow>()
    val scope = rememberCoroutineScope()

    // Like state: initial liked from the nav arg; count + last outcome (for the cap upsell) are live.
    var liked by remember { mutableStateOf(route.likedByViewer) }
    var likeCount by remember { mutableStateOf<Long?>(null) }
    var likeInFlight by remember { mutableStateOf(false) }
    var likeOutcome by remember { mutableStateOf<LikeOutcome?>(null) }

    // Replies state: the outcome drives the loading/loaded/empty/error projection; a 201 mutates the
    // Loaded list in place (local append) so the list re-projects WITHOUT a re-fetch (design D9).
    var repliesOutcome by remember { mutableStateOf<RepliesOutcome?>(null) }
    var repliesInFlight by remember { mutableStateOf(true) }

    // Header reply count: from the nav arg; +1 on a successful reply (no re-fetch).
    var replyCount by remember { mutableStateOf(route.replyCount) }

    // Composer state.
    var replyContent by remember { mutableStateOf("") }
    var replyInFlight by remember { mutableStateOf(false) }
    var replyOutcome by remember { mutableStateOf<ReplyPostOutcome?>(null) }

    // The replies first-page load is a LaunchedEffect keyed on the post id + a retry counter, so the
    // error-state retry simply bumps the key to re-run the load (a reliably-driven composition effect,
    // not a click-scoped launch). On entry the effect runs once.
    var repliesRetryKey by remember { mutableStateOf(0) }
    LaunchedEffect(route.postId, repliesRetryKey) {
        repliesInFlight = true
        repliesOutcome = flow.loadReplies(route.postId)
        repliesInFlight = false
    }
    // The like count is fetched once on entry (no single-post GET); degrades to null when unavailable.
    LaunchedEffect(route.postId) {
        likeCount =
            when (val countOutcome = flow.likeCount(route.postId)) {
                is LikeCountOutcome.Available -> countOutcome.count
                LikeCountOutcome.Unavailable -> null
            }
    }

    val reloadReplies: () -> Unit = { repliesRetryKey++ }

    val onToggleLike: () -> Unit = {
        if (!likeInFlight) {
            val wasLiked = liked
            // Capture the exact pre-tap count so a failure restores it DIRECTLY (not via an inverse
            // delta) — the count fetch may resolve between the optimistic flip and the failure, so a
            // delta-revert could drift off-by-one from a base the optimistic step never adjusted.
            val priorCount = likeCount
            // Optimistic flip (+/- the count when shown); clear any prior cap/error banner.
            liked = !wasLiked
            likeCount = likeCount?.let { if (wasLiked) it - 1 else it + 1 }
            likeOutcome = null
            // Claimed synchronously — see onSubmitReply (05-#10 double-tap window).
            likeInFlight = true
            scope.launch {
                try {
                    when (val outcome = flow.toggleLike(route.postId, currentlyLiked = wasLiked)) {
                        LikeOutcome.Liked, LikeOutcome.Unliked -> Unit // happy path: keep the optimistic flip
                        is LikeOutcome.RateLimited, LikeOutcome.PostGone, LikeOutcome.NetworkError -> {
                            // Revert to the exact pre-tap state; surface the outcome's banner.
                            liked = wasLiked
                            likeCount = priorCount
                            likeOutcome = outcome
                        }
                    }
                } finally {
                    likeInFlight = false
                }
            }
        }
    }

    val onSubmitReply: () -> Unit = {
        if (replyComposerUiState(replyContent, replyInFlight).submitEnabled) {
            // Claim the in-flight slot SYNCHRONOUSLY: setting it inside the launch
            // left a same-frame double-tap window where both taps passed the
            // submitEnabled guard and double-POSTed the reply (05-#10).
            replyInFlight = true
            scope.launch {
                try {
                    when (val outcome = flow.postReply(route.postId, replyContent)) {
                        is ReplyPostOutcome.Success -> {
                            // Prepend the returned reply + bump the count; NO list re-fetch.
                            // The list renders newest-first (created_at DESC), so appending
                            // to the END put the user's fresh reply at the BOTTOM of page 1
                            // (2026-06-10 audit, 06 medium).
                            val current = repliesOutcome
                            if (current is RepliesOutcome.Loaded) {
                                repliesOutcome =
                                    RepliesOutcome.Loaded(listOf(outcome.reply) + current.replies, current.nextCursor)
                            } else {
                                // Replies never loaded (error/in-flight) but the POST succeeded:
                                // fabricating Loaded(listOf(reply)) here used to make the error +
                                // retry control vanish and strand the post's OTHER replies until
                                // screen re-entry (2026-06-10 audit, 06 medium). Re-fetch instead —
                                // the fresh page 1 includes the new reply at its true position.
                                reloadReplies()
                            }
                            replyCount += 1
                            replyContent = ""
                            replyOutcome = null
                        }
                        is ReplyPostOutcome.RateLimited,
                        ReplyPostOutcome.PostGone,
                        ReplyPostOutcome.InvalidContent,
                        ReplyPostOutcome.NetworkError,
                        -> replyOutcome = outcome
                    }
                } finally {
                    replyInFlight = false
                }
            }
        }
    }

    Scaffold(
        topBar = { BackBar(onBack = onBack) },
        bottomBar = {
            ReplyComposer(
                content = replyContent,
                onContentChange = { replyContent = it },
                inFlight = replyInFlight,
                banner = replyBanner(replyOutcome),
                onSubmit = onSubmitReply,
            )
        },
    ) { padding ->
        // Everything scrolls in one LazyColumn (header + like row + the replies states/list); the reply
        // composer is the fixed bottom bar. The replies retry re-runs the keyed LaunchedEffect (above),
        // so the retry control works even though its item is replaced by the Loading item on re-load.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PostHeader(
                    content = route.content,
                    cityName = route.cityName,
                    createdAtIso = route.createdAtIso,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                LikeRow(
                    liked = liked,
                    likeCount = likeCount,
                    replyCount = replyCount,
                    likeInFlight = likeInFlight,
                    banner = likeBanner(likeOutcome),
                    onToggleLike = onToggleLike,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (val repliesState = repliesUiState(repliesOutcome, repliesInFlight)) {
                RepliesUiState.Loading -> item { RepliesLoading() }
                RepliesUiState.Empty -> item { RepliesEmpty() }
                RepliesUiState.Error -> item { RepliesError(onRetry = reloadReplies) }
                is RepliesUiState.Content ->
                    items(
                        items = repliesState.replies,
                        key = { it.id },
                        contentType = { "reply" },
                    ) { reply -> ReplyCard(reply) }
            }
        }
    }
}

/** The back/close affordance (the detail overlays the feed via the root stack, so "Tutup" = close it).
 *  Invokes the hoisted [onBack] — the screen holds no back-stack reference. */
@Composable
private fun BackBar(onBack: () -> Unit) {
    // This screen owns its Scaffold (root-stack overlay), so its custom bars must
    // apply their own system-bar insets — a bare Row in the topBar slot rendered
    // under the status bar (2026-06-10 audit, finding 06-#4).
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(POST_DETAIL_BACK_TAG)) {
            Text(text = stringResource(Res.string.cta_close))
        }
    }
}

/** The post header: content + a "Diposting dari {city}, {date}" line (empty `cityName` → the no-city
 *  variant, no dangling comma). Built solely from the route payload — no `author_id`, no coordinate. */
@Composable
private fun PostHeader(
    content: String,
    cityName: String,
    createdAtIso: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val postedFrom =
            if (cityName.isEmpty()) {
                stringResource(Res.string.post_detail_posted_from_no_city, postDateLabel(createdAtIso))
            } else {
                stringResource(Res.string.post_detail_posted_from, cityName, postDateLabel(createdAtIso))
            }
        Text(
            text = postedFrom,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The like control row: a clickable brand-tinted dot (filled coral when liked, muted otherwise — the
 * interactive analogue of the read-only card dot) + the "{n} suka" count when available + a read-only
 * reply-count indicator. The like-state dot carries a state-specific testTag so the optimistic flip /
 * revert is assertable. A 429/error banner (if any) renders below.
 */
@Composable
private fun LikeRow(
    liked: Boolean,
    likeCount: Long?,
    replyCount: Int,
    likeInFlight: Boolean,
    banner: PostDetailBanner?,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Material heart icon (the feed-card idiom) — the brand-tinted placeholder
            // dot contradicted the mobile-design-system icon migration the feed cards
            // already shipped (2026-06-10 audit, finding 06-#2).
            Box(
                modifier =
                    Modifier
                        .testTag(POST_DETAIL_LIKE_TOGGLE_TAG)
                        .clickable(enabled = !likeInFlight, onClick = onToggleLike)
                        .padding(4.dp),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (liked) Res.drawable.ic_post_like_filled else Res.drawable.ic_post_like,
                        ),
                    contentDescription = stringResource(Res.string.post_detail_like_count, likeCount ?: 0),
                    tint =
                        if (liked) {
                            MaterialTheme.colorScheme.locationPin
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier
                            .size(20.dp)
                            .testTag(if (liked) POST_DETAIL_LIKE_LIKED_TAG else POST_DETAIL_LIKE_NOT_LIKED_TAG),
                )
            }
            if (likeCount != null) {
                Text(
                    text = stringResource(Res.string.post_detail_like_count, likeCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Read-only reply-count indicator (muted reply icon + the live count) — mirrors the feed
            // card's counts row; the count is the nav arg + the local +1 on a successful reply.
            Icon(
                painter = painterResource(Res.drawable.ic_post_reply),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = replyCount.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(POST_DETAIL_REPLY_COUNT_TAG),
            )
        }
        if (banner != null) {
            BannerText(banner = banner, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A single reply card — `content` + the `created_at` date treatment ONLY. NO author identity (the
 *  PII-free [ReplyUi] carries no `authorId`); a viewer's-own auto-hidden reply renders identically (the
 *  flag is parsed but not surfaced in v1). */
@Composable
private fun ReplyCard(reply: ReplyUi) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = reply.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = postDateLabel(reply.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RepliesLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.timeline_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RepliesEmpty() {
    Text(
        text = stringResource(Res.string.post_detail_replies_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}

@Composable
private fun RepliesError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.signin_error_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(POST_DETAIL_REPLIES_RETRY_TAG)) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    }
}

/**
 * The reply composer (bottom bar): a multiline field + a live `N/280` code-point counter + the "Balas"
 * CTA (disabled while empty / over-limit / in-flight, so the client never submits invalid content). A
 * 429/error [banner] (if any) renders above the CTA — for the generic [PostDetailBanner.Network] case the
 * Balas CTA itself is the retry (the field text is preserved on failure).
 */
@Composable
private fun ReplyComposer(
    content: String,
    onContentChange: (String) -> Unit,
    inFlight: Boolean,
    banner: PostDetailBanner?,
    onSubmit: () -> Unit,
) {
    val composer = replyComposerUiState(content, inFlight)
    // Bottom-bar insets: keep the composer above the nav bar AND the keyboard —
    // a bare Column in the bottomBar slot was occluded by both (06-#4).
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            placeholder = { Text(text = stringResource(Res.string.post_detail_reply_placeholder)) },
            enabled = !inFlight,
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(POST_DETAIL_REPLY_FIELD_TAG),
        )
        Text(
            text = stringResource(Res.string.post_detail_reply_counter, composer.charCount),
            style = MaterialTheme.typography.labelMedium,
            color = if (composer.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        if (banner != null) {
            BannerText(banner = banner)
        }
        Button(onClick = onSubmit, enabled = composer.submitEnabled, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(Res.string.cta_reply))
        }
    }
}

/** Renders a [PostDetailBanner] message — the cap upsells (with the coarse reset-hours countdown filling
 *  the `%1$s`), the terminal post-gone copy, or the generic retryable network copy. Always via
 *  `stringResource` (no literals). */
@Composable
private fun BannerText(
    banner: PostDetailBanner,
    modifier: Modifier = Modifier,
) {
    val message =
        when (banner) {
            is PostDetailBanner.LikeCap ->
                stringResource(
                    Res.string.post_detail_likes_cap_upsell,
                    stringResource(Res.string.post_detail_reset_hours, banner.resetHours),
                )
            is PostDetailBanner.ReplyCap ->
                stringResource(
                    Res.string.post_detail_reply_cap_upsell,
                    stringResource(Res.string.post_detail_reset_hours, banner.resetHours),
                )
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

/** ISO-8601 `created_at` → its date portion ("2026-06-06"). Pure + deterministic (no wall clock) —
 *  the same treatment the feed cards use; richer relative formatting is the deferred follow-up. */
private fun postDateLabel(createdAtIso: String): String = createdAtIso.substringBefore('T')
