#!/usr/bin/env bash
# Smoke test for the admin-reserved-usernames-editor change.
#
# No-creds staging smoke: an unauthenticated GET of the new admin route MUST
# 302-redirect to /admin/login. That single assertion proves the route is
# mounted, auth-gated by the admin session middleware, and the app booted on the
# deployed revision (this is a zero-migration change, so there is no schema
# precondition to verify). The admin panel is served on the api-staging host
# (api-staging.nearyou.id/admin/*), NOT an admin-staging subdomain.
#
# Authenticated add/list/edit/remove smoke is deliberately out of scope here —
# it needs a provisioned staging admin + TOTP; the full write path is covered by
# the 46 integration specs (route + repository) and the local verify-loop
# bring-up (PR body screenshot).
#
# Usage: dev/scripts/smoke-admin-reserved-usernames-editor.sh
#   ADMIN_HOST overrides the base host (default https://api-staging.nearyou.id).
set -euo pipefail

HOST="${ADMIN_HOST:-https://api-staging.nearyou.id}"
URL="$HOST/admin/reserved-usernames"

echo "[smoke] GET $URL (expect 302 -> /admin/login)"
read -r code redirect < <(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' --max-time 20 "$URL")
echo "[smoke] status=$code redirect=$redirect"

if [ "$code" = "302" ] && printf '%s' "$redirect" | grep -q '/admin/login'; then
  echo "[smoke] PASS — route mounted, auth-gated, app booted on the deployed revision."
  exit 0
fi

echo "[smoke] FAIL — expected 302 to /admin/login, got status=$code redirect=$redirect"
exit 1
