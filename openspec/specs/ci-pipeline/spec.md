# CI Pipeline

This spec defines the GitHub Actions workflow that gates PRs (lint + build + test) and what is deliberately deferred. Push-triggered workflows (deploy, migrate, symbol upload) are out of scope and live in their own future changes.

## Requirements

### Requirement: PR-triggered workflow exists

A GitHub Actions workflow SHALL exist at `.github/workflows/ci.yml` triggered on `pull_request` against `main`. It MUST NOT trigger on `push` (push-triggered workflows are deferred to the deploy-pipeline change).

#### Scenario: Workflow file present
- **WHEN** listing `.github/workflows/`
- **THEN** `ci.yml` is present and YAML-valid

#### Scenario: Trigger is pull_request only
- **WHEN** parsing `ci.yml`
- **THEN** the `on:` block contains `pull_request` and does not contain `push`

### Requirement: Three jobs — lint, build, test

The workflow SHALL define three jobs: `lint` (running `./gradlew ktlintCheck`), `build` (running `./gradlew assemble`), and `test` (running `./gradlew test`). Each job MUST run on `ubuntu-latest`, MUST set up JDK 21 via `actions/setup-java@v4` with `distribution: temurin`, and MUST cache Gradle via `gradle/actions/setup-gradle@v4`.

#### Scenario: Three jobs present
- **WHEN** parsing `ci.yml`
- **THEN** the `jobs:` map contains keys `lint`, `build`, and `test`

#### Scenario: Each job uses Temurin 21
- **WHEN** inspecting each job's steps
- **THEN** each contains an `actions/setup-java@v4` step with `distribution: temurin` and `java-version: 21`

### Requirement: Failure on any job blocks the PR

The workflow MUST be structured so that any single failing job (lint, build, or test) reports a failure status to GitHub, allowing it to be wired as a required check on `main`.

#### Scenario: No continue-on-error
- **WHEN** parsing each job's steps
- **THEN** no step uses `continue-on-error: true`

### Requirement: Out-of-scope jobs explicitly absent

The workflow MUST NOT contain a Flyway migrate job, a Cloud Run deploy job, or a Sentry symbol upload job — these are deferred to dedicated future changes.

#### Scenario: Deferred jobs absent
- **WHEN** scanning `ci.yml` for the strings `flyway`, `gcloud run deploy`, `sentry-cli`
- **THEN** zero matches are found
