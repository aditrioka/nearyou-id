#!/usr/bin/env sh
# Generate a fresh 2048-bit RSA keypair for local Ktor JWT signing.
# Prints a ready-to-paste line for dev/.env. Run once per developer.
set -eu

tmpkey=$(mktemp)
trap 'rm -f "$tmpkey"' EXIT

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmpkey" 2>/dev/null

# Single-line base64 (no wrapping) of the PKCS#8 PEM.
encoded=$(base64 < "$tmpkey" | tr -d '\n')

echo "KTOR_RSA_PRIVATE_KEY=$encoded"

# Invite-code HMAC secret (32 random bytes, base64-encoded).
invite_secret=$(openssl rand -base64 32 | tr -d '\n')
echo "INVITE_CODE_SECRET=$invite_secret"
