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

The keypair script also emits `INVITE_CODE_SECRET` + `JITTER_SECRET` — the two
32-byte HMAC keys used at signup (`users.invite_code_prefix`) and at post
creation (`posts.display_location` via deterministic HMAC jitter per
`docs/05-Implementation.md § Coordinate Fuzzing`). No separate steps needed.

## Day-to-day

```sh
# Start services.
cd dev && docker compose --env-file .env up -d
cd ..

# Apply migrations (V1 placeholder → V2 auth → V3 signup → V4 posts).
# `processResources` first, because flywayMigrate scans the classpath for
# `db/migration/*.sql` — those files are copied into the build classpath by
# `processResources`; without it, new migrations look invisible to Flyway.
# --no-configuration-cache: Flyway 11.x Gradle plugin doesn't support Gradle's
# config cache yet (uses Task.project at execution time).
set -a; . dev/.env; set +a
./gradlew :backend:ktor:processResources :backend:ktor:flywayMigrate --no-configuration-cache

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

- **Postgres binds host port 5433**, not the default 5432, to avoid colliding
  with any native Postgres install on the host. `DB_URL` in `.env.example`
  matches. If your machine has nothing else on 5432 you can change both back.

- **Container names are `nearyouid-dev-postgres` / `nearyouid-dev-redis`** —
  namespaced to avoid colliding with stale `nearyou-postgres` containers from
  earlier prototypes. If `docker ps -a` shows old containers blocking creation,
  remove them with `docker rm <name>` (volumes are preserved separately).

- **`flywayMigrate` requires `--no-configuration-cache`.** The Flyway 11.x
  Gradle plugin uses `Task.project` at execution time, which Gradle's
  configuration cache rejects. Tracked as a TODO in
  `build-logic/src/main/kotlin/nearyou.ktor.gradle.kts`; revisit when the
  Flyway plugin fixes the issue or migrate to invoking the Flyway Java API
  via a custom task.

- Plain Postgres has no `realtime` schema — the V2 migration's RLS policy
  block is a no-op locally. The policy applies when V2 runs against a
  Supabase instance.

- **Signup flow**: `POST /api/v1/auth/signup` lands in the signup-flow change.
  Body: `{ provider, id_token, date_of_birth, device_fingerprint_hash? }`.
  Dev verification against a running Ktor instance requires a Google/Apple
  OAuth client that can issue real ID tokens (see proposal task 14.2 for
  why a live curl with a hand-rolled id_token isn't feasible without a mock
  JWKS). The integration tests in `SignupFlowTest` cover every scenario
  end-to-end against this same dev Postgres — run them with
  `DB_URL=jdbc:postgresql://localhost:5433/nearyou_dev DB_USER=postgres DB_PASSWORD=postgres ./gradlew :backend:ktor:test --tests 'id.nearyou.app.auth.signup.SignupFlowTest' -Dkotest.tags=database --no-configuration-cache`.
  You can still use `seed-test-user.sh` as a shortcut to get a usable user row.

- **Post creation**: `POST /api/v1/posts` (auth required). Body:
  `{ content, latitude, longitude }` — content ≤ 280 Unicode code points,
  coords inside the Indonesia envelope (`-11..6.5` lat, `94..142` lng).
  Happy path writes both `actual_location` and `display_location` in one
  INSERT via `ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography`. Display
  is deterministically fuzzed via HMAC-SHA256(JITTER_SECRET, post_id).
  Business reads go through the `visible_posts` view (the Detekt rule
  `RawFromPostsRule` flags raw `FROM posts` in main sources).

- **Database-tagged tests** (currently `MigrationV3SmokeTest`,
  `MigrationV4SmokeTest`, `SignupFlowTest`, `CreatePostServiceTest`,
  `VisiblePostsViewTest`) and **network-tagged tests** (`JwksReachabilityTest`)
  are excluded from PR CI via `-Dkotest.tags='!database,!network'`. Run them
  locally against a running dev compose stack with `-Dkotest.tags='!network'`
  (keeps database on, drops network) or `-Dkotest.tags=database` for only DB
  suites.

- **Detekt** runs on `main` sources only and is wired to `check` via the
  `nearyou.ktor` convention plugin. The custom ruleset lives in
  `:lint:detekt-rules`; config at `backend/ktor/config/detekt/detekt.yml`.
  Add a new rule by extending `Rule` in `:lint:detekt-rules`, registering it
  in `NearYouRuleSetProvider`, and activating it in `detekt.yml`. Tests are
  excluded from scanning on purpose — they `SELECT FROM posts` for DB
  assertions, which is fine.
