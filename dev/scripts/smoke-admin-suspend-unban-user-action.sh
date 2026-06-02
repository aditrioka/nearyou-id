#!/usr/bin/env bash
# Smoke test for admin-suspend-unban-user-action (Admin #5) against a staging deploy.
#
# Per openspec/changes/admin-suspend-unban-user-action/tasks.md task 10.1.
# Exercises the admin panel's FIRST state-changing action end-to-end through the
# real Cloudflare → Google Frontend → Cloud Run proxy chain, which the in-process
# integration tests cannot cover (wire-level CSRF on a real POST, the 303 /
# HX-Redirect back to the lookup view, and the auth-gate redirect at the edge).
#
# It logs in as the staging-test admin (reusing the admin-login flow), then for a
# SYNTHETIC target user (UUID passed as the first argument):
#   [a] unauthenticated GET /admin/users → 302 /admin/login
#   [b] browser login (303) → captures the __Host-admin_session cookie
#   [c] read the CSRF token from the authenticated layout
#   [d] GET /admin/users?q=<uuid> → 200 + the target's state + the action controls
#   [e] POST /admin/users/<uuid>/suspend (X-CSRF-Token + a distinctive free-text
#       reason) → 303 / HX-Redirect
#   [f] GET /admin/users?q=<uuid> → now shows "Suspended until …"
#   [g] GET /admin/actions-log?target_id=<uuid>&action_type=user_suspended → the
#       user_suspended row carrying the FREE-TEXT reason (admin-only audit surface)
#   [h] (optional, --db-url) the account_action_applied notification carries the
#       SANITIZED reason "suspension" and NOT the free-text (design D9 — PII).
#       Skipped with a note when no DB URL is supplied (covered by the in-process
#       test 8.6 regardless).
#   [i] POST /admin/users/<uuid>/unban (X-CSRF-Token) → 303 / HX-Redirect
#   [j] GET /admin/users?q=<uuid> → now shows "Active" (ban cleared)
#   [k] GET /admin/actions-log?target_id=<uuid>&action_type=user_unbanned → the
#       user_unbanned row exists
#   [l] logout (cleanup)
#
# This restores the target to NOT-banned at the end (the unban). Use a synthetic
# staging user, never a real account.
#
# Prerequisites:
#   - A staging-test admin row provisioned via dev/scripts/admin-bootstrap (1.8).
#   - A synthetic staging target user (its UUID is the first argument).
#   - `oathtool` installed locally (for current TOTP code generation).
#   - The staging deploy carrying this change is live.
#   - (optional) `psql` + a staging DB URL for the notification assertion [h].
#
# Usage:
#   STAGING_TEST_EMAIL="oka+smoke@nearyou.id" \
#   STAGING_TEST_PASSWORD="<staging-test-password>" \
#   STAGING_TEST_TOTP_SECRET="<base32-secret>" \
#   dev/scripts/smoke-admin-suspend-unban-user-action.sh <target-user-uuid> \
#     [--api-base https://api-staging.nearyou.id] [--db-url "postgres://…"]
set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
DB_URL=""
TARGET=""
while [ $# -gt 0 ]; do
    case "$1" in
        --api-base) API_BASE="$2"; shift 2 ;;
        --db-url) DB_URL="$2"; shift 2 ;;
        *) TARGET="$1"; shift ;;
    esac
done

: "${TARGET:?usage: smoke-admin-suspend-unban-user-action.sh <target-user-uuid> [--api-base URL] [--db-url URL]}"
: "${STAGING_TEST_EMAIL:?set STAGING_TEST_EMAIL}"
: "${STAGING_TEST_PASSWORD:?set STAGING_TEST_PASSWORD}"
: "${STAGING_TEST_TOTP_SECRET:?set STAGING_TEST_TOTP_SECRET}"

command -v oathtool >/dev/null 2>&1 || { echo "oathtool not installed (brew install oath-toolkit)"; exit 1; }

# A distinctive free-text reason: it MUST appear in the admin_actions_log audit
# row (admin-only) but MUST NOT leak into the user-facing notification (D9).
FREE_TEXT="smoke suspected-minor see report #SMOKE-$RANDOM"

pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "[a] unauthenticated GET /admin/users → 302 /admin/login"
anon_resp="$(curl -sS -i "$API_BASE/admin/users")"
echo "$anon_resp" | head -1 | grep -q "302" || fail "expected 302 on unauthenticated /admin/users"
echo "$anon_resp" | grep -qi "^Location: /admin/login" || fail "missing Location: /admin/login on auth-gate redirect"
pass "auth gate redirects anonymous request to /admin/login"

echo "[b] browser login (303) → capture __Host-admin_session cookie"
totp="$(oathtool --totp -b "$STAGING_TEST_TOTP_SECRET")"
login_resp="$(curl -sS -i -c "$tmp/cookies.txt" \
    --data-urlencode "email=$STAGING_TEST_EMAIL" \
    --data-urlencode "password=$STAGING_TEST_PASSWORD" \
    --data-urlencode "totp=$totp" \
    "$API_BASE/admin/login")"
