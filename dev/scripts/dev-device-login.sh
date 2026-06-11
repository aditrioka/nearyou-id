#!/usr/bin/env sh
# One-command authenticated session on the DEV app (device or emulator) against the
# LOCAL backend — no Google ceremony (the dev flavor's OAuth client is a placeholder
# by design; see dev/docs/google-cloud-oauth-clients.md).
#
# Does, in order (all idempotent):
#   1. checks the local backend is up on :8080 (prints the boot command if not),
#   2. `adb reverse tcp:8080 tcp:8080` so the device's localhost:8080 reaches this machine,
#   3. seeds the fixed dev-login test user if absent (reuses seed-test-user.sh),
#   4. mints a dev JWT for it (mint-dev-jwt.sh) and fires the nearyou-dev://test-login
#      deep link with device-shell-safe quoting.
#
# Usage:
#   dev/scripts/dev-device-login.sh            # first/only connected device
#   ANDROID_SERIAL=XXXX dev/scripts/dev-device-login.sh
#
# Prereqs: app installed (./gradlew :mobile:app:installDevDebug), local Postgres up
# (docker compose), dev/.env present. Quoting note: the deep link is passed as
# -d "\"<url>\"" so the DEVICE shell sees one double-quoted string — naked & would
# split it, and \& leaves a literal backslash in the token (a malformed Authorization
# header; caught live in the 2026-06-11 audit verification).
set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cd "$repo_root"

if [ -f "$script_dir/../.env" ]; then
    # shellcheck disable=SC1091
    set -a
    . "$script_dir/../.env"
    set +a
fi

adb_bin="${ADB:-adb}"
command -v "$adb_bin" >/dev/null 2>&1 || adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"
app_id="${DEV_APP_ID:-id.nearyou.app.dev}"

# 1. Backend up?
if ! curl -sf -m 3 -o /dev/null http://localhost:8080/health/live; then
    echo "Local backend is NOT serving :8080. Boot it first (separate terminal):" >&2
    echo '  set -a; . dev/.env; set +a' >&2
    echo '  KTOR_ENV=test REDIS_URL=redis://localhost:6379 SUPABASE_URL=http://localhost:54321 \' >&2
    echo '    INTERNAL_OIDC_AUDIENCE=http://localhost:8080 ./gradlew --no-daemon :backend:ktor:run' >&2
    exit 1
fi

# 2. Device + reverse + app present.
"$adb_bin" get-state >/dev/null 2>&1 || { echo "No adb device connected." >&2; exit 1; }
"$adb_bin" reverse tcp:8080 tcp:8080 >/dev/null
if ! "$adb_bin" shell pm path "$app_id" >/dev/null 2>&1; then
    echo "$app_id is not installed — run: ./gradlew :mobile:app:installDevDebug" >&2
    exit 1
fi

# 3. Fixed test user (idempotent by the well-known google_id_hash).
login_hash=$(printf '%s' "nearyou-dev-device-login" | shasum -a 256 | cut -d' ' -f1)
psql_url="${DB_URL#jdbc:}"
db_user="${DB_USER:-postgres}"
psql_url=$(printf '%s' "$psql_url" | sed "s|postgresql://|postgresql://${db_user}@|")
user_id=$(PGPASSWORD="${DB_PASSWORD:-postgres}" psql "$psql_url" -tA \
    -c "SELECT id FROM users WHERE google_id_hash = '$login_hash' LIMIT 1;")
if [ -z "$user_id" ]; then
    echo "Seeding the dev-login test user…"
    "$script_dir/seed-test-user.sh" --google-id-hash "$login_hash" >/dev/null
    user_id=$(PGPASSWORD="${DB_PASSWORD:-postgres}" psql "$psql_url" -tA \
        -c "SELECT id FROM users WHERE google_id_hash = '$login_hash' LIMIT 1;")
fi
[ -n "$user_id" ] || { echo "Could not seed/find the dev-login user." >&2; exit 1; }

# 4. Mint + fire.
echo "Minting dev JWT for $user_id…"
jwt=$("$script_dir/mint-dev-jwt.sh" "$user_id" | tail -1)
exp_ms=$(( ($(date +%s) + 900) * 1000 ))
link="nearyou-dev://test-login?access=${jwt}&refresh=${jwt}&exp=${exp_ms}"
"$adb_bin" shell am start -a android.intent.action.VIEW -d "\"$link\"" "$app_id" >/dev/null
echo "Done — the dev app should now be on the authenticated Home (user $user_id)."
echo "NOTE: the refresh token is the access JWT (bogus) — sessions die at exp (~15 min); just re-run this script."
