#!/usr/bin/env bash
# Smoke test for admin-login-argon2-totp (Admin #3) against a staging deploy.
#
# Per openspec/changes/admin-login-argon2-totp/tasks.md task 13.1. Exercises
# the full login → authenticated GET → logout → revoked-cookie cycle through
# the real Cloudflare → Google Frontend → Cloud Run proxy chain, which the
# in-process integration tests cannot cover (especially the wire-level cookie
# attributes in step b.1).
#
# Prerequisites:
#   - A staging-test admin row provisioned via dev/scripts/admin-bootstrap (1.8).
#   - `oathtool` installed locally (for current TOTP code generation).
#   - The staging deploy carrying this change is live.
#
# Usage:
#   STAGING_TEST_EMAIL="oka+smoke@nearyou.id" \
#   STAGING_TEST_PASSWORD="<staging-test-password>" \
#   STAGING_TEST_TOTP_SECRET="<base32-secret>" \
#   dev/scripts/smoke-admin-login-argon2-totp.sh [--api-base https://api-staging.nearyou.id]
set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
if [ "${1:-}" = "--api-base" ]; then API_BASE="$2"; shift 2; fi

: "${STAGING_TEST_EMAIL:?set STAGING_TEST_EMAIL}"
: "${STAGING_TEST_PASSWORD:?set STAGING_TEST_PASSWORD}"
: "${STAGING_TEST_TOTP_SECRET:?set STAGING_TEST_TOTP_SECRET}"

command -v oathtool >/dev/null 2>&1 || { echo "oathtool not installed (brew install oath-toolkit)"; exit 1; }

pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "[a] GET /admin/login → 200 + login form"
login_page="$(curl -sS -i "$API_BASE/admin/login")"
echo "$login_page" | head -1 | grep -q "200" || fail "expected 200 on /admin/login"
echo "$login_page" | grep -qi 'name="totp"' || fail "login form missing totp field"
pass "login page renders"

echo "[b] POST /admin/login with valid creds + current TOTP → 200 + HX-Redirect + Set-Cookie"
totp="$(oathtool --totp -b "$STAGING_TEST_TOTP_SECRET")"
login_resp="$(curl -sS -i -c "$tmp/cookies.txt" \
    --data-urlencode "email=$STAGING_TEST_EMAIL" \
    --data-urlencode "password=$STAGING_TEST_PASSWORD" \
    --data-urlencode "totp=$totp" \
    "$API_BASE/admin/login")"
echo "$login_resp" | head -1 | grep -q "200" || fail "expected 200 on successful login"
echo "$login_resp" | grep -qi "^HX-Redirect: /admin/" || fail "missing HX-Redirect: /admin/"
set_cookie="$(echo "$login_resp" | grep -i "^Set-Cookie: __Host-admin_session" || true)"
[ -n "$set_cookie" ] || fail "missing Set-Cookie __Host-admin_session"
pass "login succeeded"

echo "[b.1] wire-level cookie attribute check"
echo "$set_cookie" | grep -q "Secure" || fail "cookie missing Secure"
echo "$set_cookie" | grep -q "HttpOnly" || fail "cookie missing HttpOnly"
echo "$set_cookie" | grep -q "SameSite=Strict" || fail "cookie missing SameSite=Strict"
echo "$set_cookie" | grep -q "Path=/" || fail "cookie missing Path=/"
echo "$set_cookie" | grep -qi "Domain=" && fail "cookie unexpectedly carries Domain= (violates __Host- prefix)"
pass "cookie attributes correct through the proxy chain"

echo "[c] GET /admin/ → extract CSRF, then POST /admin/logout → 302 /admin/login"
admin_index="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/")"
csrf="$(echo "$admin_index" | sed -n 's/.*name="csrf-token" content="\([^"]*\)".*/\1/p' | head -1)"
[ -n "$csrf" ] || fail "could not extract csrf-token meta from /admin/"
logout_resp="$(curl -sS -i -b "$tmp/cookies.txt" -c "$tmp/cookies.txt" \
    -H "X-CSRF-Token: $csrf" -X POST "$API_BASE/admin/logout")"
echo "$logout_resp" | head -1 | grep -q "302" || fail "expected 302 on logout"
echo "$logout_resp" | grep -qi "^Location: /admin/login" || fail "logout missing Location: /admin/login"
pass "logout revoked the session"

echo "[d] GET /admin/ with revoked cookie → 302 /admin/login"
revoked_resp="$(curl -sS -i -b "$tmp/cookies.txt" "$API_BASE/admin/")"
echo "$revoked_resp" | head -1 | grep -q "302" || fail "expected 302 on revoked-session GET"
echo "$revoked_resp" | grep -qi "^Location: /admin/login" || fail "revoked GET missing Location: /admin/login"
pass "revoked cookie rejected"

echo "[e] no-enumeration: wrong-password body == phantom-email body (byte-equal)"
wrong_pw_body="$(curl -sS \
    --data-urlencode "email=$STAGING_TEST_EMAIL" \
    --data-urlencode "password=definitely-wrong-$RANDOM" \
    --data-urlencode "totp=000000" \
    "$API_BASE/admin/login")"
phantom_body="$(curl -sS \
    --data-urlencode "email=phantom-$RANDOM@nearyou.id" \
    --data-urlencode "password=whatever" \
    --data-urlencode "totp=000000" \
    "$API_BASE/admin/login")"
[ "$wrong_pw_body" = "$phantom_body" ] || fail "no-enumeration violated: failure bodies differ"
pass "no-enumeration response bodies are byte-equal"

echo "All admin-login smoke checks passed against $API_BASE"
