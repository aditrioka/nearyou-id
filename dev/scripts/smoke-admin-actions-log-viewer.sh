#!/usr/bin/env bash
# Smoke test for admin-actions-log-viewer (Admin #4) against a staging deploy.
#
# Per openspec/changes/archive/2026-05-29-admin-actions-log-viewer/tasks.md
# task 8.2. Exercises the read-only audit-log viewer end-to-end through the
# real Cloudflare → Google Frontend → Cloud Run proxy chain, which the
# in-process integration tests cannot cover (wire-level HTMX-fragment vs
# full-page branching, the keyset "older" link round-trip through the proxy,
# and the auth-gate redirect at the edge).
#
# It logs in as the staging-test admin (reusing the admin-login flow), then:
#   [a] unauthenticated GET /admin/actions-log → 302 /admin/login
#   [b] browser login (303) → captures the __Host-admin_session cookie
#   [c] authenticated GET → 200 + the table fragment + the filter form + the
#       admin_login_success row written by THIS very login
#   [d] action_type filter narrows; a no-match filter renders the empty state
#   [e] HX-Request → fragment only (no full-page layout / filter form)
#   [f] POST /admin/actions-log → 405 (read-only; no mutation route)
#   [g] if a second page exists, the "older" keyset link round-trips → 200
#   [h] logout (cleanup)
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
#   dev/scripts/smoke-admin-actions-log-viewer.sh [--api-base https://api-staging.nearyou.id]
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

PATH_VIEWER="/admin/actions-log"

echo "[a] unauthenticated GET $PATH_VIEWER → 302 /admin/login"
anon_resp="$(curl -sS -i "$API_BASE$PATH_VIEWER")"
echo "$anon_resp" | head -1 | grep -q "302" || fail "expected 302 on unauthenticated viewer GET"
echo "$anon_resp" | grep -qi "^Location: /admin/login" || fail "missing Location: /admin/login on auth-gate redirect"
pass "auth gate redirects anonymous request to /admin/login"

echo "[b] browser login (303) → capture __Host-admin_session cookie"
totp="$(oathtool --totp -b "$STAGING_TEST_TOTP_SECRET")"
login_resp="$(curl -sS -i -c "$tmp/cookies.txt" \
    --data-urlencode "email=$STAGING_TEST_EMAIL" \
    --data-urlencode "password=$STAGING_TEST_PASSWORD" \
    --data-urlencode "totp=$totp" \
    "$API_BASE/admin/login")"
echo "$login_resp" | head -1 | grep -qE "30[23]" || fail "expected 303 (POST-redirect-GET) on browser login"
echo "$login_resp" | grep -qi "^Set-Cookie: __Host-admin_session" || fail "missing Set-Cookie __Host-admin_session"
pass "browser login succeeded; session cookie captured"

echo "[c] authenticated GET $PATH_VIEWER → 200 + table + filter form + own login row"
page="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE$PATH_VIEWER")"
echo "$page" | grep -q 'id="actions-log-table"' || fail "viewer page missing the #actions-log-table fragment"
echo "$page" | grep -q 'hx-get="/admin/actions-log"' || fail "viewer page missing the HTMX filter form"
echo "$page" | grep -q "admin_login_success" || fail "viewer does not show the admin_login_success row this login just wrote"
pass "authenticated viewer renders the table + filter form + the just-written login row"

echo "[d] action_type filter narrows; no-match filter shows the empty state"
filtered="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE$PATH_VIEWER?action_type=admin_login_success")"
echo "$filtered" | grep -q "admin_login_success" || fail "action_type=admin_login_success filter dropped its own matching rows"
nomatch="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE$PATH_VIEWER?action_type=zzz_no_such_action_$RANDOM")"
echo "$nomatch" | grep -q "No audit log entries match the current filters." || fail "no-match filter did not render the empty state"
pass "filtering narrows results; empty state renders for a no-match filter"

echo "[e] HX-Request → fragment only (no full-page layout / filter form)"
fragment="$(curl -sS -b "$tmp/cookies.txt" -H "HX-Request: true" "$API_BASE$PATH_VIEWER")"
echo "$fragment" | grep -q 'id="actions-log-table"' || fail "HTMX response missing the table fragment"
echo "$fragment" | grep -qi "<html" && fail "HTMX response unexpectedly carries the full-page <html> wrapper"
echo "$fragment" | grep -q 'hx-get="/admin/actions-log"' && fail "HTMX response unexpectedly carries the full-page filter form"
pass "HTMX request returns only the swappable table fragment"

echo "[f] POST $PATH_VIEWER → 405 (read-only; no mutation route)"
post_code="$(curl -sS -o /dev/null -w "%{http_code}" -b "$tmp/cookies.txt" -X POST "$API_BASE$PATH_VIEWER")"
[ "$post_code" = "405" ] || fail "expected 405 on POST $PATH_VIEWER (got $post_code)"
pass "POST is unmapped (405) — viewer adds no mutation surface"

echo "[g] keyset 'older' link round-trips (skipped if < 1 full page of rows)"
older_url="$(echo "$page" | grep -oE "/admin/actions-log\?cursor=[^\"]*" | head -1 | sed 's/&amp;/\&/g' || true)"
if [ -n "$older_url" ]; then
    older_page="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE$older_url")"
    echo "$older_page" | grep -q 'id="actions-log-table"' || fail "following the older cursor link did not return the table"
    pass "older keyset link round-trips through the proxy → 200 + table"
else
    pass "no 'older' control present (< 1 full page of audit rows) — pagination round-trip skipped"
fi

echo "[h] logout (cleanup)"
csrf="$(curl -sS -b "$tmp/cookies.txt" "$API_BASE/admin/" | sed -n 's/.*name="csrf-token" content="\([^"]*\)".*/\1/p' | head -1)"
if [ -n "$csrf" ]; then
    curl -sS -o /dev/null -b "$tmp/cookies.txt" -H "X-CSRF-Token: $csrf" -X POST "$API_BASE/admin/logout" || true
    pass "logged out"
else
    pass "logout skipped (could not read CSRF token — non-fatal cleanup step)"
fi

echo "All admin-actions-log-viewer smoke checks passed against $API_BASE"
