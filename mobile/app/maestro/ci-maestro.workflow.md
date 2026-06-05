# CI workflow for Maestro (apply manually)

`.github/workflows/**` edits are hook-blocked for AI. Copy the YAML below into
`.github/workflows/mobile-maestro.yml` yourself, then commit.

Notes before applying:
- Runs the non-auth flows only (`--exclude-tags auth-gated`) until the Phase 2 test-login lands.
- Boots an Android emulator in CI (KVM) and installs the **dev** flavor. Heavy (~several min) — gate it
  on `mobile/**` changes and let it run non-blocking at first.
- Mirror the repo's existing `cancel-in-progress` + path-filter conventions from `ci.yml`.
- Always uploads artifacts (screenshots/log/JUnit) so a human can eyeball failures.

```yaml
name: mobile-maestro
on:
  pull_request:
    paths:
      - 'mobile/**'
      - 'dev/scripts/maestro-run.sh'
concurrency:
  group: mobile-maestro-${{ github.ref }}
  cancel-in-progress: true
jobs:
  maestro:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - name: Install Maestro
        run: |
          curl -fsSL https://get.maestro.mobile.dev | bash
          echo "$HOME/.maestro/bin" >> "$GITHUB_PATH"
      - name: Build dev-debug APK
        run: ./gradlew :mobile:app:assembleDevDebug
      - name: Run Maestro flows on emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          script: |
            adb install -r mobile/app/build/outputs/apk/dev/debug/app-dev-debug.apk
            maestro test mobile/app/maestro/flows \
              --exclude-tags auth-gated \
              -e APP_ID=id.nearyou.app.dev \
              --format JUNIT --output report.xml \
              --test-output-dir maestro-artifacts
      - name: Upload artifacts
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: maestro-artifacts
          path: |
            maestro-artifacts/**
            report.xml
```
