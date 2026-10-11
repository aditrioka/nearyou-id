#!/usr/bin/env bash
# Smoke test for `internal-caller-allowlist-and-body-limit` (#544 + #545) against a
# deployed backend (default: the staging branch deploy). Pure HTTP — no JWT minting,
# no DB access. Per `openspec/project.md` § Staging deploy timing.
#
# Asserts:
#   1. A 1 MiB POST /api/v1/posts (Content-Length declared, no auth) → 413
#      payload_too_large — the body cap fires before authentication.
#   2. A small POST /api/v1/posts with no auth → 401 (the cap does not touch normal bodies).
#   3. POST /internal/unban-worker with no Authorization → 401 missing_authorization.
#   4. POST /internal/unban-worker with a garbage bearer → 401 invalid_token.
#   5. (optional) FOREIGN_ID_TOKEN set → POST /internal/unban-worker → 403
#      principal_not_allowed. Mint it as ANY non-allowlisted service account, WITH the
#      email claim (otherwise you test the no-email path):
#        FOREIGN_ID_TOKEN="$(gcloud auth print-identity-token \
#          --impersonate-service-account=<non-allowlisted SA> \
#          --audiences=https://api-staging.nearyou.id --include-email)"
#
# The allowlisted-scheduler 2xx half is proven with the real job, not this script:
#   gcloud scheduler jobs run nearyou-unban-worker-staging \
#     --location=asia-southeast1 --project=nearyou-staging
#   then read the request log for POST /internal/unban-worker → 200.
#
# Usage:
#   dev/scripts/smoke-internal-caller-allowlist-and-body-limit.sh [--api-base <url>]
#
# Exit codes: 0 pass · 1 an assertion failed · 2 usage error.

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base) API_BASE="$2"; shift 2 ;;
        --help|-h) sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

FAILED=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# expect <label> <expected-status> <expected-body-substring> <curl args...>
expect() {
    local label="$1" want_status="$2" want_body="$3"
    shift 3
    local status
    # A transport error (reset, timeout) prints 000 via -w → a FAIL line, not a silent set -e exit.
    status="$(curl -sS --max-time 30 -o "$TMP/body" -w '%{http_code}' -X POST "$@" || true)"
    if [[ "$status" == "$want_status" ]] && { [[ -z "$want_body" ]] || grep -q -- "$want_body" "$TMP/body"; }; then
        echo "PASS  $label → $status"
    else
        echo "FAIL  $label → $status (want $want_status / '$want_body'): $(head -c 300 "$TMP/body")"
        FAILED=1
    fi
}

ONE_MIB=$((1024 * 1024))
head -c "$ONE_MIB" /dev/zero | tr '\0' 'a' > "$TMP/big.json"

expect "1 MiB body, no auth (cap before auth)" 413 "payload_too_large" \
    -H "Content-Type: application/json" --data-binary "@$TMP/big.json" "$API_BASE/api/v1/posts"
expect "small body, no auth" 401 "" \
    -H "Content-Type: application/json" --data '{"content":"x"}' "$API_BASE/api/v1/posts"
expect "/internal without token" 401 "missing_authorization" "$API_BASE/internal/unban-worker"
expect "/internal with garbage bearer" 401 "invalid_token" \
    -H "Authorization: Bearer not.a.jwt" "$API_BASE/internal/unban-worker"

if [[ -n "${FOREIGN_ID_TOKEN:-}" ]]; then
    expect "/internal with a foreign SA token" 403 "principal_not_allowed" \
        -H "Authorization: Bearer $FOREIGN_ID_TOKEN" "$API_BASE/internal/unban-worker"
else
    echo "SKIP  foreign-SA 403 (set FOREIGN_ID_TOKEN — see header)"
fi

exit "$FAILED"
