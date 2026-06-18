#!/usr/bin/env bash
# Smoke for account-deletion-tombstone against a staging deploy.
#
# No-creds smoke: confirms the new routes are MOUNTED + correctly gated after the
# branch deploy (the user endpoints require a Bearer JWT → 401; the internal worker
# requires Google OIDC → 401). A 404 would mean the route didn't deploy; a 200/500
# would mean the auth gate is missing. This is the deploy-config check (secret-slot
# drift, env renames, eager-connect crashes surface as a non-401/404 or a dead host)
# — a fuller authenticated request→status→cancel smoke needs a staging session.
#
# Usage: dev/scripts/smoke-account-deletion-tombstone.sh [BASE_URL]
#   BASE_URL defaults to https://api-staging.nearyou.id
set -euo pipefail
BASE="${1:-https://api-staging.nearyou.id}"
fail=0

check() {
  local method="$1" path="$2" want="$3" label="$4"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X "$method" "$BASE$path" || echo "000")
  if [[ "$code" == "$want" ]]; then
    echo "  ok   $label  ($method $path → $code)"
  else
    echo "  FAIL $label  ($method $path → $code, want $want)"
    fail=1
  fi
}

echo "== account-deletion-tombstone smoke @ $BASE =="
# App boots + DB reachable (the migrations V24/V25 applied on deploy).
check GET  /health/ready                       200 "health ready"
# User deletion API mounted + Bearer-gated (no creds → 401, not 404/500).
check GET    /api/v1/account/deletion-request  401 "deletion status auth-gated"
check POST   /api/v1/account/deletion-request  401 "deletion request auth-gated"
check DELETE /api/v1/account/deletion-request  401 "deletion cancel auth-gated"
# Internal worker mounted + OIDC-gated (no Google OIDC token → 401, not 404).
check POST /internal/account-hard-delete-worker 401 "hard-delete worker OIDC-gated"

if [[ "$fail" == "0" ]]; then echo "== SMOKE PASS =="; else echo "== SMOKE FAIL =="; exit 1; fi
