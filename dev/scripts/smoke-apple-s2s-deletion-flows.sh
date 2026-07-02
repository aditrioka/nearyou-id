#!/usr/bin/env bash
# Smoke for apple-s2s-deletion-flows against a staging deploy.
#
# No-creds smoke (mirrors smoke-account-deletion-tombstone.sh): confirms the Apple
# S2S webhook is MOUNTED and its verification pipeline is intact after the branch
# deploy. A real signed payload can only come from Apple (staging verifies against
# Apple's live JWKS), so the deletion happy paths are covered by the
# `AppleS2SDeletionRoutesTest` DB suite — this is the deploy-config check:
#   - malformed envelope → 400 (route live, envelope validation answers — not 404/500)
#   - self-signed JWT     → 401 (JWKS verification fail-closed — not 501/500)
#   - hard-delete worker  → 401 (OIDC gate intact; the backstop endpoint deployed)
#
# Usage: dev/scripts/smoke-apple-s2s-deletion-flows.sh [BASE_URL]
#   BASE_URL defaults to https://api-staging.nearyou.id
set -euo pipefail
BASE="${1:-https://api-staging.nearyou.id}"
fail=0

check() {
  local method="$1" path="$2" want="$3" label="$4" body="${5:-}"
  local code
  if [[ -n "$body" ]]; then
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X "$method" \
      -H 'Content-Type: application/json' -d "$body" "$BASE$path" || echo "000")
  else
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X "$method" "$BASE$path" || echo "000")
  fi
  if [[ "$code" == "$want" ]]; then
    echo "  ok   $label  ($method $path → $code)"
  else
    echo "  FAIL $label  ($method $path → $code, want $want)"
    fail=1
  fi
}

# A structurally-valid but self-signed JWT (headers claim kid=smoke): staging's
# JwksCache never resolves this kid against Apple's JWKS → 401 invalid_signature.
SELF_SIGNED_JWT='eyJhbGciOiJSUzI1NiIsImtpZCI6InNtb2tlIn0.eyJ0eXBlIjoiYWNjb3VudC1kZWxldGUiLCJzdWIiOiJzbW9rZSJ9.c21va2Utc2lnbmF0dXJl'

echo "== apple-s2s-deletion-flows smoke @ $BASE =="
# App boots + DB reachable.
check GET /health/ready 200 "health ready"
# Webhook mounted: malformed envelope answers 400 from envelope validation (not 404).
check POST /internal/apple/s2s-notifications 400 "S2S malformed envelope → 400" '{"nope":true}'
# Verification pipeline intact + fail-closed: unknown-kid self-signed JWT → 401 (not 501/500).
check POST /internal/apple/s2s-notifications 401 "S2S self-signed deletion payload → 401" \
  "{\"signedPayload\":\"$SELF_SIGNED_JWT\"}"
# Daily backstop endpoint deployed + OIDC-gated.
check POST /internal/account-hard-delete-worker 401 "hard-delete worker OIDC-gated"

if [[ "$fail" == "0" ]]; then echo "== SMOKE PASS =="; else echo "== SMOKE FAIL =="; exit 1; fi
