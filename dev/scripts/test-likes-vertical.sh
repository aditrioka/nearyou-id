#!/usr/bin/env bash
# Manual end-to-end smoke for the V7 likes vertical (task 9.3 of post-likes-v7).
#
# Prereqs:
#   - dev/docker-compose.yml is up (`cd dev && docker compose --env-file .env up -d`)
#   - V1–V7 migrations applied (`./gradlew :backend:ktor:processResources :backend:ktor:flywayMigrate --no-configuration-cache`)
#   - Ktor server is running on localhost:8080 (`./gradlew :backend:ktor:run`)
#   - dev/.env is populated with KTOR_RSA_PRIVATE_KEY, DB_*, etc.
#
# Usage: dev/scripts/test-likes-vertical.sh
#
# Scenario (per post-likes-v7 task 9.3):
#   signup 3 users → A creates post P → B likes → count=1 →
#   A's Nearby shows liked_by_viewer=false, B's shows true →
#   B unlikes → count=0 → both timelines false →
#   B blocks A → POST /like and GET /likes/count on P return 404 →
#   B unblocks A → surfaces return 200/204 again.
set -euo pipefail

API="${API:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [ -f "dev/.env" ]; then
    set -a; . dev/.env; set +a
fi

psql_url() {
    local url="${DB_URL#jdbc:}"
    local u="${DB_USER:-postgres}"
    printf '%s' "$url" | sed "s|postgresql://|postgresql://${u}@|"
}
PSQL="PGPASSWORD=${DB_PASSWORD:-postgres} psql $(psql_url) -Atq -v ON_ERROR_STOP=1"

sha256hex() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

expect_status() {
    local label="$1" want="$2" got="$3"
    if [ "$got" != "$want" ]; then
        echo "FAIL: $label — expected $want, got $got" >&2
        exit 1
    fi
    echo "PASS: $label ($got)"
}

json_field() { # json_field '<json>' '<key>'
    python3 -c "import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])" "$1" "$2"
}

# ---- server reachable? --------------------------------------------------------
if ! curl -fsS "$API/health/live" >/dev/null 2>&1; then
    echo "Ktor not reachable at $API/health/live. Start it with ./gradlew :backend:ktor:run" >&2
    exit 1
fi

# ---- seed 3 users -------------------------------------------------------------
seed_user() { # seed_user <google_hash>
    local gh="$1" suf iprefix
    suf=$(head -c4 /dev/urandom | hexdump -e '"%08x"')
    iprefix=$(head -c4 /dev/urandom | hexdump -e '"%08x"' | tr 'a-f' 'A-F' | cut -c1-8)
    eval "$PSQL" <<SQL
INSERT INTO users (username, display_name, date_of_birth, google_id_hash, invite_code_prefix)
VALUES ('likes_${suf}', 'Likes Tester ${suf}', DATE '1990-01-01', '${gh}', '${iprefix}')
RETURNING id;
SQL
}

A_ID=$(seed_user "$(sha256hex "likes-vertical-A-$$-$(date +%s)")")
B_ID=$(seed_user "$(sha256hex "likes-vertical-B-$$-$(date +%s)")")
C_ID=$(seed_user "$(sha256hex "likes-vertical-C-$$-$(date +%s)")")
echo "seeded users: A=$A_ID B=$B_ID C=$C_ID"

# ---- mint JWTs ----------------------------------------------------------------
mint() { dev/scripts/mint-dev-jwt.sh "$1"; }
A_TOK=$(mint "$A_ID")
B_TOK=$(mint "$B_ID")
C_TOK=$(mint "$C_ID"); : "$C_TOK"  # 3rd user reserved for future shadow-ban scenarios
echo "minted JWTs for A, B, C"

cleanup() {
    for uid in "$A_ID" "$B_ID" "$C_ID"; do
        eval "$PSQL" -c "DELETE FROM posts WHERE author_id = '${uid}';" >/dev/null 2>&1 || true
        eval "$PSQL" -c "DELETE FROM users WHERE id = '${uid}';" >/dev/null 2>&1 || true
    done
    echo "cleaned up seeded rows"
}
trap cleanup EXIT

# ---- A creates post P at Jakarta ----------------------------------------------
POST_BODY='{"content":"hello from A","latitude":-6.2,"longitude":106.8}'
CREATE_JSON=$(curl -fsS -X POST "$API/api/v1/posts" \
    -H "Authorization: Bearer $A_TOK" -H "Content-Type: application/json" \
    -d "$POST_BODY")
P_ID=$(json_field "$CREATE_JSON" id)
echo "A created post P=$P_ID"

