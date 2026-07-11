package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import id.nearyou.app.screens.appeal.AppealScreen
import id.nearyou.app.screens.auth.AgeGateScreen
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.chat.ChatThreadScreen
import id.nearyou.app.screens.chat.ConversationListScreen
import id.nearyou.app.screens.chat.ConversationPickerScreen
import id.nearyou.app.screens.consent.ConsentScreen
import id.nearyou.app.screens.followlist.FollowListScreen
import id.nearyou.app.screens.paywall.PaywallScreen
import id.nearyou.app.screens.post.EditPostScreen
import id.nearyou.app.screens.post.PostCreationScreen
import id.nearyou.app.screens.post.PostDetailScreen
import id.nearyou.app.screens.profile.ProfileScreen
import id.nearyou.app.screens.referral.ReferralScreen
import id.nearyou.app.screens.search.SearchScreen
import id.nearyou.app.screens.settings.BlockedUsersScreen
import id.nearyou.app.screens.settings.ConsentSettingsScreen
import id.nearyou.app.screens.settings.SettingsScreen
import id.nearyou.app.screens.shell.AppShellScreen
import id.nearyou.app.screens.username.UsernameCustomizationScreen
import org.koin.compose.koinInject

/**
 * Maps each [NavKey] route to its screen composable, wiring every screen's narrow navigation lambdas
 * (design Decision 6) to back-stack operations on [backStack]. Auth-boundary transitions use
 * [replaceAll] (clear-and-set — no back-navigation across the boundary); in-auth transitions use
 * `add()` (push) / `removeLastOrNull()` (pop):
 *
 *  - [RootRoute] → [RootRouterScreen] — token-presence routing → `replaceAll(HomeRoute/SignInRoute)`.
 *  - [SignInRoute] → [SignInScreen] — success → `replaceAll(HomeRoute)`; 404 no-account → `add(AgeGateRoute)`.
 *  - [HomeRoute] → [AppShellScreen] — the bottom-nav section shell (Home / Notifikasi / Profil); the Home
 *    section hosts the feed tab host + composer FAB → `add(PostCreationRoute)`, and a feed card tap →
 *    `add(PostDetailRoute(...))` — both on the root stack (above the shell).
 *  - [AgeGateRoute] → [AgeGateScreen] — success → `replaceAll(ConsentRoute)`; account-exists / absent
 *    identity → `replaceAll(SignInRoute)`.
 *  - [ConsentRoute] → [ConsentScreen] — done (Success or post-failure skip) → `replaceAll(HomeRoute)`.
 *  - [PostCreationRoute] → [PostCreationScreen] — success → `removeLastOrNull()`.
 *  - [PostDetailRoute] → [PostDetailScreen] — back → `removeLastOrNull()` (pop off the root stack).
 *
 * Adding a new screen requires only declaring its `NavKey` (NavKeys.kt) + one `entry<…>` mapping
 * here (`mobile-app-scaffold` § "Typed navigation host with start destination").
 */
