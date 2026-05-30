-- Seed the `system` sentinel admin user: the canonical, non-human actor that
-- machine-initiated admin_actions_log rows attribute to (admin_actions_log.admin_id
-- is NOT NULL REFERENCES admin_users(id), so a real row is required). Deferred to
-- this change by V16__admin_users.sql:24-26; driven by docs/05-Implementation.md:235
-- ("Audit log inserted per unban").
--
-- `id` is the deterministic UUID.nameUUIDFromBytes("system") value, hardcoded here
-- and as SYSTEM_ACTOR_ID in :backend:ktor (SystemActorTest asserts they match).
-- `is_active = FALSE` makes it structurally un-loginable: AdminUserRepository
-- .findActiveByEmail filters `WHERE email = ? AND is_active = TRUE`, so the row is
-- never loaded for password verification on the login path.
-- `password_hash` is a WELL-FORMED Argon2id PHC string of a randomly-generated
-- secret that was discarded -- well-formed so PasswordHasher.verify returns `false`
-- rather than THROWING on a malformed value (a bare non-PHC literal makes
-- Password4j's decodeHash throw BadParametersException), and non-matching so no
-- password can ever verify against it. It hashes no real credential, so committing
-- it to this source-available repo is not a credential leak; the account is
-- un-loginable regardless.
-- `role = 'read_only'` is least privilege (role is NOT NULL CHECK).
-- ON CONFLICT keeps the seed idempotent + Flyway-portable (no admin_app role
-- needed; the admin_users table exists from V16, so this runs clean in CI).

INSERT INTO admin_users (id, email, display_name, password_hash, role, is_active, webauthn_enrolled)
VALUES (
    '54b53072-540e-3eb8-b8e9-343e71f28176',
    'system@system.nearyou.invalid',
    'System Actor',
    '$argon2id$v=19$m=19456,t=8,p=1$D6JAq3z/99exB7zohBns5g$xJSc+Uv3njpAtE4UgNRBNiL2ol6N0cpJh+NERB8izBs',
    'read_only',
    FALSE,
    FALSE
)
ON CONFLICT (id) DO NOTHING;
