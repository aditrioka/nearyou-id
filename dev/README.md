# Local Dev Environment

Plain Postgres+PostGIS + Redis via docker compose. Auth bypasses Supabase
Auth/Realtime/PostgREST so the lighter image is sufficient until chat lands.

## First-time setup

```sh
cd dev
cp .env.example .env

# Generate the RSA keypair Ktor uses to sign access tokens.
# Append the printed line into dev/.env (overwrites the empty KTOR_RSA_PRIVATE_KEY).
./scripts/generate-rsa-keypair.sh >> .env

# Generate a 32-byte HS256 secret for Supabase realtime tokens.
echo "SUPABASE_JWT_SECRET=$(openssl rand -base64 32)" >> .env
```

## Day-to-day

```sh
# Start services.
cd dev && docker compose --env-file .env up -d
cd ..

# Apply migrations (V1 placeholder + V2 schema).
# --no-configuration-cache: Flyway 11.x Gradle plugin doesn't support Gradle's
# config cache yet (uses Task.project at execution time).
set -a; . dev/.env; set +a
./gradlew :backend:ktor:flywayMigrate --no-configuration-cache

# Seed a tester user (you supply the hashed Google/Apple sub).
dev/scripts/seed-test-user.sh --google-id-hash $(printf '%s' 'fake-google-sub' | shasum -a 256 | cut -d' ' -f1)

# Run the backend (env vars must already be exported).
./gradlew :backend:ktor:run
```

## Stopping

```sh
cd dev && docker compose down              # keep volumes
cd dev && docker compose down -v           # nuke postgres data too
```

## Notes

- `dev/.env` is gitignored; never commit secrets.
- Postgres binds host port **5433** (not the default 5432) to avoid colliding
  with a native Postgres install. `DB_URL` in `.env.example` matches.
- Container names are `nearyouid-dev-postgres` / `nearyouid-dev-redis` to
  avoid colliding with any pre-existing `nearyou-postgres` from earlier
  prototypes.
- Plain Postgres has no `realtime` schema — the V2 migration's RLS policy
  block is a no-op locally. The policy applies when V2 runs against a
  Supabase instance.
- Signup is not implemented in this change. Use `seed-test-user.sh` to
  create users until the age-gate change lands.