echo "$login_resp" | head -1 | grep -qE "30[23]" || fail "expected 303 on browser login"
echo "$login_resp" | grep -qi "^Set-Cookie: __Host-admin_session" || fail "missing Set-Cookie __Host-admin_session"
pass "browser login succeeded; session cookie captured"

echo "[c] read the CSRF token from the authenticated layout"
csrf="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/" | sed -n 's/.*name="csrf-token" content="\([^"]*\)".*/\1/p' | head -1)"
[ -n "$csrf" ] || fail "could not read the CSRF token from /admin/"
pass "CSRF token captured"

echo "[d] GET /admin/users?q=$TARGET → 200 + state + suspend/unban controls"
lookup="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/users?q=$TARGET")"
echo "$lookup" | grep -q "/admin/users/$TARGET/suspend" || fail "lookup page missing the suspend control for the target"
echo "$lookup" | grep -q "/admin/users/$TARGET/unban" || fail "lookup page missing the unban control for the target"
pass "target resolves; suspend + unban controls render"

echo "[e] POST /admin/users/$TARGET/suspend (CSRF + free-text reason) → 303"
suspend_code="$(curl -sS -o /dev/null -w "%{http_code}" -b "$tmp/cookies.txt" \
    -H "X-CSRF-Token: $csrf" \
    --data-urlencode "reason=$FREE_TEXT" \
    -X POST "$API_BASE/admin/users/$TARGET/suspend")"
echo "$suspend_code" | grep -qE "30[23]" || fail "expected 303 on suspend (got $suspend_code)"
pass "suspend applied (303 back to the lookup view)"

echo "[f] GET /admin/users?q=$TARGET → now shows a suspension"
after_suspend="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/users?q=$TARGET")"
echo "$after_suspend" | grep -qi "Suspended until" || fail "lookup did not reflect the new suspension state"
pass "target now shows 'Suspended until …'"

echo "[g] GET /admin/actions-log → the user_suspended row carries the free-text reason"
audit_suspend="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/actions-log?target_id=$TARGET&action_type=user_suspended")"
echo "$audit_suspend" | grep -q "user_suspended" || fail "no user_suspended audit row for the target"
echo "$audit_suspend" | grep -q "report #SMOKE" || fail "the free-text reason is not recorded in the audit row"
pass "user_suspended audit row recorded with the free-text reason (admin-only)"

echo "[h] notification carries the SANITIZED reason, NOT the free-text (optional DB check)"
if [ -n "$DB_URL" ]; then
    command -v psql >/dev/null 2>&1 || fail "psql not installed but --db-url was supplied"
    notif="$(psql "$DB_URL" -tAc \
        "SELECT body_data->>'reason' || '|' || (body_data::text LIKE '%suspected-minor%')::text
           FROM notifications
          WHERE user_id = '$TARGET' AND type = 'account_action_applied'
          ORDER BY created_at DESC LIMIT 1;")"
    [ -n "$notif" ] || fail "no account_action_applied notification for the target"
    echo "$notif" | grep -q "^suspension|" || fail "notification body_data.reason is not the sanitized code 'suspension' (got: $notif)"
    echo "$notif" | grep -q "|false$" || fail "notification body_data LEAKED the free-text reason (PII regression — design D9)"
    pass "notification carries body_data.reason='suspension'; the free-text is absent"
else
    pass "DB check skipped (no --db-url); notification sanitization covered by in-process test 8.6"
fi

echo "[i] POST /admin/users/$TARGET/unban (CSRF) → 303"
unban_code="$(curl -sS -o /dev/null -w "%{http_code}" -b "$tmp/cookies.txt" \
    -H "X-CSRF-Token: $csrf" \
    -X POST "$API_BASE/admin/users/$TARGET/unban")"
echo "$unban_code" | grep -qE "30[23]" || fail "expected 303 on unban (got $unban_code)"
pass "unban applied (303 back to the lookup view)"

echo "[j] GET /admin/users?q=$TARGET → now shows 'Active' (ban cleared)"
after_unban="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/users?q=$TARGET")"
echo "$after_unban" | grep -qi "Active" || fail "lookup did not reflect the cleared ban"
echo "$after_unban" | grep -qi "Suspended until" && fail "target still shows a suspension after unban"
pass "target restored to 'Active'"

echo "[k] GET /admin/actions-log → the user_unbanned row exists"
audit_unban="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/actions-log?target_id=$TARGET&action_type=user_unbanned")"
echo "$audit_unban" | grep -q "user_unbanned" || fail "no user_unbanned audit row for the target"
pass "user_unbanned audit row recorded"

echo "[l] logout (cleanup)"
curl -sS -o /dev/null -b "$tmp/cookies.txt" -H "X-CSRF-Token: $csrf" -X POST "$API_BASE/admin/logout" || true
pass "logged out"

echo "All admin-suspend-unban-user-action smoke checks passed against $API_BASE"
