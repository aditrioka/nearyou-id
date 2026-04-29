# nearyou-id

[![CI](https://github.com/aditrioka/nearyou-id/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/aditrioka/nearyou-id/actions/workflows/ci.yml)
[![License: FSL-1.1-ALv2](https://img.shields.io/badge/License-FSL--1.1--ALv2-blue.svg)](LICENSE)

> Location-based social-media MVP for Indonesia (18+ only). Text posts pinned to fuzzed coordinates, nearby discovery, 1:1 chat, freemium with a Premium tier.

**Status:** pre-launch, solo-operator build. The repository is public for transparency, free GitHub Actions minutes, and as a working reference for AI-assisted Kotlin Multiplatform development. Not currently accepting external contributions — see the [License](#license) section for what you can and can't do with the source.

---

## What's in this repo

A modular monolith on Kotlin Multiplatform. Module list below is auto-generated from [`settings.gradle.kts`](settings.gradle.kts) + [`dev/module-descriptions.txt`](dev/module-descriptions.txt) — run `dev/scripts/sync-readme.sh --write` to regenerate.

<!-- AUTOGEN:modules:start -->
- `:mobile:app` — KMP + Compose Multiplatform app (Android + iOS, sharing UI in `commonMain`).
- `:backend:ktor` — Ktor server: REST API, scheduled workers, JWT auth, Flyway-managed Postgres schema.
- `:core:data` — pure-JVM repository interfaces and DTOs.
- `:core:domain` — pure-JVM domain interfaces, value types, no vendor SDK imports.
- `:shared:distance` — KMP utilities for great-circle distance + nearby-radius math, shared by mobile and backend.
- `:shared:tmp` — scratch placeholder for KMP boilerplate; will be split into real `:shared:<name>` modules as features are built.
- `:infra:fcm` — Firebase Cloud Messaging Admin SDK wrapper; production `NotificationDispatcher` impl, per-platform payload builders, on-send token-prune contract.
- `:infra:oidc` — Google OIDC bearer-token verifier (Auth0 `jwks-rsa` + `java-jwt`) for `/internal/*` endpoints invoked by Cloud Scheduler.
- `:infra:redis` — Lettuce-backed `RateLimiter` + `RedisProbe` implementations; Redis client lifecycle isolated from `:backend:ktor`.
- `:infra:supabase` — Supabase JWKS / token-verifier helpers used by Realtime channel access and Apple S2S sign-in flows.
- `:lint:detekt-rules` — project-specific Detekt rules enforcing safety invariants (block-exclusion joins, shadow-ban view reads, raw `X-Forwarded-For` bans, Redis hash-tag scoping, etc.).
<!-- AUTOGEN:modules:end -->

Plus [`iosApp/`](iosApp/) — the Xcode entry point that consumes the `ComposeApp` framework emitted by `:mobile:app`.

## Stack

| Layer | Tech |
|---|---|
| Mobile | Kotlin Multiplatform, Compose Multiplatform, Moko Resources |
| Backend | Ktor (Netty), Koin, Flyway, HikariCP, kotlinx.serialization, Auth0 java-jwt + jwks-rsa |
| Storage | Postgres + PostGIS (Supabase managed in staging/prod), Redis (Upstash for staging/prod, local Compose for dev) |
| Auth | Self-issued RS256 JWTs, Google / Apple Sign-In on the way in, Supabase JWTs for Realtime channel access |
| Hosting | GCP Cloud Run + Cloud Scheduler, Cloudflare in front, R2 for media |
| Observability | Sentry (errors), Cloud Logging (structured app logs), OpenTelemetry-shaped probes |
| CI | GitHub Actions — ktlint, Detekt, JVM tests against a Postgres + Redis service container |

## Documentation map

The README is the elevator pitch; the load-bearing context lives in three places, all version-controlled in this repo:

- [`docs/00-README.md`](docs/00-README.md) → master design doc, principles, cross-reference map. Start here for product intent, architecture, security/privacy posture, and roadmap.
- [`openspec/specs/`](openspec/specs) → 35+ capability specs in OpenSpec format. Every shipped feature has a spec describing requirements, scenarios, and invariants. The archive at [`openspec/changes/archive/`](openspec/changes/archive) is a chronological log of every change proposal that landed, including rejected alternatives and reconciliation notes.
- [`CLAUDE.md`](CLAUDE.md) → project instructions for AI agents (Claude Code, GitHub Action). Documents the critical invariants (shadow-ban safety, block enforcement, spatial fuzzing, rate-limit conventions, RLS policies) and the change-delivery workflow. Useful as a fast-onboarding read for humans too.

## Local development

Everything for running the backend locally is in [`dev/README.md`](dev/README.md). The short version:

```sh
# 1. Start Postgres + Redis via Compose.
cd dev && cp .env.example .env
./scripts/generate-rsa-keypair.sh >> .env
echo "SUPABASE_JWT_SECRET=$(openssl rand -base64 32)" >> .env
docker compose --env-file .env up -d
cd ..

# 2. Run migrations.
set -a; . dev/.env; set +a
./gradlew :backend:ktor:processResources :backend:ktor:flywayMigrate --no-configuration-cache

# 3. Run the backend.
./gradlew :backend:ktor:run

# Optional: seed a test user.
dev/scripts/seed-test-user.sh --google-id-hash $(printf '%s' 'fake-google-sub' | shasum -a 256 | cut -d' ' -f1)
```

## Build commands

```sh
# Backend
./gradlew :backend:ktor:run
./gradlew :backend:ktor:test

# Android
./gradlew :mobile:app:assembleDebug

# iOS — open iosApp/ in Xcode and run, or use the IDE run configuration.

# Whole-project lint + test (matches CI).
./gradlew ktlintCheck detekt :backend:ktor:test :infra:oidc:test :lint:detekt-rules:test
```

## License

[FSL-1.1-ALv2](LICENSE) — Functional Source License, version 1.1, with Apache-2.0 future grant.

In plain language:

- ✅ You **may** read the source, fork for personal study, run it locally, contribute non-commercially, or use it for non-commercial education and research.
- ❌ You **may not** make this software (or substantially-similar functionality) available to others as part of a commercial product or service that competes with nearyou-id.
- ⏳ Two years after each commit lands, that commit is automatically re-licensed to **Apache License 2.0** — fully open source. The commercial restriction sunsets; nothing is locked up forever.

This balances "public for transparency + free CI" with the realities of a pre-launch consumer product. See [LICENSE](LICENSE) for the canonical text.

## Contributing

Not currently accepting external pull requests — this is a single-operator MVP and the change-delivery workflow assumes one author. If you spot a security issue, please open a private security advisory through GitHub rather than a public issue.

---

Built with [Claude Code](https://claude.com/claude-code) and the [Anthropic SDK](https://docs.anthropic.com).
