# syntax=docker/dockerfile:1.7
#
# Multi-stage Dockerfile for the NearYouID Ktor backend.
#
# Stage 1 (`builder`) — run Gradle `installDist` on the full multi-module repo to
# produce a standard Ktor distribution under
# `backend/ktor/build/install/ktor/{bin,lib}`. Gradle cache survives across builds
# via BuildKit cache mounts.
#
# Stage 2 (`runtime`) — Eclipse Temurin 21 JRE on a small Ubuntu Noble base.
# Copies only the installDist output + resources; no Gradle / source / test code
# ships in the final image.
#
# `RUN_FLYWAY_ON_STARTUP=true` in Cloud Run tells the app to run Flyway migrations
# before serving requests (staging-simplified bootstrap). Prod will split this into
# a dedicated Cloud Run Job per docs/04-Architecture.md.

ARG JDK_IMAGE=eclipse-temurin:21.0.5_11-jdk-noble
ARG JRE_IMAGE=eclipse-temurin:21.0.5_11-jre-noble

# ---- builder ---------------------------------------------------------------

FROM ${JDK_IMAGE} AS builder

WORKDIR /workspace

# Gradle wrapper + settings first so wrapper download + dep resolution caches
# separately from source edits.
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml

# build-logic must be copied next so convention plugins are available before
# subproject build files resolve.
COPY build-logic build-logic

# Subproject build.gradle.kts files to allow Gradle to compute the full dep graph
# before source copy (improves layer caching).
# NB: `:mobile:app` is intentionally excluded from Docker builds — see
# `settings.gradle.kts` + the `-PincludeMobile=false` flag below. The Android
# plugin it applies would otherwise force Gradle to resolve an Android SDK that
# this JDK-only builder does not ship.
COPY backend/ktor/build.gradle.kts backend/ktor/build.gradle.kts
COPY core/data/build.gradle.kts core/data/build.gradle.kts
COPY core/domain/build.gradle.kts core/domain/build.gradle.kts
COPY infra/oidc/build.gradle.kts infra/oidc/build.gradle.kts
COPY infra/redis/build.gradle.kts infra/redis/build.gradle.kts
COPY infra/supabase/build.gradle.kts infra/supabase/build.gradle.kts
COPY lint/detekt-rules/build.gradle.kts lint/detekt-rules/build.gradle.kts
COPY shared/distance/build.gradle.kts shared/distance/build.gradle.kts
COPY shared/tmp/build.gradle.kts shared/tmp/build.gradle.kts

# Now copy actual sources for the modules the Ktor jar depends on.
COPY backend/ktor/src backend/ktor/src
COPY core/data/src core/data/src
COPY core/domain/src core/domain/src
COPY infra/oidc/src infra/oidc/src
COPY infra/redis/src infra/redis/src
COPY infra/supabase/src infra/supabase/src
COPY shared/distance/src shared/distance/src
COPY shared/tmp/src shared/tmp/src

# Build Ktor distribution. `installDist` produces `backend/ktor/build/install/ktor/`
# with `bin/ktor` launch script + `lib/*.jar` runtime classpath. We skip tests
# (CI runs them on its own) and keep Gradle + wrapper caches mounted so rebuilds
# only pay for the changed subproject.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/workspace/.gradle \
    ./gradlew :backend:ktor:installDist -x test \
        -PincludeMobile=false \
        --no-daemon --console=plain

# ---- runtime ---------------------------------------------------------------

FROM ${JRE_IMAGE} AS runtime

# Non-root user — Cloud Run doesn't enforce this, but it's a no-cost hardening
# baseline and matches the "least privilege" principle in docs/06-Security-Privacy.md.
RUN useradd --system --uid 10001 --user-group --home /app --shell /usr/sbin/nologin ktor
WORKDIR /app

# Copy distribution output from builder.
COPY --from=builder --chown=ktor:ktor /workspace/backend/ktor/build/install/ktor /app

USER ktor

# Cloud Run default PORT is 8080; application.conf already honours `${?PORT}`.
EXPOSE 8080

# `bin/ktor` is the Gradle-generated launcher script that wires the full classpath
# and calls `io.ktor.server.netty.EngineMain`.
ENTRYPOINT ["/app/bin/ktor"]
