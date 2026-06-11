# Design: mobile-timeline-card-redesign

## Context

The three timeline endpoints ship a per-post DTO with `authorUserId` but no display identity; the mobile cards (`NearbyPostCard` / `GlobalPostCard`, near-verbatim duplicates per audit 05-#11, plus the `PostHeader` partial third copy in `PostDetailScreen`) render content/city/distance/counts only. The canonical mockup (frames 1 + 19, `dev/mockups/nearyou-screens-mockup.html`, rendered per docs/11 § 2.8 at proposal time) defines the target card: letter avatar, display name + @username + time, content, coral pin + city + distance, with a centered brand-logo app bar above the feed tabs. `users.username` / `users.display_name` are NOT NULL since V2; `visible_posts` (V20) already hides posts whose author is shadow-banned/deleted; all three canonical queries already `JOIN visible_users` inside the reply-count LATERAL.

Constraints: 16 code-level invariants (`openspec/project.md` § Coding Conventions — shadow-ban `visible_*` views, block-exclusion joins, CMP Resources strings), docs/11 § 2 mobile contracts + § 3 backend contracts, the one-PR-per-change lifecycle, and the mobile-first priority (backend work allowed as a mobile dependency).

## Goals / Non-Goals

**Goals:**

- Author display identity (`authorUsername`, `authorDisplayName`) on all three timeline responses, sourced shadow-ban-safely, zero schema migration, additive wire.
- ONE shared post-card composable in `ui/components/` (docs/11 § 2.1 first occupant) consumed by Nearby + Global; identity also rendered on the post-detail header via the existing nav-args path.
- Centered brand-logo app bar on the shell's single Scaffold (Home section).
- Keep PII discipline: `author_user_id` UUID + raw coordinates never rendered/logged.
- Absorb the post-card half of audit 05-#11.

**Non-Goals:**

- Inline like/reply/send action row (issue #201), radius slider, profile navigation from avatar/name (issue #196 — card identity is non-tappable; whole-card tap → detail stays), media/images (Month 6), relative timestamps, Premium name shimmer, kebab/block/report (issue #200), 4th "Pesan" nav section, the list-state-kit half of 05-#11, guest timelines, infinite scroll.

## Decisions

### D1 — Wire names: bare camelCase `authorUsername` + `authorDisplayName`

Declared explicitly (per docs/11 § 2.6 wire-truth rule): bare camelCase, no `@SerialName`. Precedents: the sibling identity field `authorUserId` is bare camelCase in all three shipped timeline DTOs (`TimelineRoutes.kt`), and the shipped profile endpoint serializes `username` / `displayName` bare camelCase (`UserProfileRoutes.kt:77-78`). Alternatives rejected: snake_case `username` / `display_name` (matches only the *stale* JSON examples in old spec prose — the documented drift trap, PR #128); bare `username` / `displayName` (ambiguous next to `authorUserId`; the `author` prefix keeps the post-scoped semantics obvious). Non-null `String`s — the V2 columns are NOT NULL.

### D2 — Identity via `JOIN visible_users u ON u.id = p.author_id` (INNER)

The same view the reply-count LATERAL already joins. Shadow-ban invariant holds (never raw `users` in business reads). INNER (not LEFT) is safe and self-consistent: `visible_posts` already excludes posts whose author is shadow-banned/deleted, so the join cannot drop additional rows — it is belt-and-suspenders against the two views ever drifting. Lint mechanics (PR #207 precedent): `BlockExclusionJoinRule` does not fire on `visible_users` and `RawFromPostsRule` matches `posts` only → no allowlist annotations needed; block enforcement remains the existing bidirectional `user_blocks` NOT-IN predicates on `p.author_id`. One query, no N+1 (docs/11 § 3.2). `docs/05` § Timeline Implementation gains the join + two SELECT columns in all three blocks, same PR.

### D3 — Shared card is a NEW capability (`mobile-post-card`) born in `ui/components/`

The card contract gets one owner spec instead of three drifting per-screen copies (the 05-#11 failure mode). `ui/components/` is created as the docs/11 § 2.1 target-shape package (design-system composables shared by ≥2 screens); Nearby + Global consume it now and delete their local copies; Following/profile/search MODIFY-by-consuming later (the `mobile-design-system` pattern). The post-detail `PostHeader` is NOT switched to the card composable (different surface: no card container, no whole-surface tap) but renders the same identity fields from the route payload — full header unification stays in the remaining 05-#11 item. Alternative rejected: duplicating card requirements into each screen spec (re-creates the drift this change exists to stop).

### D4 — Letter avatar: initials from `authorDisplayName`, deterministic tonal container

- **Initials**: first Unicode code point of the first word + first code point of the last word (single-word name → its first code point), uppercased — code-point-based so surrogate-pair names don't crash; testable in commonTest.
- **Color**: deterministic hash of `authorUsername` → one of the M3 tonal container pairs from `NearYouTheme` (`primaryContainer`, `secondaryContainer`, `tertiaryContainer` + matching `on*Container` content color). The mockup shows a wider hue variety, but mockup colors are CSS approximations and docs/11 § 2.8 + the no-hex-literals rule give theme tokens precedence — 3 token pairs is the largest palette expressible without inventing colors. Deterministic by username (stable across sessions/feeds), not random per composition.
- No image avatars anywhere yet (no profile-photo capability), so the letter avatar IS the avatar contract for now.

### D5 — App bar: `CenterAlignedTopAppBar` in the shell Scaffold's `topBar` slot, Home section only

The shell (`AppShellScreen`) owns the app's single Scaffold; the app bar lands in its `topBar` slot — insets still applied exactly once, screens stay Scaffold/TopAppBar-free, so the `mobile-design-system` single-Scaffold requirement is preserved — conscious MODIFY in two strokes: the flush-tab-row scenario rewords to "flush under the shell app bar," and the requirement gains an explicit scoping sentence (the topBar rule governs shell-rendered section surfaces; root-stack overlay screens, e.g. `PostDetailScreen`'s back bar per audit 06-#4, keep their own chrome). Logo: `logo_brand_light.xml` / `logo_brand_dark.xml` selected by the active theme (the existing light/dark selection mechanism in `:shared:resources` consumers), `contentDescription = stringResource(Res.string.app_name)`. Pinned (no scroll-collapse) — matches the static mockup; collapse behavior would be new motion surface with no spec backing. Scoped to the Home section: Notifikasi/Profil keep their existing in-body headers (their specs untouched). Alternatives rejected: app bar inside `HomeScreen` body (violates the design-system "screens declare no TopAppBar" letter and re-derives inset logic); app bar on all sections (drags `mobile-notifications-list` into scope for zero demo value).

### D6 — `onOpenPost` payload + `PostDetailRoute` gain the two fields, defaulted `""`

The detail header renders from nav args only (shipped `mobile-post-detail` — no single-post GET exists), so identity must travel the payload: card → hoisted `onOpenPost` → `PostDetailRoute(authorUsername, authorDisplayName, …)` → header. Both fields `String` with default `""`: `PostDetailRoute` is a polymorphic-registered serialized NavKey, and defaults keep a previously-serialized back stack (process-death restore) decodable; an empty value renders the header without the identity row (graceful, test-covered). Still no `latitude`/`longitude` on the route (PII rule unchanged).

### D7 — What the card keeps vs. defers (no dead controls)

Keeps: read-only like/reply counts (existing spec requirement; restyled into the mockup's bottom-row position, NOT tappable — no ripple/role, so nothing looks interactive), `postDateLabel` time value (relative "5 mnt" format stays deferred with its own change; the mockup's relative times are a flagged divergence — specs/docs govern behavior, mockup governs layout), whole-card tap → post detail, `city_name` empty-string tolerance, Nearby-only distance via `DistanceRenderer`. Defers (rendered in mockup, intentionally absent here): action row, kebab, media block, Premium shimmer, avatar/name tap.

The time label moves into the identity header as **plain text** (after the @-handle) — the clock glyph is dropped per mockup frames 1/19. Since the mockup governs look (docs/11 § 2.8 precedence), the `mobile-design-system` Material-icons requirement and `docs/03-UX-Design.md` § canonical glyph list both lose the card "time (clock)" entry in this same PR (declared docs amendment, not silent drift).

### Standards conformance (docs/11)

Builds on Pattern Registry patterns unchanged: mobile state holder (existing ViewModels; no new state patterns), Navigation 3 (payload NavKey + polymorphic registration per § 2.3), data layer (ApiClient DTO + Repository mapping per § 2.6, wire-truth from `TimelineRoutes.kt`), components package per § 2.1; backend layering Routes → Service → Repository (§ 3.1) and single-query JDBC discipline (§ 3.2). **No deviations → no docs/11 Pattern Registry amendment needed.** Mockup consultation per § 2.8 performed (frames 1 + 19 rendered via headless Chrome at proposal time). No `gradle/libs.versions.toml` touch → the propose-time substrate WebSearch gate is N/A.

## Risks / Trade-offs

- **[Hot-path query cost]** Adding a PK-equality `JOIN visible_users` to the three timeline queries → negligible (PK join, columns NOT NULL); integration tests keep asserting page shape; no new indexes needed.
- **[Wire growth on every post row]** ~40-110 bytes/post → acceptable; additive; no pagination change.
- **[Shared card becomes a god-composable]** Mitigation: contract scoped to today's slots (identity/content/meta/counts) with nullable distance; action-row slot lands with #201, not speculatively now.
- **[Robolectric release-variant failures]** New/updated `*ScreenTest`s must stay in the Release-variant exclude; gate runs `testDevDebugUnitTest` + `testDevReleaseUnitTest` (PR #126 precedent).
- **[Restored back stack predating the new fields]** Defaults `""` (D6) → header degrades gracefully instead of crashing on decode.
- **[Mockup-vs-spec divergence confusion]** Mockup shows action row/media/shimmer/kebab/relative-time; the spec deltas name each as deferred so reviewers don't read them as missed scope.

## Migration Plan

Single PR, single squash-merge (one-PR lifecycle): backend fields land additively (old clients unaffected — `ignoreUnknownKeys`), mobile consumes in the same merge; `main` auto-deploys staging. No flag, no migration, no rollback step beyond revert-the-merge.

## Open Questions

None blocking. (Avatar palette intentionally capped at the 3 M3 container pairs until a brand-extended palette exists in `NearYouColorScheme`; revisit only if design adds tokens.)
