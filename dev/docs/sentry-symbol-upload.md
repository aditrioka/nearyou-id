# Sentry symbolication upload — operator runbook

`mobile-sentry-crash-reporting` ships crash/error reporting via `:infra:sentry`. Reports work
**out of the box** but stack traces are **unsymbolicated** until the steps below are applied. Like the
Firebase `google-services` plugin, the symbol-upload tooling needs operator-only inputs (a Sentry
org/project + an auth token) and would fail a token-less CI build, so it is **documented here for the
operator to apply**, not wired into `build.gradle.kts`.

Prereqs: a Sentry project (one per environment is fine — `staging` / `production`), and a
**`SENTRY_AUTH_TOKEN`** with `project:releases` + `org:read` scope (this IS a secret — never commit it;
keep it in GitHub Actions secrets / local `~/.sentry/`).

## 1. DSN (no token needed)
Set the per-flavor DSN (a write-only client ingest key, safe to commit, but ours ship empty):
```
./gradlew :mobile:app:assembleStagingRelease -PstagingSentryDsn=https://<key>@oXXX.ingest.sentry.io/<proj>
```
or paste the real value into the `SENTRY_DSN` `buildConfigField` for the flavor in
`mobile/app/build.gradle.kts` (per CLAUDE.md public-repo posture a DSN is non-sensitive). On iOS, set
the `SentryDsn` / `SentryEnvironment` `Info.plist` keys via the scheme's xcconfig.

## 2. Android — ProGuard/R8 mapping upload (Sentry Android Gradle plugin)
Add the plugin (it must NOT be committed-and-applied without the token — it would break token-less CI,
the `google-services` precedent):
```kotlin
// mobile/app/build.gradle.kts — operator applies:
plugins { id("io.sentry.android.gradle") version "<current>" }
sentry {
    org.set("<your-org>"); projectName.set("<your-project>")
    authToken.set(System.getenv("SENTRY_AUTH_TOKEN"))
    autoUploadProguardMapping.set(true) // release variants only
}
```
R8 is already `isMinifyEnabled = true` for release (`build.gradle.kts` buildTypes), so a mapping file
exists to upload.

## 3. iOS — dSYM upload
Add an Xcode "Run Script" build phase to the `iosApp` target (after "Embed Frameworks"):
```sh
export SENTRY_AUTH_TOKEN="$(cat ~/.sentry/auth-token)"
if which sentry-cli >/dev/null; then
  sentry-cli debug-files upload --org <org> --project <project> "$DWARF_DSYM_FOLDER_PATH"
fi
```
(`sentry-cli` via `brew install getsentry/tools/sentry-cli`.) The static-framework build (`isStatic =
true`) emits the ComposeApp dSYM into `DWARF_DSYM_FOLDER_PATH`.

## 4. CI (agent-hook-blocked — operator applies the YAML)
Agent edits to `.github/workflows/**` are blocked. Add the `SENTRY_AUTH_TOKEN` repo secret and an upload
step to the release/deploy workflow (Android: the Gradle plugin uploads during `assemble*Release`;
iOS: the build phase above). Until this lands, crashes still report — just unsymbolicated.

## 5. Verify
Trigger a release-build crash on a device; confirm the Sentry issue shows a **symbolicated** stack
(method names + line numbers), the correct `environment` + `release` tags, and **no** IP / coordinate /
token in the payload (`sendDefaultPii = false` + the `beforeSend` scrubber enforce this).

## 6. Backend DSN (`backend-sentry-error-capture`)
The backend (`:infra:sentry-jvm`, spec `backend-error-reporting`) reports every `ERROR` log — including
every 500 — to Sentry once a DSN is present. Until then it logs `event=sentry_disabled reason=dsn_missing`
at startup and does nothing else. No symbol upload is needed (JVM stack traces are readable).

1. In the same Sentry org as mobile, create a **Java** project for the backend (e.g. `nearyou-backend`);
   copy its DSN. One project for staging + prod is fine — events carry `environment`.
2. Store it in Secret Manager and let the Cloud Run runtime SA read it (the
   `dev/scripts/admin-totp-key-bootstrap.sh` shape):
   ```bash
   printf '%s' 'https://<key>@oXXX.ingest.sentry.io/<proj>' | gcloud secrets create staging-sentry-backend-dsn \
     --project=nearyou-staging --replication-policy=automatic --data-file=-
   gcloud secrets add-iam-policy-binding staging-sentry-backend-dsn --project=nearyou-staging \
     --member="serviceAccount:27815942904-compute@developer.gserviceaccount.com" \
     --role="roles/secretmanager.secretAccessor"
   ```
3. In `.github/workflows/deploy-staging.yml`, move `SENTRY_BACKEND_DSN=staging-sentry-backend-dsn:latest`
   from the *pending* comment list into the live `--set-secrets` list (only after step 2 — mapping a
   missing slot fails the deploy, #381). The backend resolves the **unprefixed** name
   (`secrets.resolve("sentry-backend-dsn")` → env `SENTRY_BACKEND_DSN`); do not name it `SENTRY_DSN`.
4. Verify after the deploy: startup logs `event=sentry_enabled environment=staging release=<revision>`;
   trigger a 500 and confirm the Sentry issue carries `environment=staging`, a `call_id` tag matching the
   response's `X-Request-Id`, no `user`, no `server_name`, and no coordinate / token / email / IP.
5. Production: the same slot unprefixed (`sentry-backend-dsn`) at prod cutover.
