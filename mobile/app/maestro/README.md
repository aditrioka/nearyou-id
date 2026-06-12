# Mobile E2E test harness (Maestro)

AI-agent-usable, repeatable mobile UI testing for `:mobile:app`: drives the **real** app on an
emulator/simulator/device, capturing **screenshots + screen recording + logs** so an AI agent *and a
human* can validate a change from the artifacts — without reading code. The
[`verify-loop`](../../../.claude/skills/verify-loop/SKILL.md) skill calls this harness for the mobile
"drive the UI" step. Builds on the official
[Maestro AI-agent skill](https://github.com/mobile-dev-inc/Maestro/discussions/2985) (auth pre-flight,
iOS Keychain, GraalJS patterns). Tool: [Maestro](https://docs.maestro.dev/) (installed, `maestro --version` ≥ 2.0).

## Why Maestro (validated 2026)

Mainstream mobile E2E tool: works against compiled APK/IPA **without instrumentation**, reads the
**accessibility tree** (tap by element, not pixel), natively emits
[screenshots, videos, logs, JUnit + AI reports](https://docs.maestro.dev/maestro-flows/workspace-management/test-reports-and-artifacts).
Production users at this stack (Compose Multiplatform): Bitkey by Block, etc.

## Run a flow

```bash
# physical device (staging flavor):
dev/scripts/maestro-run.sh mobile/app/maestro/flows/smoke-launch.yaml --app-id id.nearyou.app.staging --record

# emulator (dev flavor + local backend on :8080):
dev/scripts/maestro-run.sh mobile/app/maestro/flows/signin-renders.yaml --app-id id.nearyou.app.dev

# whole suite, skipping Phase-2 auth-gated flows:
maestro test mobile/app/maestro/flows --exclude-tags auth-gated -e APP_ID=id.nearyou.app.staging
```

`appId` per flavor (flows declare `appId: ${APP_ID}`):

| Flavor | applicationId | Backend | Surface |
|---|---|---|---|
| `dev` | `id.nearyou.app.dev` | `http://10.0.2.2:8080` | **emulator only** (host-loopback; cleartext) |
| `staging` | `id.nearyou.app.staging` | `https://api-staging.nearyou.id` | **physical device** (real OAuth) |
| `production` | `id.nearyou.app` | prod | do not test against |

## Artifacts (for human review)

Each run writes to `mobile/app/maestro/artifacts/<flow>-<timestamp>/` (gitignored):

- `*.png` — per-step + on-failure **screenshots**
- `recording.mp4` — **screen recording** (with `--record`)
- `debug/maestro.log` — full **log** (the AI reads this to explain pass/fail)
- `report.xml` — JUnit (CI)
- AI report — Maestro's own run analysis (add `--analyze` for AI Insights, beta)

Workflow: the AI runs the flow and writes a plain-English summary; **you** open the mp4/PNGs and
validate the behavior.

## Flow inventory

| Flow | Auth? | Notes |
|---|---|---|
| `flows/smoke-launch.yaml` | no | launch + screenshot — proves the harness works |
| `flows/signin-renders.yaml` | no | unauthenticated Sign-In screen renders |
| `flows/auth/nearby-timeline.yaml` | **Phase 2** | tagged `auth-gated`; needs dev test-login |
| `flows/auth/create-post.yaml` | **Phase 2** | tagged `auth-gated`; FAB → composer |

**Phase 2 (this branch):** a `dev`-only test-login (auth bypass) injects a seeded session so
`auth-gated` flows can run past Sign-In — Google/Apple social login can't be driven by automation, so
a bypass is the standard pattern. Design is grounded + ready; backend untouched (reuses
`dev/scripts/mint-dev-jwt.sh` + `dev/scripts/seed-test-user.sh`); guard = dev flavor source-set
isolation. Why + full design: [`PHASE-2-dev-test-login.md`](./PHASE-2-dev-test-login.md).

## Selectors

Flows currently use **text selectors** (e.g. `"Posting"`, `"Buat postingan"`) — update them when UI
copy changes. The app's Compose `testTag`s (`postContentField`, `nearbyTimelineList`, …) are NOT yet
addressable by Maestro `id:` — testTags only surface to UiAutomator/Maestro when
`testTagsAsResourceId = true` is enabled at the Android Compose root. Enabling it (debug-gated) is a
small follow-up that would make all existing testTags `id:`-addressable and the flows
language-independent; deferred rather than rushed because the API was not available via the CMP
`compose-ui` artifact on the first attempt.

## CI (apply manually)

`.github/workflows/**` edits are hook-blocked for AI — copy the block in
[`ci-maestro.workflow.md`](./ci-maestro.workflow.md) into `.github/workflows/` yourself.
