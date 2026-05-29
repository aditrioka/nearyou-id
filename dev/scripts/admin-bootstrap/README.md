# admin-bootstrap

Generates the SQL `INSERT` for a new `admin_users` row for the
`admin-login-argon2-totp` flow (Admin #3). There is **no admin self-signup
endpoint** by design — the first admin (and any later manually-provisioned
admin) is created out-of-band with this tool.

## What it produces

- An **Argon2id** `password_hash` for an interactively-entered password
  (the plaintext password is never printed and never stored — only the hash).
- A SecureRandom **160-bit TOTP secret**, surfaced **once** in **base32** for
  authenticator-app entry, AND **AES-256-GCM-encrypted** (admin UUID bound as
  AAD) for the `totp_secret_encrypted` BYTEA column.
- A ready-to-paste `INSERT INTO admin_users (...)` statement.

## Prerequisites

1. The AES-256 key slot must exist in GCP Secret Manager (provision via
   [`../admin-totp-key-bootstrap.sh`](../admin-totp-key-bootstrap.sh)):
   - staging: `staging-admin-totp-secret-aes-key`
   - production: `admin-totp-secret-aes-key`

2. Export the key as base64 into `ADMIN_TOTP_AES_KEY_BASE64`.

   **⚠ Shell-history mitigation — do BOTH:**
   - Enable ignorespace once per shell:
     ```sh
     export HISTCONTROL=ignorespace        # bash
     setopt HIST_IGNORE_SPACE              # zsh
     ```
   - Prefix the resolve command with a **leading space** so it is not recorded:
     ```sh
      export ADMIN_TOTP_AES_KEY_BASE64="$(gcloud secrets versions access latest \
         --secret=staging-admin-totp-secret-aes-key --project=nearyou-staging)"
     ```
     (note the leading space before `export`).

## Usage

```sh
dev/scripts/admin-bootstrap/admin-bootstrap.sh \
    --email oka@nearyou.id --display-name "Oka" --role owner
```

Valid roles: `owner`, `admin`, `moderator`, `read_only` (per the V16
`admin_users_role_check` constraint).

The script prompts for the password (hidden input). It then prints, on stdout:

1. A `DO NOT save this output to a file` warning.
2. The admin UUID.
3. The **base32 TOTP secret** — enter this into your authenticator app
   immediately; it is shown only once.
4. The SQL `INSERT` statement.

Apply the SQL to the target database (staging: Supabase SQL editor; production:
once the prod key slot is provisioned). **Do not** redirect the script output
to a file or commit it.

## Security invariants (tested)

`backend/ktor/src/test/kotlin/id/nearyou/app/dev/AdminBootstrapMainTest.kt`
asserts the operator output:

- contains the SQL INSERT + the DO-NOT-SAVE warning,
- never contains the plaintext password (only its Argon2id hash),
- never contains the AES key,
- surfaces the base32 TOTP secret exactly once,
- never emits the raw TOTP secret bytes as hex/base64,
- the encrypted secret round-trips back to the raw secret via `AesGcmCipher.decrypt`.
