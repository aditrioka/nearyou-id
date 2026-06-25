---
name: audit-burndown
description: Work ONE remaining item from the 2026-06-10 holistic-audit backlog end-to-end — pick (or take the named item), route it to the right delivery shape (OpenSpec vs regular PR), execute under the docs/11 rails + verification gates, then update the backlog state. When the last item falls, this skill performs the audit-directory cleanup. Use on "/audit-burndown", "burn down one audit item", "babat satu item audit", "kerjakan item audit berikutnya", or a named audit leftover (05-#5, #214, …). NOT for new-capability proposals (use /next-change) or general follow-up triage (use /triage-follow-ups).
---

Eat the 2026-06-10 audit backlog one item per invocation. One item = one branch = one PR. Never start a second item in the same session.

## 0 — Claim survey (always, before picking)

Same discipline as `/next-change`: `gh pr list --state open`, `git worktree list`, `gh issue list --label follow-up --state open`. Skip any item already claimed by an open PR or sibling-worktree branch; take the next. Work from a fresh branch off `origin/main`.

## 1 — Resolve the backlog

Two sources of truth (check both — the second disappears after cleanup):

1. **Open `follow-up` issues from the audit:** [#210](https://github.com/aditrioka/nearyou-id/issues/210) feed self-visibility · [#211](https://github.com/aditrioka/nearyou-id/issues/211)+[#196](https://github.com/aditrioka/nearyou-id/issues/196) social-list contract · [#212](https://github.com/aditrioka/nearyou-id/issues/212) batched-Lua limiter · [#214](https://github.com/aditrioka/nearyou-id/issues/214) auth rate limits. ([#213](https://github.com/aditrioka/nearyou-id/issues/213) is NEVER picked standalone — it ships inside the future chat change.)
2. **`dev/audits/2026-06-10-holistic-audit/`** — PROGRESS.md § "Remaining after wave 7" + fix sketches in `findings/05` + `findings/06`. If the directory is deleted, the sketches live in the PR [#209](https://github.com/aditrioka/nearyou-id/pull/209) diff.

**The menu** (id → what → sketch → shape → constraint):

| Item | What | Sketch | Shape | Constraint |
|---|---|---|---|---|
| `#196+#211` | Social lists embed profile summaries + constant-404 alignment | issue bodies | OpenSpec (one change) | MUST land before the profile/follow screens |
| ~~`05-#5`~~ | ✔ **DONE** — remember-only screens → entry-scoped ViewModels: PostCreation/PostDetail (sibling changes) + SignIn/AgeGate/Consent (PR #405) | findings/05 #5 | — | shipped; 05-#6/#7 are NO LONGER folded in (now distinct rows below) |
| `#214` | App-level auth-endpoint rate limits | issue body | OpenSpec (amends rate-limit-infrastructure) | client 429 mapping ships in the SAME change |
| `05-#16` | TokenRefresher follower-CE translation | findings/05 #16 | regular fix PR + test | small |
| `05-#11` | `ui/components/` extraction (list-state kit + post card) | findings/06 duplication map | regular refactor PR | fold in 05-#9 (shell unread VM) + 05-#12 (LocationGate) if touching those files anyway; update docs/11 Pattern Registry |
| ~~`05-#6`~~ | ✔ **DONE** — feed/list VMs → single-`stateIn` `uiState` (Nearby/Global/**Following**/Notifications = 4, not 3; PR #409 `mobile-feed-viewmodels-statein`). Following copied the old shape post-audit (so "new VMs already use stateIn" was false for the feed family); `isInitialLoad`→private, `outcome` kept as the raw-state seam. ConversationList/BlockedUsers same-shape fork deferred → #410 | findings/05 #6 | OpenSpec | shipped |
| `05-#7` | App-wide `koinViewModel()` + Koin VM-declaration conversion of all ~17 `viewModel { }` call sites | findings/05 #7 | OpenSpec | app-wide pattern decision + a docs/11 §2.2-vs-code divergence; converting a subset FORKS the convention |
| `D6` | `screens/` package restructure (mechanical moves only) | docs/11 §2.1 | regular PR | LAST mobile item (avoids churn conflicts) |
| `#212` | Batched-Lua timeline limiter | issue body | OpenSpec (spec amendment mandated) | opportunistic — bundle when touching the limiter |
| `R8-smoke` | Release-build runtime smoke on a physical device | verify-loop §B | no PR — verification evidence only | REQUIRED before the first distributed release build |

> **Shipped (closed):** `#196+#211` (PR #222), `#210` (PR #243), `05-#5` (PR #405), `05-#16` (PR #406), `05-#11` list-state half (PR #407), `#214` (PR #408), `05-#6` (PR #409), `05-#9` shell unread VM (PR #411), `05-#12` LocationGate fold (PR #412), `#410` ConversationList+BlockedUsers stateIn fold (PR #413). Still open: `#212`; `05-#7`; the `05-#11` PostDetail `Replies*`/`PostHeader` remainder (deferred in #407); `D6`; `R8-smoke` (release-time); plus the new same-shape-fork follow-up `#414` (`ChatThreadViewModel` — a 3-input projection incl. the realtime `rows` merge, surfaced while shipping #410/#413; named in neither 05-#6 nor #410).

**No argument given → recommended order:** the small clean VM folds (05-#9/05-#12/#410) are all shipped; next is `#414` (`ChatThreadViewModel` single-`stateIn` fold — small-ish, the only remaining clean VM fold, but a 3-input `combine` incl. the realtime `rows` so slightly more involved than #410) **or** `05-#7` (app-wide `koinViewModel()` — larger, and it carries an unresolved convention decision: convert to `koinViewModel()` vs amend docs/11 §2.2 to bless the de-facto `viewModel { }`; surface that to the operator before sinking a session into ~17 call sites); `#212` opportunistic (bundle when touching the limiter); `D6` LAST mobile item (package moves → churn); `R8-smoke` whenever a release approaches. State the pick + reason, then proceed (do not stop to ask).

## 2 — Execute under the rails

- Read `docs/11-Engineering-Standards.md` (the relevant §) BEFORE coding — non-negotiable.
- OpenSpec-shaped items: `/opsx:propose` → `/opsx:apply` → `/opsx:archive` (one PR carries the lifecycle). Regular-PR items: branch `fix/…`/`refactor/…`, normal review.
- Gates before every push: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + (mobile) `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`. Do NOT run the gate while a local `:backend:ktor:run` is alive (Postgres connection budget — see verify-loop § Known blockers).
- UI-affecting → manual verification gate (verify-loop §B/§C, screenshot evidence in the PR body) before archive/merge. K/N-touching → `:mobile:app:iosSimulatorArm64Test`.

## 3 — Close the loop (keeps the backlog honest)

After the item's PR merges:

1. Close its issue (or comment progress if partially shipped). For findings-sourced items: edit PROGRESS.md § Remaining — move the item to a "✔ shipped via PR #N" note (commit rides the same PR when possible, else a tiny docs commit).
2. Re-list the backlog (step 1 sources). **If NOTHING remains** (issues closed; Remaining empty except #213-with-chat):
   - Delete `dev/audits/2026-06-10-holistic-audit/` entirely (history + PR #209 diff preserve it),
   - Fix the one live code reference: the `AUDIT-FLAGGED` comment in `Application.kt`'s RemoteConfig wiring (point it at the PR instead of the file),
   - Ship as a tiny `chore(audit): retire audit artifacts — backlog empty` PR. Mention this skill can be deleted in the same PR or kept dormant.
3. Tell the operator: what shipped, what the backlog still holds, the recommended next pick.

## Safety

All mutation lands on a fresh feature branch + PR — never push to `main`, never `--no-verify`. Don't run the gradle gate while a local `:backend:ktor:run` is alive. The directory-delete in step 3.2 fires ONLY when the backlog is verified empty (history + PR #209 preserve it).

## Self-improving rule

New blocker / wrong sketch / stale constraint discovered while eating an item → fix it HERE (the menu row or the rails) before finishing, same as verify-loop.
