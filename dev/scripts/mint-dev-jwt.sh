#!/usr/bin/env sh
# Mint a dev access JWT for an existing users.id, so you can curl authenticated
# endpoints without going through Google/Apple OAuth.
#
# Usage:
#   dev/scripts/mint-dev-jwt.sh <user-uuid> [token-version]
#
# Reads KTOR_RSA_PRIVATE_KEY (and optionally other vars) from dev/.env if present.
# The token is signed with the same key the running server uses to verify, so it
# validates against `Authentication`/`AUTH_PROVIDER_USER` without further setup.
#
# Output: the JWT on stdout, nothing else (gradle noise suppressed via -q).
set -eu

if [ -f "$(dirname "$0")/../.env" ]; then
    # shellcheck disable=SC1091
    set -a
    . "$(dirname "$0")/../.env"
    set +a
fi

if [ "$#" -lt 1 ]; then
    echo "usage: $0 <user-uuid> [token-version]" >&2
    exit 2
fi

# cd to repo root so `./gradlew` resolves regardless of caller's pwd.
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

# `--quiet` strips the gradle progress chrome; the task itself prints just the token.
# `--no-configuration-cache` because the application plugin's JavaExec doesn't play
# nicely with the cache (matching the existing flywayMigrate workaround).
./gradlew --quiet --no-configuration-cache :backend:ktor:mintDevJwt --args="$*"
