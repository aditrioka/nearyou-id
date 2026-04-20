#!/usr/bin/env sh
# Insert a test user into the local Postgres. Use until the signup endpoint exists.
#
# Usage:
#   dev/scripts/seed-test-user.sh --google-id-hash <sha256hex>
#   dev/scripts/seed-test-user.sh --apple-id-hash <sha256hex>
#
# Reads DB_URL/DB_USER/DB_PASSWORD from dev/.env if present.
set -eu

if [ -f "$(dirname "$0")/../.env" ]; then
    # shellcheck disable=SC1091
    . "$(dirname "$0")/../.env"
fi

google_hash=""
apple_hash=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --google-id-hash) google_hash="$2"; shift 2 ;;
        --apple-id-hash)  apple_hash="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$google_hash" ] && [ -z "$apple_hash" ]; then
    echo "must pass --google-id-hash or --apple-id-hash" >&2
    exit 2
fi

# 8-hex random for username and invite_code_prefix uniqueness in dev seeding.
rand8() { hexdump -n4 -e '"%08x"' /dev/urandom; }
suffix=$(rand8)
invite_prefix=$(rand8 | tr 'a-f' 'A-F' | cut -c1-8)

google_sql=$( [ -n "$google_hash" ] && echo "'$google_hash'" || echo "NULL" )
apple_sql=$(  [ -n "$apple_hash"  ] && echo "'$apple_hash'"  || echo "NULL" )

# Strip jdbc: prefix for psql connection string.
psql_url="${DB_URL#jdbc:}"

PGPASSWORD="${DB_PASSWORD:-postgres}" psql "$psql_url" -v ON_ERROR_STOP=1 <<SQL
INSERT INTO users (
    username, display_name, date_of_birth,
    google_id_hash, apple_id_hash,
    invite_code_prefix
) VALUES (
    'tester_${suffix}', 'Tester ${suffix}', DATE '1990-01-01',
    ${google_sql}, ${apple_sql},
    '${invite_prefix}'
) RETURNING id, username;
SQL
