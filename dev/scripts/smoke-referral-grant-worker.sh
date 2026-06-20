#!/usr/bin/env bash
# Smoke for referral-grant-worker against a staging deploy.
#
# No-creds smoke: confirms the new worker route is MOUNTED + correctly gated after the
# branch deploy. `/internal/referral-activity-check` requires Google OIDC → 401 (a 404
# would mean the route didn't deploy; a 200/500 would mean the gate is missing or the
# handler crashed at boot). `/health/ready` 200 confirms the app booted + the V29
# granted_entitlements migration applied (deploy runs Flyway). The RevenueCat GRANT echo
# is the existing `/internal/revenuecat-webhook` (vendor Bearer-gated) — re-checked here
# so the §5 GRANT-handler MODIFY didn't break its mount/auth.
#
# This is the deploy-config check (secret-slot drift, env renames, eager-connect crashes
# surface as a non-401/404 or a dead host). A fuller authenticated seed→invoke→grant smoke
# needs a staging session + the `staging-revenuecat-secret-api-key` slot (the worker
# fail-softs to ledger-only until that slot is provisioned).
#
# Usage: dev/scripts/smoke-referral-grant-worker.sh [BASE_URL]
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

echo "== referral-grant-worker smoke @ $BASE =="
# App boots + DB reachable (the V29 granted_entitlements migration applied on deploy).
check GET  /health/ready                     200 "health ready (V29 applied)"
# New activity-check worker mounted + OIDC-gated (no Google OIDC token → 401, not 404).
check POST /internal/referral-activity-check 401 "activity-check worker OIDC-gated"
# Existing RevenueCat webhook still mounted + vendor-Bearer-gated (GRANT MODIFY intact).
check POST /internal/revenuecat-webhook      401 "revenuecat webhook vendor-gated"

if [[ "$fail" == "0" ]]; then echo "== SMOKE PASS =="; else echo "== SMOKE FAIL =="; exit 1; fi
