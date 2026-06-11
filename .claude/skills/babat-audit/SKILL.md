---
name: babat-audit
description: Work ONE remaining item from the 2026-06-10 holistic-audit backlog end-to-end — pick (or take the named item), route it to the right delivery shape (OpenSpec vs regular PR), execute under the docs/11 rails + verification gates, then update the backlog state. Use when the user says "/babat-audit", "babat satu item audit", "kerjakan item audit berikutnya", or names a specific audit leftover (05-#5, #214, ...). When the last item falls, this skill also performs the audit-directory cleanup.
---

Eat the 2026-06-10 audit backlog one item per invocation. One item = one branch = one PR. Never start a second item in the same session.

## 0 — Claim survey (always, before picking)

Same discipline as `/next-change`: `gh pr list --state open`, `git worktree list`, `gh issue list --label follow-up --state open`. If an item is already claimed by an open PR or a sibling worktree branch, skip it and take the next. Work from a fresh branch off `origin/main`.

## 1 — Resolve the backlog

Two sources of truth (check both — the second disappears after cleanup):

1. **Open `follow-up` issues from the audit:** [#210](https://github.com/aditrioka/nearyou-id/issues/210) feed self-visibility · [#211](https://github.com/aditrioka/nearyou-id/issues/211)+[#196](https://github.com/aditrioka/nearyou-id/issues/196) social-list contract · [#212](https://github.com/aditrioka/nearyou-id/issues/212) batched-Lua limiter · [#214](https://github.com/aditrioka/nearyou-id/issues/214) auth rate limits. ([#213](https://github.com/aditrioka/nearyou-id/issues/213) is NEVER picked standalone — it ships inside the future chat change.)
2. **`dev/audits/2026-06-10-holistic-audit/`** — PROGRESS.md § "Remaining after wave 7" + the full fix sketches in `findings/05` + `findings/06`. If the directory is already deleted, the sketches live forever in the PR [#209](https://github.com/aditrioka/nearyou-id/pull/209) diff.

**The menu** (id → what → sketch → shape → constraint):

| Item | What | Sketch | Shape | Constraint |
|---|---|---|---|---|
| `#196+#211` | Social lists embed profile summaries + constant-404 alignment | issue bodies | OpenSpec (one change) | MUST land before the profile/follow screens |
| `05-#5` | 5 remember-only screens → entry-scoped ViewModels | findings/05 #5 | OpenSpec | PostDetail+PostCreation first (user drafts); FOLD IN 05-#6/#7 (stateIn + koinViewModel) — same surface |
| `#214` | App-level auth-endpoint rate limits | issue body | OpenSpec (amends rate-limit-infrastructure) | client 429 mapping ships in the SAME change |
| `#210` | Shadow-banned author feed self-visibility | issue body | OpenSpec (timeline spec deltas) | watch the cursor indexes (no de-indexing OR) |
| `05-#16` | TokenRefresher follower-CE translation | findings/05 #16 | regular fix PR + test | small |
| `05-#11` | `ui/components/` extraction (list-state kit + post card) | findings/06 duplication map | regular refactor PR | fold in 05-#9 (shell unread VM) + 05-#12 (LocationGate) if touching those files anyway; update docs/11 Pattern Registry |
| `D6` | `screens/` package restructure (mechanical moves only) | docs/11 §2.1 | regular PR | LAST mobile item (avoids churn conflicts) |
| `#212` | Batched-Lua timeline limiter | issue body | OpenSpec (spec amendment mandated) | opportunistic — bundle when touching the limiter |
| `R8-smoke` | Release-build runtime smoke on a physical device | verify-loop §B | no PR — verification evidence only | REQUIRED before the first distributed release build |

**No argument given → recommended order:** `#196+#211` if profile/follow screens are imminent, else `05-#5`; then `#214` → `#210` → `05-#16` → `05-#11` → `D6`; `#212` opportunistic; `R8-smoke` whenever a release approaches. State the pick + reason, then proceed (do not stop to ask).

## 2 — Execute under the rails

- Read `docs/11-Engineering-Standards.md` (the relevant §) BEFORE coding — non-negotiable.
- OpenSpec-shaped items: `/opsx:propose` → `/opsx:apply` → `/opsx:archive` (one PR carries the lifecycle). Regular-PR items: branch `fix/...`/`refactor/...`, normal review.
- Gates before every push: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + (mobile) `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`. Do NOT run the gate while a local `:backend:ktor:run` is alive (Postgres connection budget — see verify-loop § Known blockers).
- UI-affecting → the manual verification gate (verify-loop §B/§C, screenshot evidence in the PR body) before archive/merge. K/N-touching → `:mobile:app:iosSimulatorArm64Test`.

## 3 — Close the loop (the part that keeps the backlog honest)

After the item's PR merges:

1. Close its issue (or comment progress if partially shipped). For findings-sourced items: edit PROGRESS.md § Remaining — move the item to a "✔ shipped via PR #N" note (commit rides the same PR when possible, else a tiny docs commit).
2. Re-list the backlog (step 1 sources). **If NOTHING remains** (issues closed; Remaining empty except #213-with-chat):
   - Delete `dev/audits/2026-06-10-holistic-audit/` entirely (history + PR #209 diff preserve it),
   - Fix the one live code reference: the `AUDIT-FLAGGED` comment in `Application.kt`'s RemoteConfig wiring (point it at the PR instead of the file),
   - Ship as a tiny `chore(audit): retire audit artifacts — backlog empty` PR. Mention this skill can be deleted in the same PR or kept dormant.
3. Tell the operator: what shipped, what the backlog still holds, and the recommended next `/babat-audit` pick.

## Self-improving rule

New blocker / wrong sketch / stale constraint discovered while eating an item → fix it HERE (the menu row or the rails) before finishing, same as verify-loop.