# Helper: HTTP status only.
status() { # status <method> <url> <token> [body]
    local method="$1" url="$2" tok="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -sS -o /dev/null -w "%{http_code}" -X "$method" "$url" \
            -H "Authorization: Bearer $tok" -H "Content-Type: application/json" -d "$body"
    else
        curl -sS -o /dev/null -w "%{http_code}" -X "$method" "$url" \
            -H "Authorization: Bearer $tok"
    fi
}

# Helper: body + status.
body_status() { # prints <status>\n<body>
    local method="$1" url="$2" tok="$3"
    curl -sS -w "\n%{http_code}" -X "$method" "$url" -H "Authorization: Bearer $tok"
}

# Helper: liked_by_viewer for a post id in a Nearby response.
liked_for() { # liked_for <json> <post_id>
    python3 - "$1" "$2" <<'PY'
import json, sys
body = json.loads(sys.argv[1])
pid = sys.argv[2]
for post in body.get("posts", []):
    if post["id"] == pid:
        print(str(post["liked_by_viewer"]).lower())
        sys.exit(0)
print("missing")
PY
}

# ---- B likes P → 204 ----------------------------------------------------------
expect_status "B POST /like (first)" 204 "$(status POST "$API/api/v1/posts/$P_ID/like" "$B_TOK")"

# ---- GET /likes/count = 1 -----------------------------------------------------
COUNT_JSON=$(curl -fsS "$API/api/v1/posts/$P_ID/likes/count" -H "Authorization: Bearer $B_TOK")
[ "$(json_field "$COUNT_JSON" count)" = "1" ] && echo "PASS: count=1" || { echo "FAIL: count expected 1, got $COUNT_JSON" >&2; exit 1; }

# ---- A's Nearby: liked_by_viewer=false for P ----------------------------------
A_NEARBY=$(curl -fsS "$API/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000" -H "Authorization: Bearer $A_TOK")
[ "$(liked_for "$A_NEARBY" "$P_ID")" = "false" ] && echo "PASS: A sees liked_by_viewer=false" || { echo "FAIL: A nearby shape wrong: $A_NEARBY" >&2; exit 1; }

# ---- B's Nearby: liked_by_viewer=true for P -----------------------------------
B_NEARBY=$(curl -fsS "$API/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000" -H "Authorization: Bearer $B_TOK")
[ "$(liked_for "$B_NEARBY" "$P_ID")" = "true" ] && echo "PASS: B sees liked_by_viewer=true" || { echo "FAIL: B nearby shape wrong: $B_NEARBY" >&2; exit 1; }

# ---- B unlikes P → 204; count=0; both timelines show false --------------------
expect_status "B DELETE /like" 204 "$(status DELETE "$API/api/v1/posts/$P_ID/like" "$B_TOK")"

COUNT_JSON=$(curl -fsS "$API/api/v1/posts/$P_ID/likes/count" -H "Authorization: Bearer $B_TOK")
[ "$(json_field "$COUNT_JSON" count)" = "0" ] && echo "PASS: count=0 after unlike" || { echo "FAIL: count expected 0, got $COUNT_JSON" >&2; exit 1; }

A_NEARBY=$(curl -fsS "$API/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000" -H "Authorization: Bearer $A_TOK")
B_NEARBY=$(curl -fsS "$API/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000" -H "Authorization: Bearer $B_TOK")
[ "$(liked_for "$A_NEARBY" "$P_ID")" = "false" ] && [ "$(liked_for "$B_NEARBY" "$P_ID")" = "false" ] \
    && echo "PASS: both timelines show liked_by_viewer=false after unlike" \
    || { echo "FAIL: post-unlike timeline shape wrong" >&2; exit 1; }

# ---- B blocks A → POST /like and GET /likes/count return 404 ------------------
expect_status "B blocks A" 204 "$(status POST "$API/api/v1/blocks/$A_ID" "$B_TOK")"

expect_status "B POST /like while blocked" 404 "$(status POST "$API/api/v1/posts/$P_ID/like" "$B_TOK")"
expect_status "B GET /likes/count while blocked" 404 \
    "$(curl -sS -o /dev/null -w "%{http_code}" "$API/api/v1/posts/$P_ID/likes/count" -H "Authorization: Bearer $B_TOK")"

# ---- B unblocks A → surfaces return to 204/200 --------------------------------
expect_status "B unblocks A" 204 "$(status DELETE "$API/api/v1/blocks/$A_ID" "$B_TOK")"

expect_status "B POST /like after unblock" 204 "$(status POST "$API/api/v1/posts/$P_ID/like" "$B_TOK")"
expect_status "B GET /likes/count after unblock" 200 \
    "$(curl -sS -o /dev/null -w "%{http_code}" "$API/api/v1/posts/$P_ID/likes/count" -H "Authorization: Bearer $B_TOK")"

echo
echo "========================================"
echo "ALL CHECKS GREEN — V7 likes vertical OK"
echo "========================================"
