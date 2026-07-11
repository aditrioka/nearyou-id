# Tasks: post-detail-tap-to-profile

## 1. Screen wiring

- [x] 1.1 `PostDetailScreen`: add `onOpenProfile: (userId: String) -> Unit = {}` param; add `POST_DETAIL_HEADER_PROFILE_TAG` / `POST_DETAIL_REPLY_PROFILE_TAG` constants
- [x] 1.2 `PostHeader`: nullable `onOpenProfile: (() -> Unit)?` param; `clickable` + test tag on the identity Row iff non-null; caller builds it via `authorUserId?.let { id -> { onOpenProfile(id) } }` (design D2); update the KDoc's "NOT a tap target" sentence
- [x] 1.3 `ReplyCard`: `onOpenProfile: () -> Unit` on the identity Row (renders only with a wire identity — design D3) + test tag; caller passes `{ onOpenProfile(reply.authorId) }`
- [x] 1.4 `AppEntryProvider` `PostDetailRoute` entry: wire `onOpenProfile = { userId -> backStack.add(ProfileRoute(userId)) }`

## 2. Tests

- [x] 2.1 `PostDetailScreenTest`: header identity tap fires `onOpenProfile` with the freshness-read `authorUserId`
- [x] 2.2 `PostDetailScreenTest`: degraded freshness read (`Unavailable`) → header identity not tappable (no fire)
- [x] 2.3 `PostDetailScreenTest`: reply identity tap fires `onOpenProfile` with the reply's `authorId`; no identity → no reply tap target (tag absent); no-UUID-in-tree assertion still holds

## 3. Verification & lifecycle

- [x] 3.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`
- [x] 3.2 Manual verify on emulator (verify-loop §B): tap header identity → profile opens; tap reply identity → profile opens; screenshot evidence into the PR body (docs/11 §5 DoD)
- [ ] 3.3 PR title/body current; `Closes #455`; archive via /opsx:archive