fun appEntryProvider(backStack: NavBackStack<NavKey>): (NavKey) -> NavEntry<NavKey> =
    entryProvider {
        entry<RootRoute> {
            RootRouterScreen(
                onAuthenticated = { backStack.replaceAll(HomeRoute) },
                onUnauthenticated = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<SignInRoute> {
            // mobile-session-expiry-and-proactive-refresh (D5) — on sign-in success, return the user to
            // the destination captured before an involuntary terminal-401 re-route (else HomeRoute), then
            // clear the holder. A fresh-launch sign-in has no captured destination → restores HomeRoute.
            val pendingReturnDestination = koinInject<PendingReturnDestination>()
            SignInScreen(
                onSignedIn = {
                    val destination = pendingReturnDestination.peek()
                    pendingReturnDestination.clear()
                    backStack.restoreAfterReauth(destination)
                },
                onNoAccount = { backStack.add(AgeGateRoute) },
                // content-moderation-appeal: a banned/suspended sign-in stashes the appeal token in
                // AppealSession (AuthRepository) and surfaces this entry → push the appeal surface.
                onOpenAppeal = { backStack.add(AppealRoute) },
            )
        }
        entry<HomeRoute> {
            // The bottom-nav section shell is the authenticated root entry (mobile-home-tab-host §
            // "Bottom navigation is a top-level section shell"). The shell hosts HomeScreen for the Home
            // section; BOTH root-stack pushes live HERE at the call site (the shell + HomeScreen hold no
            // back-stack reference, mirroring the composer FAB): the composer FAB → PostCreationRoute,
            // and a feed card tap → PostDetailRoute (built from the tapped card's non-PII
            // [PostDetailTarget] — NO latitude/longitude — appended above the shell so the detail overlays
            // the section bar). This absorbs #159's onOpenPost wiring through the shell (design D9 /
            // tasks.md 14.5): the call site moved from HomeScreen to AppShellScreen, which forwards
            // onOpenPost to the Home section's HomeScreen.
            AppShellScreen(
                onOpenComposer = { backStack.add(PostCreationRoute) },
                // mobile-chat-screen (task 10.1): the Home brand app-bar "Pesan" action pushes the
                // conversation list onto the root stack (overlaying the section bar, like PostDetailRoute).
                onOpenChat = { backStack.add(ConversationListRoute) },
                // Notification chat_message deep-link (mobile-notifications-deep-link-targets): push the
                // thread directly atop the shell (the VM resolved the partner identity via user-profile-read).
                onOpenChatThread = { conversationId, partnerUsername, partnerDisplayName ->
                    backStack.add(
                        ChatThreadRoute(
                            conversationId = conversationId,
                            partnerUsername = partnerUsername,
                            partnerDisplayName = partnerDisplayName,
                        ),
                    )
                },
                onOpenPost = { target ->
                    backStack.add(
                        PostDetailRoute(
                            postId = target.postId,
                            content = target.content,
                            cityName = target.cityName,
                            distanceM = target.distanceM,
                            createdAtIso = target.createdAtIso,
                            likedByViewer = target.likedByViewer,
                            replyCount = target.replyCount,
                            authorUsername = target.authorUsername,
                            authorDisplayName = target.authorDisplayName,
                            // image-attached-posts: carry the tapped card's image URL so detail renders it
                            // with no by-id re-fetch (null = text-only).
                            imageUrl = target.imageUrl,
                        ),
                    )
                },
                // The cards' reply shortcut (mobile-inline-post-actions): the SAME detail push with
                // focusReplyComposer = true, so the entry autofocuses the reply composer. The
                // whole-card onOpenPost above keeps the default false.
                onOpenPostReply = { target ->
                    backStack.add(
                        PostDetailRoute(
                            postId = target.postId,
                            content = target.content,
                            cityName = target.cityName,
                            distanceM = target.distanceM,
                            createdAtIso = target.createdAtIso,
                            likedByViewer = target.likedByViewer,
                            replyCount = target.replyCount,
                            authorUsername = target.authorUsername,
                            authorDisplayName = target.authorDisplayName,
                            focusReplyComposer = true,
                            // image-attached-posts: same image URL carry-through as the whole-card open.
                            imageUrl = target.imageUrl,
                        ),
                    )
                },
                // The card identity tap (mobile-profile): push the author's profile onto the root stack.
                // The host receives the resolved authorUserId (the screens resolve it from the VM's raw
                // DTO outcome — never on the PII-free card model); ProfileRoute carries only that id.
                onOpenProfile = { authorUserId -> backStack.add(ProfileRoute(authorUserId)) },
                // The Home brand app bar's search action (mobile-search) → push the parameterless
                // SearchRoute onto the root stack (above the shell, overlaying the section bar). Same
                // call-site mechanism as onOpenComposer; the shell + app bar hold no back-stack reference.
                onOpenSearch = { backStack.add(SearchRoute) },
                // The self-profile section's settings gear (mobile-settings, #288) → push the parameterless
                // SettingsRoute onto the root stack (above the shell). Same call-site mechanism as the
                // others; AppShellScreen forwards this to ProfileScreen's onSettings on the Profil section.
                onOpenSettings = { backStack.add(SettingsRoute) },
                // The self profile's tappable follower/following counts (mobile-follow-lists): push the
                // tabbed list onto the root stack at the tapped count's tab. The self ProfileScreen resolves
                // its own userId from the session and supplies it here; the route carries only userId + tab.
                onOpenFollowList = { followUserId, tab -> backStack.add(FollowListRoute(followUserId, tab)) },
                // mobile-paywall-screen (#235): the like-cap upsell dialog's "Aktifkan Premium" CTA (threaded
                // from the dialog → timeline screens → HomeScreen → here) pushes the paywall onto the root stack.
                onActivatePremium = { backStack.add(PaywallRoute(PaywallEntry.LIKE_CAP)) },
            )
        }
        entry<AgeGateRoute> {
            AgeGateScreen(
                onSignedUp = { backStack.replaceAll(ConsentRoute) },
                onExitToSignIn = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<ConsentRoute> {
            // ConsentRoute REPLACES AgeGateRoute (the signup-success transition uses `replaceAll`,
            // not `add`), so the back stack holds [ConsentRoute] with no AgeGateRoute beneath —
            // back-press cannot re-enter the age gate. onDone (Success or the post-failure skip) →
            // HomeRoute (`mobile-analytics-consent` capability).
            ConsentScreen(onDone = { backStack.replaceAll(HomeRoute) })
        }
        entry<PostCreationRoute> {
            // `removeLastOrNull()` is size-safe by construction: PostCreationRoute is only ever appended
            // ATOP HomeRoute (the home-surface FAB), so popping it leaves HomeRoute — never an empty stack
            // (which NavDisplay would reject). No defensive size guard is added, so a future misuse that
            // makes PostCreationRoute the sole entry fails loudly rather than silently no-op'ing.
            PostCreationScreen(
                onPostCreated = { backStack.removeLastOrNull() },
                // image-attached-posts: a Free viewer tapping the image-attach affordance routes to the
                // shared paywall (the IMAGE_ATTACH entry-context tailors the hero), the SearchScreen /
                // username-gate mechanism. The composer holds no back-stack reference.
                onActivatePremium = { backStack.add(PaywallRoute(PaywallEntry.IMAGE_ATTACH)) },
            )
        }
        entry<PostDetailRoute> { route ->
            // `removeLastOrNull()` is size-safe: PostDetailRoute is only ever appended ATOP HomeRoute
            // (the feed card tap), so popping it leaves HomeRoute — never an empty stack.
            PostDetailScreen(
                route = route,
                onBack = { backStack.removeLastOrNull() },
                onEditPost = { postId, content -> backStack.add(EditPostRoute(postId, content)) },
                // chat-embedded-posts: "Bagikan ke chat" pushes the conversation picker for this post.
                onShareToChat = { postId -> backStack.add(ConversationPickerRoute(postId)) },
                // post-detail-tap-to-profile: header/reply identity taps push the author's profile onto
                // the root stack — the same mechanism as the feed-card identity tap. The screen resolves
                // the UUID from the freshness read / reply wire; the route payload stays UUID-free.
                onOpenProfile = { userId -> backStack.add(ProfileRoute(userId)) },
            )
        }
        entry<EditPostRoute> { route ->
            // `removeLastOrNull()` is size-safe: EditPostRoute is only ever appended ATOP a PostDetailRoute
            // (the detail Edit affordance), so popping it reveals that detail — never an empty stack. On a
            // successful edit it pops; post-detail refreshes its content + "Diedit" label on the resumed entry.
            EditPostScreen(
                route = route,
                onBack = { backStack.removeLastOrNull() },
                onPostEdited = { backStack.removeLastOrNull() },
            )
        }
        // mobile-settings — the Settings surface + its two sub-surfaces, pushed onto the root stack above
        // the shell. SettingsRoute is reached from the settings gear on the self-profile section, wired via
        // AppShellScreen.onOpenSettings → ProfileScreen.onSettings (#288); the route→screen mappings are
        // owned here. A terminal 401 on a sub-surface routes to sign-in (replaceAll — the auth boundary).
        entry<SettingsRoute> {
            SettingsScreen(
                onBack = { backStack.removeLastOrNull() },
                // The "Ganti username" row pushes the Ganti Username surface unconditionally (the
                // route-scoped screen owns the Free/Premium gate — mobile-premium-username).
                onOpenUsernameCustomization = { backStack.add(UsernameCustomizationRoute) },
                onOpenBlocked = { backStack.add(BlockedUsersRoute) },
                onOpenConsent = { backStack.add(ConsentSettingsRoute) },
                // mobile-referral: the "Undang teman" row pushes the parameterless ReferralRoute onto the
                // root stack (above the shell, the BlockedUsersRoute / ConsentSettingsRoute mechanism).
                // Open to all tiers (design D3) — NOT a paywall divert.
                onOpenReferral = { backStack.add(ReferralRoute) },
                onLoggedOut = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<BlockedUsersRoute> {
            BlockedUsersScreen(
                onBack = { backStack.removeLastOrNull() },
                onTokenInvalid = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<ConsentSettingsRoute> {
            ConsentSettingsScreen(
                onBack = { backStack.removeLastOrNull() },
                onTokenInvalid = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<ReferralRoute> {
            // The referral surface (mobile-referral). `removeLastOrNull()` is size-safe: ReferralRoute is
            // only ever appended ATOP SettingsRoute (the "Undang teman" row), so popping it reveals
            // Settings — never an empty stack. The screen's ViewModel fetches the code+progress on entry.
            ReferralScreen(onBack = { backStack.removeLastOrNull() })
        }
        entry<AppealRoute> {
            // The ban/suspension appeal surface (mobile-appeal). Reached from the banned/suspended session
            // (the 5.4 wiring). onBack pops; a no-token / 401 / approved state routes to sign-in via
            // `replaceAll` — the appeal token is one-shot, so re-sign-in re-mints it (or picks up the lifted
            // ban). The screen reads the token from the in-memory AppealSession holder.
            AppealScreen(
                onBack = { backStack.removeLastOrNull() },
                onReSignIn = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<ProfileRoute> { route ->
            // The other-user profile overlay (mobile-profile). `removeLastOrNull()` is size-safe:
            // ProfileRoute is only ever appended ATOP HomeRoute (the card identity tap), so popping it
            // leaves HomeRoute. targetUserId = the route's resource key; onBack pops the overlay.
            // onOpenFollowList (mobile-follow-lists): the tappable counts push the tabbed list onto the
            // root stack at the tapped tab — the screen supplies the resolved profile userId.
            ProfileScreen(
                targetUserId = route.userId,
                onBack = { backStack.removeLastOrNull() },
                onOpenFollowList = { followUserId, tab -> backStack.add(FollowListRoute(followUserId, tab)) },
            )
        }
        entry<FollowListRoute> { route ->
            // The follower/following list overlay (mobile-follow-lists). `removeLastOrNull()` is size-safe:
            // FollowListRoute is only ever appended ATOP HomeRoute (a profile-count tap). A row tap pushes
            // the row user's profile onto the root stack; back pops the list.
            FollowListScreen(
                userId = route.userId,
                initialTab = route.initialTab,
                onBack = { backStack.removeLastOrNull() },
                onOpenProfile = { rowUserId -> backStack.add(ProfileRoute(rowUserId)) },
            )
        }
        entry<ConversationListRoute> {
            // The conversation list overlays the shell via the root stack (the Home app-bar "Pesan"
            // action appended it ATOP HomeRoute). A row tap pushes the thread; back pops to the shell.
            ConversationListScreen(
                onBack = { backStack.removeLastOrNull() },
                onOpenThread = { row ->
                    backStack.add(
                        ChatThreadRoute(
                            conversationId = row.conversationId,
                            partnerUsername = row.partnerUsername,
                            partnerDisplayName = row.partnerDisplayName,
                        ),
                    )
                },
            )
        }
        entry<ChatThreadRoute> { route ->
            // The thread overlays the list (appended ATOP ConversationListRoute); back pops to the list.
            ChatThreadScreen(
                route = route,
                onBack = { backStack.removeLastOrNull() },
                // chat-embedded-posts: tapping a live shared-post context card opens that post's detail.
                // The PostDetailRoute is built from the immutable snapshot's display fields (the same
                // nav-arg pattern a feed-card / search-result tap uses); the detail's /likes + /replies
                // fetches are authoritative for the defaulted likedByViewer/replyCount/distanceM.
                onOpenSharedPost = { postId, snapshot ->
                    backStack.add(
                        PostDetailRoute(
                            postId = postId,
                            content = snapshot.content,
                            cityName = snapshot.cityName,
                            distanceM = null,
                            createdAtIso = snapshot.createdAt,
                            likedByViewer = false,
                            replyCount = 0,
                            authorUsername = snapshot.authorUsername,
                            authorDisplayName = snapshot.authorDisplayName,
                        ),
                    )
                },
            )
        }
        entry<ConversationPickerRoute> { route ->
            // The share-to-chat picker overlays the post-detail it was opened from (appended ATOP it).
            // On a successful share it pops itself THEN pushes the thread (so back from the thread
            // returns to post-detail, not the picker); back from the picker pops to post-detail.
            ConversationPickerScreen(
                postId = route.postId,
                onBack = { backStack.removeLastOrNull() },
                onSelectConversation = { conversationId, partnerUsername, partnerDisplayName ->
                    backStack.removeLastOrNull()
                    backStack.add(ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName))
                },
            )
        }
        entry<SearchRoute> {
            // The Cari surface (mobile-search). `removeLastOrNull()` is size-safe: SearchRoute is only
            // ever appended ATOP HomeRoute (the app-bar search action). A result tap pushes
            // PostDetailRoute built from the hit's non-PII fields PLUS documented defaults — the search
            // wire carries no cityName/distanceM/likedByViewer/replyCount, so those default
            // ("", null, false, 0); the detail screen's /likes/count + /replies fetches are authoritative
            // (mobile-search § "A result tap opens PostDetailRoute with documented default fields").
            SearchScreen(
                onBack = { backStack.removeLastOrNull() },
                // mobile-paywall-screen (#254): the 403 Premium-gate CTA pushes the paywall.
                onActivatePremium = { backStack.add(PaywallRoute(PaywallEntry.SEARCH_GATE)) },
                onOpenPost = { hit ->
                    backStack.add(
                        PostDetailRoute(
                            postId = hit.postId,
                            content = hit.content,
                            cityName = "",
                            distanceM = null,
                            createdAtIso = hit.createdAt,
                            likedByViewer = false,
                            replyCount = 0,
                            authorUsername = hit.authorUsername,
                            authorDisplayName = hit.authorDisplayName,
                        ),
                    )
                },
            )
        }
        entry<PaywallRoute> { route ->
            // The Premium paywall (mobile-paywall, frame 17). `removeLastOrNull()` is size-safe:
            // PaywallRoute is only ever appended ATOP the surface that opened it (the cap dialog, the
            // search Premium gate, or the username gate), so popping returns there. `route.entry` tailors
            // only the hero subheadline; on a confirmed purchase the screen pops itself (onPurchaseComplete
            // defaults to onClose) and the underlying surface re-evaluates its gate on next action (design D5).
            PaywallScreen(
                entry = route.entry,
                onClose = { backStack.removeLastOrNull() },
            )
        }
        entry<UsernameCustomizationRoute> {
            // The Ganti Username surface (mobile-premium-username). `removeLastOrNull()` is size-safe:
            // UsernameCustomizationRoute is only ever appended ATOP HomeRoute (the Settings "Ganti
            // username" row), so popping it leaves the settings/home surface. The route-scoped screen
            // owns the Free/Premium gate (an on-entry self-isPremium read + the reactive 403 backstop),
            // so Settings pushes this unconditionally. onChanged pops back to Settings on a successful
            // change; the stateless ProfileFlow re-fetches the new handle on the next read.
            UsernameCustomizationScreen(
                onBack = { backStack.removeLastOrNull() },
                onChanged = { backStack.removeLastOrNull() },
                // mobile-premium-username § "The Premium gate ... routes to the paywall": the call site
                // pushes PaywallRoute with the USERNAME entry-context — the reserved entry mobile-paywall-screen
                // (#309) introduced for exactly this cross-change hook (fulfils that change's TODO(#309)).
                onActivatePremium = { backStack.add(PaywallRoute(PaywallEntry.USERNAME)) },
            )
        }
    }
