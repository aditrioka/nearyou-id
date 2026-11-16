#!/usr/bin/env bash
# Smoke test for `admin-user-history-enforcement-rows` (#496) against a deployed
# backend (default: the staging branch deploy). Per `openspec/project.md` § Staging
# deploy timing. Read-only apart from the login's own session + audit rows.
#
# The schema half (V41 in flyway_schema_history, `admin_actions_after_user_idx`
# present with its partial predicate) is checked separately with a read-only SQL
# query (Supabase MCP); this script covers the HTTP + query half:
#   [a] GET /health/ready → 200
#   [b] unauthenticated GET /admin/users/<id> → 302 /admin/login
#   [c] browser login (303) → __Host-admin_session cookie
#   [d] GET /admin/users/$SMOKE_USER_ID → 200 + the History card. This runs the
#       widened history query (`target_type='user'` arm OR `after_state ->> 'user_id'`
#       arm) on the real database. With EXPECT_ACTION set, the page must also show
#       that action type: a `user_*` row proves the original arm still renders; an
#       `appeal_approved` / `moderation_queue_resolved` row (keyed to another object,
#       naming the user in after_state.user_id) proves the new arm.
#   [e] logout (cleanup)
#
# Usage (creds from the operator's Keychain — never print them):
#   K() { security find-generic-password -s nearyou-staging-smoke-admin -a "$1" -w; }
#   STAGING_TEST_EMAIL="$(K email)" STAGING_TEST_PASSWORD="$(K password)" \
#   STAGING_TEST_TOTP_SECRET="$(K totp)" SMOKE_USER_ID=<users.id> EXPECT_ACTION=appeal_approved \
#   dev/scripts/smoke-admin-user-history-enforcement-rows.sh [--api-base https://api-staging.nearyou.id]
#
# Each run spends one login against the #532 throttle (≤5 runs / 15 min per email).
set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
if [ "${1:-}" = "--api-base" ]; then API_BASE="$2"; shift 2; fi

: "${STAGING_TEST_EMAIL:?set STAGING_TEST_EMAIL}"
: "${STAGING_TEST_PASSWORD:?set STAGING_TEST_PASSWORD}"
: "${STAGING_TEST_TOTP_SECRET:?set STAGING_TEST_TOTP_SECRET}"
: "${SMOKE_USER_ID:?set SMOKE_USER_ID (a users.id on the target environment)}"

command -v oathtool >/dev/null 2>&1 || { echo "oathtool not installed (brew install oath-toolkit)"; exit 1; }

pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "[a] GET /health/ready → 200"
ready="$(curl -sS --max-time 60 -o /dev/null -w "%{http_code}" "$API_BASE/health/ready")"
[ "$ready" = "200" ] || fail "expected 200 on /health/ready (got $ready)"
pass "backend ready"

echo "[b] unauthenticated GET /admin/users/$SMOKE_USER_ID → 302 /admin/login"
anon="$(curl -sS -i "$API_BASE/admin/users/$SMOKE_USER_ID")"
echo "$anon" | head -1 | grep -q "302" || fail "expected 302 on unauthenticated profile GET"
echo "$anon" | grep -qi "^Location: /admin/login" || fail "missing Location: /admin/login"
pass "auth gate redirects anonymous request to /admin/login"

echo "[c] browser login (303) → capture __Host-admin_session cookie"
totp="$(oathtool --totp -b "$STAGING_TEST_TOTP_SECRET")"
login="$(curl -sS -i -c "$tmp/cookies.txt" \
    --data-urlencode "email=$STAGING_TEST_EMAIL" \
    --data-urlencode "password=$STAGING_TEST_PASSWORD" \
    --data-urlencode "totp=$totp" \
    "$API_BASE/admin/login")"
echo "$login" | head -1 | grep -qE "30[23]" || fail "expected 303 on browser login"
echo "$login" | grep -qi "^Set-Cookie: __Host-admin_session" || fail "missing Set-Cookie __Host-admin_session"
pass "login succeeded; session cookie captured"

echo "[d] GET /admin/users/$SMOKE_USER_ID → 200 + History card (widened query runs)"
code="$(curl -sS -o "$tmp/profile.html" -w "%{http_code}" -b "$tmp/cookies.txt" "$API_BASE/admin/users/$SMOKE_USER_ID")"
[ "$code" = "200" ] || fail "expected 200 on the profile GET (got $code)"
grep -q 'class="user-history cardlet"' "$tmp/profile.html" || fail "profile page missing the History card"
grep -q "No matching user." "$tmp/profile.html" && fail "SMOKE_USER_ID resolves to no user (the history query never ran)"
pass "profile rendered with its History card"
if [ -n "${EXPECT_ACTION:-}" ]; then
    grep -q ">$EXPECT_ACTION</span>" "$tmp/profile.html" || fail "History is missing the expected $EXPECT_ACTION row"
    pass "History shows the expected $EXPECT_ACTION row"
fi

echo "[e] logout (cleanup)"
csrf="$(sed -n 's/.*name="csrf-token" content="\([^"]*\)".*/\1/p' "$tmp/profile.html" | head -1)"
if [ -n "$csrf" ]; then
    curl -sS -o /dev/null -b "$tmp/cookies.txt" -H "X-CSRF-Token: $csrf" -X POST "$API_BASE/admin/logout" || true
    pass "logged out"
else
    pass "logout skipped (no csrf-token meta — non-fatal cleanup step)"
fi

echo "All admin-user-history-enforcement-rows smoke checks passed against $API_BASE"
