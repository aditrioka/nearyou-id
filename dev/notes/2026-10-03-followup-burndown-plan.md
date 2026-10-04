# nearyou-id — Analisis & Rencana Burndown Follow-up (2026-10-03)

> Sesi analisis-saja (tidak ada perubahan kode, label, issue, atau PR). Sumber: CLAUDE.md, `openspec/project.md`, `docs/08`, `docs/10`, `docs/11`, audit `dev/audits/2026-09-25-mobile-admin-review/REPORT.md`, 80 issue `follow-up` open (ditarik via `gh` akun `aditrioka`), `.claude/skills/*` + `.claude/commands/opsx/*`, dan grep kode di `origin/main` 311fa870.
>
> **Snapshot:** staging `GET /health/ready` → 200 (0.48 s). **0 PR open.** Tidak ada issue berlabel `burning-down` / `triaging`. 80 issue open, semuanya `follow-up`; 46 berlabel `ready-to-burndown`, 34 belum (sebagian besar temuan 2026-09-26 yang belum ditriage). Flyway terakhir **V39** → nomor bebas berikutnya **V40**.

## Status 2026-10-04 (update)

- **Wave 1:** 4/5 merged — #479 (PR #523, hitungan pinned dihapus → `strings.xml` bukan hotspot lagi), #501+#282 (PR #527), #498 (PR #521), #507 (PR #524). **#177–#180 = PR #520, CI hijau + mergeable, menunggu merge** (label `burning-down` masih menempel, lepas otomatis saat issue tertutup).
- **Konteks baru:** review arsitektur merged (PR #526, `dev/audits/2026-10-03-architecture-review/REPORT.md`: 27 usulan issue BELUM dibuat, top-5 pre-launch di § 14, addendum § 15 "feature-complete dulu, produksi belakangan"); doc drift fix merged (#528); PR #529 open = keputusan operator Android-first + production spend gate + lane `apple-paid` (#258/#430/#495 ikut ke lane itu — konsisten dengan rencana ini). Issue baru: #522 (OTel sentinel scenario tests, lanjutan #520), #525 (kelas admin tanpa style, lanjutan #501/#282). 77 issue follow-up open.
- **Wave 2 (disetujui untuk di-list, chip belum dibuat):** lihat § 5 Wave 2; perubahan: aturan "maks 3 sesi menyentuh strings.xml" dilonggarkan (hotspot hilang), #499 harus re-cek sisa drift setelah #528/#529, #525 masuk sebagai cadangan Wave 2 / sesi Wave 3.

## 0. Ringkasan 1 menit

| | |
|---|---|
| **Progress** | Backend ≈ 90 % · Mobile ≈ 85 % (93 % dari spec, 85 % dari scope launch) · Admin ≈ 88 % · Kesiapan launch (non-kode + prod) ≈ 25 % |
| **80 issue** | 4 low-hanging · 33 dalam 17 bundle · 21 berat (solo) · 14 terblokir/operator · 8 butuh keputusanmu |
| **Wave** | 8 wave × 4–5 sesi; Wave 1 = 5 sesi tanpa satu pun menyentuh `strings.xml`/Flyway/`Application.kt` kecuali #479 yang justru memperbaiki hotspot-nya |
| **Keputusan yang kutunggu** | (a) setujui Wave 1; (b) 8 keputusan produk (§7); (c) attestation: post-MVP vs wajib pre-launch; (d) perbaiki drift `/opsx:*` command (§2) |
| **Chip** | Chip review arsitektur `[Fable 5.1 xhigh]` sudah dibuat. Chip burndown `[Opus 5.5 xhigh]` dibuat setelah Wave 1 kamu setujui. |

---

## 1. Orientasi — hal yang berubah / perlu kamu tahu

- **Audit 2026-09-25 "burn order" sudah 4/6 selesai:** #490 (PR #512), #491 (#513), #492 (#519), #493 (#515) merged. Sisa: **#438** (viewer moderation_queue generik) dan **#501** (ikon admin jadi teks). Keduanya masuk rencana.
- **Premise beberapa issue sudah berubah** (diverifikasi grep): #517 bilang blocked #512 → sudah merge, `purchaseConfirmed` ada; #390 blocked #491 → closed; #381 sisi kode sudah fix (PR #460) tinggal operator; #253 bagian re-eval pasca-beli sudah ada; #266 part 1 sudah ship; #272 tidak butuh migrasi (`last_read_at` sudah ada di V15); #348 `RootRouterFlowIosTest` sudah fix di #468.
- **CLAUDE.md stale:** "admin UI intentionally unstyled so far" — `admin.css` 760 baris sudah ada sejak #226/#241 (catatan audit). Perbaiki saat PR docs berikutnya.
- **Env lokal sesi ini:** `JAVA_HOME`/`ANDROID_HOME` unset, Postgres :5433 mati, Redis mati. Sesi burndown harus `docker start nearyouid-dev-postgres nearyouid-dev-redis` dulu (memory `feedback_prefer_existing_dev_containers`), dan ikuti `reference_local_macos_mobile_build_recipe`.

## 2. Peta skill (11 skill + 4 command `/opsx`)

| Skill / trigger | Guna (1 kalimat) | Kapan dipakai | Menyambung ke |
|---|---|---|---|
| `/next-change` | Pilih kapabilitas OpenSpec baru bernilai tertinggi, klaim dengan draft PR, scaffold, review sub-agent, serahkan ke apply. | Fitur BARU (bukan issue). | → `openspec-propose` (B.1) → `openspec-preflight` (B.5) → `/opsx:apply` |
| `openspec-propose` (`/opsx:propose`) | Hasilkan proposal/design/specs/tasks sekaligus; design wajib punya nota konformansi docs/11 + deklarasi lintas-lapisan docs/12. | Dipanggil next-change, issue-burndown (bentuk OpenSpec), audit-burndown. | → `/opsx:apply` |
| `openspec-preflight` | Gate awal: tugas operator, lapisan pasangan yang hilang (docs/12), skenario tanpa task. | Setelah scaffold, sebelum kode. | Dipanggil next-change B.5, apply step 4, issue-burndown. |
| `openspec-apply-change` (`/opsx:apply`) | Implementasi tasks di branch yang sama; staging smoke (7), verifikasi manual UI (7.5), `gh pr ready` + 1 komentar `/review` qodo + 4 lensa sub-agent (8). | Setelah proposal. | ← preflight; → `verify-loop`, `mobile-ui-foundation`; → `/opsx:archive` |
| `openspec-archive-change` (`/opsx:archive`) | Gate DoD docs/11 §5 (3.5), sync spec, pindah ke archive/, halt pada Purpose "TBD", commit ke PR yang sama. | Setelah apply + verifikasi. | Akhir siklus; kamu squash-merge. |
| `openspec-explore` | Mode berpikir, tidak menulis kode. | Ide/masalah belum jelas; cap review next-change tercapai. | → propose |
| `/triage-follow-ups` | Klasifikasi backlog `follow-up`: close / migrate / label `ready-to-burndown` / in-progress; tidak pernah coding. | Backlog kotor; issue baru belum berlabel (34 issue sekarang). | → `/issue-burndown` lewat label |
| `/issue-burndown [N]` | Eksekusi SATU issue → PR → close; rute regular-PR vs OpenSpec penuh; klaim label `burning-down`. | Utang yang masih valid. | ← triage; → propose/preflight/apply/archive bila OpenSpec; → verify-loop |
| `/audit-burndown` | Eksekusi SATU item dari menu audit holistik 2026-06-10 (bukan label); hapus dir audit saat kosong. | Item audit tersisa (mis. #212 = 02-H3). | Paralel dengan issue-burndown; issue-burndown skip item yang masih di menu audit. |
| `verify-loop` (`/run`, `/verify`) | Jalankan app nyata (Ktor+admin, Android, iOS sim) + gate §D (`ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + unit test mobile). | Verifikasi manual, smoke, pre-push. | Dipanggil apply 7.5 + kedua burndown. |
| `mobile-ui-foundation` | Checklist UI/UX per layar Compose (mockup board dulu, state contract, a11y, Bahasa Indonesia). | Bangun/poles layar Compose. | Bersama apply; bring-up → verify-loop. |

**Tiga alur:**
- **A. Fitur baru:** `/next-change` → propose → `--strict` → rekonsiliasi docs → preflight (`## Preflight` di PR body) → review sub-agent (≤ 2 ronde) → `/opsx:apply` (preflight precondition → tasks → smoke → 7.5 verify → qodo + lensa) → `/opsx:archive` (DoD → sync → archive) → **satu** squash-merge.
- **B. Utang/bug:** issue `follow-up` → `/triage-follow-ups` (`triaging` → klasifikasi → `ready-to-burndown`) → `/issue-burndown N` (`burning-down` → regular `fix/` PR **atau** propose→preflight→apply→archive) → PR `Closes #N` → merge. `/audit-burndown` = eksekutor terpisah dari menu audit beku, tanpa label klaim.
- **C. Verifikasi:** edit → `verify-loop` Step 0 pilih permukaan (§A Ktor `KTOR_ENV=test` + TOTP; §B Android emulator/Test Lab via `scripts/test_android.sh`; §C iOS sim lokal) → amati → gate §D. Checklist UI dari `mobile-ui-foundation`. Titik paksa: apply 7.5 dan archive 3.5.

**Self-improving:** verify-loop, mobile-ui-foundation, openspec-preflight ("append here"); issue-burndown + audit-burndown ("fix it HERE").

**Temuan proses (perlu keputusanmu, bukan kode produk):**
1. **`.claude/commands/opsx/{apply,archive,propose,explore}.md` drift dari skill-nya.** Grep `docs/11|preflight|Definition of Done|verify-loop` = **0 hit** di keempat file (apply 281 baris, archive 216). Jadi saat kamu mengetik `/opsx:apply`, yang jalan adalah command tanpa precondition preflight, tanpa gate 7.5, tanpa DoD 3.5 — gate itu hanya ada di skill `openspec-apply-change`/`openspec-archive-change`. Usulan: regular PR kecil yang menjadikan keempat command wrapper tipis ("invoke skill X"). Edit `.claude/**` kena self-modification guard → butuh persetujuanmu eksplisit.
2. **Tidak ada command `/opsx:preflight`** padahal next-change/apply/issue-burndown memanggilnya; yang ada skill `openspec-preflight`. Masuk PR yang sama.
3. Label `promoted` disebut di template triage tapi tidak pernah di-set → stale, bersihkan di PR yang sama.

## 3. Progress per lapisan

| Lapisan | Estimasi | Dasar |
|---|---|---|
| **Backend** | **≈ 90 %** | 137 spec kapabilitas; Phase 1–2 + 3.5 + Phase 4 backend (RevenueCat webhook, grace, privacy-flip, Apple S2S, premium-username, post-edit, image pipeline gated, anomaly detection, referral, appeal, data-export, Sentry) semua ada spec + V1–V39. Sisa: attestation (`:infra:attestation` DESIGN — keputusan tertunda), Sign in with Apple backend, tail-sampling (#175), 11 issue backend (mayoritas S/M), item Pre-Launch §5 yang belum dites (jitter reversibility, backup restore, env separation). |
| **Mobile** | **≈ 85 %** (≈ 93 % dari scope spec) | Matriks audit 2026-09-25: 43 kapabilitas rata-rata 90–100 %; 5 titik lemah (paywall, crash-reporting, chat, push, appeal) — 2 sudah ditutup (#512, #519), chat 429 ditutup (#515). Pengurang: iOS push inert (#495 + operator), suite iOS merah dan tidak di-gate CI (#348), 46 issue mobile, dan **fitur launch tanpa spec**: Sign in with Apple, guest browse + login wall, onboarding carousel, tab profil Postingan/Disukai, self-delete post, polish chat (preview/read receipt). |
| **Admin** | **≈ 88 %** | Board 23 frame: 19 shipped, 2 parsial (dashboard #303, profil #282), 2 belum (frame 11 Attestation Review — tergantung keputusan attestation; frame 14 Account Security WebAuthn + sesi aktif). `/admin/appeals` ship tanpa frame (#392). Invarian admin (CSRF→role, audit in-tx, cap in-tx) semua lolos audit. 13 issue admin, 1 L (#438). |
| **Kesiapan launch (non-kode + prod)** | **≈ 25 %** | `docs/10` Progress Summary: Domain 0/14 (angka stale — `api-staging.nearyou.id` sudah hidup), Developer Programs 0/15, Legal 0/6, Verifikasi 1/8, Infra accounts 43/45 (staging saja). **Belum ada:** workflow deploy production (hanya `deploy-staging.yml`), job backup `pg_dump`+`age`→R2 (grep: nol), Supabase/Upstash prod, badan hukum → D-U-N-S → akun Play/App Store, PSE Komdigi, Privacy Policy/ToS live, DPO/RoPA, Data Safety form + Privacy Nutrition Labels. Pre-Launch §6 (lokasi nyata) ✅, §9 (admin rate-limit) ✅, §8 CI lanes ½ (instrumented ✅ #514; iOS link lane ✗), §7 age assurance ✗, §10 bukti verifikasi ✗. |

## 4. Peta konflik (file rebutan) — aturan per wave

| File / sumber daya | Issue yang menyentuh | Aturan |
|---|---|---|
| `shared/resources/.../strings.xml` + `SharedStringsCatalogTest.kt` (baris `assertEquals(243)`) | #203 #204 #259 #333/#335 #434 #479 #487 #494 #497 #502 #516/#517; mungkin #238 #252 #307 | **#479 DULUAN (Wave 1) dan ubah hitungan hardcoded jadi enumerasi turunan** supaya baris itu berhenti jadi hotspot. Setelah itu maks **3 sesi per wave** yang menambah string; rebase saat merge. |
| `screens/routing/AppEntryProvider.kt` / `NavKeys.kt` / `RootRouterScreen.kt` | #173 #238 #255 #266 #379 #516 | Maks 2 per wave; #238 dan #379 di wave berbeda. |
| `backend/.../Application.kt` (wiring route/service) | #252/#336 #259 #266 #272 #379 #390(+#315) #176 | Maks 2 per wave. |
| **Flyway V40+** | #390 (**V40**, CHECK `notifications.type`), #393 (V41), #496 (mungkin index), #489 opsi 1, #278/#279 (blocked) | **Satu PR migrasi per wave.** Urutan: #390 → #496 → #393. Memory `reference_flyway_version_collision_parallel_sessions`. |
| `.github/workflows/ci.yml` | #184 #348 | Satu bundle (B7). |
| `.github/workflows/deploy-staging.yml` | #381 #506 (#175) | Hanya setelah operator provision slot. |
| `di/KoinInit.kt` | #266 #395 | Wave berbeda (4 vs 5). |
| `iosApp/*` (Xcode) | #258 #430 #495 #504 | Semuanya jalur operator. |
| `templates/admin/layout.peb` / `icons.peb` / `admin.css` | #501+#282 (B9), #438 (nav), #392 | B9 dulu (Wave 1); #438 Wave 5. |
| `ChatThreadScreen.kt` / VM / UiState | #487 #488 #494 (B1), #286, #440, #238 | Urutan: B1 (W2) → #286 + #440 (W4) → #238 (W8). |
| `NearbyTimelineViewModel.kt` | #173 #518 (B2), #204, #338 | B2 (W2) → #204/#338 (W6). |
| `PostDetailScreen.kt` | #497 #242 (B17) | Satu bundle. |
| 3 feed VM/Screen | #516 (W2) → #500 tests (W6) → #238 (W8) | Berurutan. |
| `AppealReviewRepository.kt` + `AdminAuditLogger.kt` | #390 (W3) → #496 (W4) → #393 | Berurutan. |

## 5. Rencana wave (maks 5 sesi paralel; 1 sesi = 1 burndown; bundle = 1 burndown)

Legenda bentuk: **R** = regular PR (`/issue-burndown N`, rute fix/refactor), **O** = OpenSpec penuh (`/issue-burndown N` memilih rute propose→preflight→apply→archive). Angka kurung = ukuran.

### Wave 1 — "zero-contention" (5 sesi)
| # | Sesi | Issue | Bentuk | Kenapa sekarang |
|---|---|---|---|---|
| 1 | B0 Catalog guard | **#479** | R (M) | Membuka semua PR string berikutnya; ganti `assertEquals(243)` dengan enumerasi turunan. Hanya 1 file test. |
| 2 | B9 ADMIN-UI | **#501 + #282** | R (S+S) | Cacat admin terlihat; `icons.peb` + 6 template + `user-profile.peb` + `admin.css`; butuh render annex frame 6/7. `Closes #501` `Closes #282`. |
| 3 | B8 OTEL-LINT | **#177 + #178 + #179 + #180** | O (M) | Satu rule + satu test + satu requirement spec; nol kontak dengan file lain. `Closes #177` `Closes #178` `Closes #179` `Closes #180`. |
| 4 | Profile VM | **#498** | R (S) | Bug kebenaran (POST ganda). 1 VM + 1 test. |
| 5 | Tanggal WIB | **#507** | R (S) | Bug terlihat user; 5 call site, kotlinx-datetime sudah ada; tanpa string baru. |

### Wave 2 — mobile polish + iOS (5 sesi; setelah #479 merge)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | B1 ChatThread | **#487 + #488 + #494** | R (S+S+M) | Satu layar/VM/UiState; strings. |
| 2 | B2 Nearby | **#173 + #518** | O (S+S) | MODIFY `mobile-post-creation` deferral; `AppEntryProvider.onPostCreated` + `reload()` map PremiumGated. |
| 3 | B4 Cap/paywall | **#516 + #517** | O (M+M) | `PaywallEntry.TIMELINE_CAP`; dialog cap pasca-beli; strings; `NavKeys.kt` (bukan AppEntryProvider → aman dgn B2). |
| 4 | B7 iOS tests + CI | **#348 + #400 + #174 + #383(sim run) + #184** | R (M) | **Hanya di Mac lokal** (Xcode). Tambah step `compileTestKotlinIosSimulatorArm64` + grep hardcoded-string ke `ci.yml`. |
| 5 | Spec hygiene | **#499** | O spec-only (S) | + fold stale `mobile-post-detail` "by-id fetch deferred" + spec cleanup #336 (keputusan operator 2026-09-26). |

### Wave 3 — campuran vertikal + trust-boundary (5 sesi) — DIPILIH operator 2026-10-04
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | B10 APPEAL-NOTIFY | **#390** | O (M-L) | **V40** CHECK `notifications.type` += `appeal_decided`; emit in-tx di `AppealReviewRepository`; in-app saja (#315 diputuskan TIDAK push). `Application.kt` hanya bila dispatcher perlu — hindari bila bisa. |
| 2 | TRUST-BOUNDARY | **#544 + #545** | O (M) | Allowlist identitas pemanggil OIDC `/internal/*` (MODIFY `internal-endpoint-auth`) + `RequestBodyLimit` global 64 KiB / 5 MiB images. Keduanya `Application.kt` → satu bundle. |
| 3 | B17 PostDetail | **#497 + #242 + #542** | O (L) | Hapus reply sendiri + restyle frame 7 (render annex dulu) + pindahkan 4 call repo dari composition scope ke `PostDetailViewModel`; pecah file 1161 LOC. |
| 4 | STAGING-PROOF | **#535** | R (M) | `dev/scripts/provision-schedulers.sh` idempoten untuk 9 worker `/internal/*` (OIDC SA + audience) + docs; **operator menjalankannya di staging** setelah merge. |
| 5 | B3 Search | **#253 + #255** | O (S+S) | Satu requirement `mobile-search`; `AppEntryProvider:355-380`. |

Digeser ke Wave 4: B5 (#333 + #335), #277. (Versi asli W3 tersimpan di riwayat git file ini.)

### Wave 4 — admin + konsen + chat lanjutan (+ B5 #333/#335 dan #277 dari W3; #191 → W5) (5–6 sesi)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | Admin history | **#496** | O (M) | Setelah #390 merge (file sama); mungkin **V41** index `after_state->>'user_id'`. |
| 2 | Inline follow | **#307** | O (M) | Backend `followedByViewer` + FollowListRow. |
| 3 | Consent GET | **#266** (part 2) | O (M) | `ConsentRoutes` GET; `KoinInit`, `RootRouter`, `Application.kt`. |
| 4 | Chat edited-since-shared | **#440** (+ **#286** redline di PR yang sama bila waktu ada) | O (M) | Setelah B1 merge. |
| 5 | Report-queue filter | **#191** | O (S-M) | EXISTS `post_edits`, index V22 sudah ada. |

### Wave 5 — backend-first + admin L (5 sesi)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | Moderation-queue viewer | **#438** | O (L) | Capability baru + MODIFY `auth-login-anomaly-detection`; `layout.peb` nav. Prioritas audit #4. |
| 2 | Chat unread | **#272** | O (M-L) | Endpoint `last_read_at` (tanpa migrasi) + badge list. `Application.kt`. |
| 3 | B12 Amplitude | **#395 + #397** | O (M+M) | `KoinInit` (bebas dari #266 yang sudah merge di W4). |
| 4 | Actor username | **#194** | O (M) | `NotificationDto.actor_username` + mobile copy. |
| 5 | Dashboard tiles | **#303** (bagian Premium + CSAM) | O (M) | Issue tetap open untuk widget sisa. |

### Wave 6 — timeline + tests (5 sesi)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | Deep links | **#379** | O (M-L) | reply-by-id / conversation-by-id read; `Application.kt` + nav. |
| 2 | Lua limiter | **#212** | O (M) | Juga item audit 02-H3 → boleh via `/audit-burndown`. Backend-only. |
| 3 | "Diedit" badge | **#338** | O (L) | 3 query timeline hot-path (perf!) + PostCard. |
| 4 | Settings Premium rows | **#502** (Kelola + Restore) | O (M) | "Perjalanan Premium" menunggu keputusan tenure. |
| 5 | Test gaps | **#500** (+ **#204** bila ada slot) | R (M) | Setelah #516 agar test feed tidak di-rebase 2×. |

### Wave 7 — endpoint baru + infra (5 sesi)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | B6 Autocomplete | **#252 + #336** | O (L) | `GET /api/v1/search/usernames` (pg_trgm) + 2 klien. `Application.kt`. |
| 2 | Referral share | **#434** | O (S-M) | expect/actual share sheet. |
| 3 | FCM traceparent | **#176** | O (M) | `TracingHttpTransport` di `:infra:fcm`. |
| 4 | B13 Ads | **#442 + #443** | O (M+M) | Test ad units; gate `ads_enabled`; thread chat tidak pernah beriklan. |
| 5 | (cadangan) | #204 jika belum | O (S-M) | |

### Wave 8 — slice besar terakhir (3 sesi)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | Profile edit | **#259** | O (L) | Endpoint tulis + moderasi + UI. |
| 2 | Kirim pesan dari kartu | **#238** | O (L) | ChatThread + 3 feed VM + nav — paling akhir karena menyentuh semuanya. |
| 3 | iOS push code | **#495 + #197** | R (M) | **Hanya setelah operator #258 + #430** (verifikasi live wajib per DoD). |

### Setelah operator (bukan wave, kapan pun slot operator selesai)
- **B15 OPS-SECRETS:** kamu provision slot `staging-cloudflare-images-*`, `staging-gcp-vision-sa`, `staging-sentry-backend-dsn` → satu PR kecil meng-uncomment `deploy-staging.yml:136-141` (`Closes #381` `Closes #506`). #382 = buat Cloud Scheduler + `run.invoker`, lalu smoke.
- **B14 iOS push:** #258 + #430 (kamu) → #495 + #197 (Wave 8 sesi 3).

**Total yang masuk wave: 58 issue dalam 37 sesi** (W1–W6 masing-masing 5, W7 4 + cadangan, W8 3; sesi W8-3 menunggu operator) + 1 PR kecil B15 pasca-operator. 14 terblokir + 8 keputusan = 22 di luar wave (§6–§7).

## 6. Daftar tugasmu (operator) — terblokir sampai kamu bertindak

| Issue | Tugas | Membuka |
|---|---|---|
| **#258** | Firebase client config (`google-services.json`, `GoogleService-Info.plist`) + APNs key di Firebase console | #495, #197 (varian push), #430 |
| **#430** | NSE Xcode target + App Group (butuh Apple Developer account) | #495 live verify |
| **#504** | Sentry auth token + plugin Gradle + dSYM build phase | symbolication rilis |
| **#506** | Buat Sentry project backend + slot `staging-sentry-backend-dsn` | PR B15 |
| **#381** | Provision slot Secret Manager Cloudflare Images + Vision SA | PR B15 (kode sudah fix) |
| **#382** | Cloud Scheduler `data-export-worker` + `run.invoker` di staging, lalu smoke | — |
| **#280** | Smoke chat realtime 2 device nyata (+ iOS Supabase config) | — |
| **#435**, **#392** | Frame mockup referral (mobile board) + appeal-review (admin board) — input desainmu | #434 polish, #392 |
| **#175** | Deploy OTel Collector (GCP) untuk tail sampling | spec sampling 10 %/100 %/100 % |
| **#182** | Cek changelog firebase-admin ≥ 9.8.0 apakah `ConditionEvaluator` sudah fix (10 menit; bisa kudelegasikan) | hapus bypass |
| #278, #279, #332, #444 | Pemicu belum terjadi (skala / profile-cache / Phase 2+) — biarkan | — |
| **Launch-ops** | Legal entity PT Perorangan → NPWP → NIB → D-U-N-S → Apple/Play org accounts + PSE Komdigi (memory `project_legal_entity_and_dev_accounts_plan`) | store submission |

## 7. Keputusan produk yang kuperlukan darimu (8)

| Issue | Pertanyaan | Default yang kusarankan |
|---|---|---|
| **#315** | Push FCM saat pesan diredaksi admin? | **Tidak** (direvisi 2026-10-03 setelah riset: push hanya untuk hal time-sensitive; notifikasi in-app sudah memenuhi prinsip notice). Tutup "not planned" atau biarkan deferred. |
| **#489** | Percakapan kosong tampil di Pesan penerima: opsi 1 (`created_by` + migrasi, filter server) atau opsi 2 (filter klien)? | **Opsi 1** (benar di semua klien); jadwalkan V42 setelah #393 |
| **#393** | Redaksi `appeal_text` sekarang atau tunggu review UU PDP pre-launch? | Tunggu review pre-launch (low-pri, audiens admin saja) |
| **#503** | Form appeal in-app untuk permanent-ban (#491 sudah closed) — ship atau tetap deferred? | Ship sebelum launch (jalur banding = kewajiban moderasi) → Wave 7 cadangan |
| **#502** | Semantik "Perjalanan Premium"/tenure badge belum dispesifikasi | Spesifikasikan via `/next-change` (fitur tanpa spec), bukan burndown |
| **#396** | `post_viewed` = buka detail atau impression? Backend security events via Amplitude JVM? | detail-open; security events ditunda (Sentry sudah menangkap) |
| **#203** | Locale non-ID? | **Tidak** pre-launch; tutup/biarkan deferred |
| **#186** | Fallback GoogleSignIn legacy (dependency deprecated) | Tunggu bukti dari breadcrumb Sentry (#492 sudah mengirimnya) |

Plus **keputusan arsitektur:** attestation — `project.md` bilang post-MVP, `docs/08` Pre-Launch §5 mewajibkan ("reject emulator, pass legit device"). Pilihan: (a) wajib pre-launch → `/next-change attestation-play-integrity-app-attest` (L, lintas 3 lapisan + frame admin 11); (b) tetap post-MVP → amend `docs/08` + `docs/06` agar tidak kontradiktif. Review arsitektur (chip) akan memberi masukan.

## 8. Item launch tanpa issue — jalur `/next-change`

1. **Sign in with Apple (iOS)** — `docs/08:170`; wajib App Review bila ada login pihak ketiga. L (Swift bridge + backend verifier + account-linking policy docs/06).
2. **Environment production** — workflow `deploy-production.yml` (tag-deploy, flag Layer 3 `--cpu=2 --min-instances=1 --no-cpu-throttling`), secrets tanpa prefix, Supabase/Upstash prod, Cloud Armor/XFF, **job backup** `pg_dump` + `age` → R2 + deletion log (KAD "Backups"), restore test. Ini infra → regular PR, bukan OpenSpec, tapi ukurannya L dan butuh akun/billing dari kamu.
3. **Attestation** — lihat §7.
4. (Dari audit, launch-relevant, belum ada spec) Admin account security frame 14 (WebAuthn enrol + sesi aktif), admin-user administration, guest Global browse + login wall, onboarding carousel, Data Safety form + Privacy Nutrition Labels (dokumen).

## 9. Chip

- **Review arsitektur `[Fable 5.1 xhigh]`** — dibuat sekarang (lihat chip di panel). Prompt berdiri sendiri, max 2 sub-agent, tidak membuat issue.
- **Burndown `[Opus 5.5 xhigh]`** — 5 chip Wave 1 dibuat **setelah kamu menyetujui** Wave 1. Tiap chip = satu `/issue-burndown` dengan daftar issue bundle + kata kunci `Closes #a` per issue + peringatan file rebutan.
- Catatan: 34 issue belum berlabel `ready-to-burndown` (termasuk #487 #488 #507 #516 #517 #502 dll. yang masuk Wave 1–2). `/issue-burndown N` dengan nomor eksplisit tetap bisa jalan (skill melakukan freshness re-check), tapi bila kamu ingin backlog konsisten, satu sesi `/triage-follow-ups` singkat sebelum Wave 2 akan melabeli mereka — aku tidak melabeli apa pun di sesi ini.

## 9b. Adendum 2026-10-04 — 28 issue baru (#530–#558) dari review arsitektur

Total open: **101** (63 `ready-to-burndown`). 27 usulan § 11 review dibuat sebagai #530–#556 + flaky test #558. 7 di antaranya `deferred` di balik production spend gate (#550 IAP, #551 age-assurance legal, #552 alerting, #553 metrics, #554 prod env, #555 re-baseline biaya, #556 checklist "100 %"). Pemetaan sisanya ke wave, mengikuti aturan § 4 (maks 2 sesi/wave di `Application.kt`, 1 migrasi/wave, 1 sesi/wave di workflows):

| Issue | Isi singkat | Kontensi | Masuk |
|---|---|---|---|
| **#543 + #536** | hapus modul mati `:shared:tmp` + bump image Temurin | `settings.gradle.kts`, `Dockerfile` (keduanya) → bundle | **Wave 2b** |
| **#537** | keep-warm ping staging hari kerja (workflow baru) | file workflow baru, bukan `ci.yml` | **Wave 2b** |
| **#558** | flaky `SettingsLogoutViewModelTest` | 1 test | **Wave 2b** |
| **#549** | hygiene backend (dispatcher 2 worker, hapus `redisKoinModule`, cap export) | kecil, tanpa Application.kt | **Wave 2b** |
| **#541** | docs: kalibrasi intensitas review-gate | `openspec/project.md` saja | **Wave 2b** |
| **#542** | PostDetail: pindahkan call repo dari composition scope ke VM | `PostDetailScreen.kt` | **Wave 3 → gabung B17 (#497 + #242 + #542)** |
| **#544 + #545** | allowlist OIDC `/internal/*` + `RequestBodyLimit` global | `Application.kt` (keduanya) → bundle; #544 MODIFY spec `internal-endpoint-auth` | **Wave 4** (gantikan #191 → W5) |
| **#531 + #532** | header keamanan admin + rate-limit login admin | `AdminModule`/plugin admin, Redis limiter | **Wave 5** |
| **#546** | trigger immutability `admin_actions_log` + pool `admin_app` | **migrasi** | **Wave 5** (slot migrasi W5; #390 W3, #496 W4) |
| **#547** | REVOKE anon/authenticated (migrasi guarded) | **migrasi** | **Wave 6** |
| **#548** | sweep retensi soft-deleted posts / post_edits / arsip export | MODIFY `scheduled-retention-cleanup`; mungkin tanpa migrasi | **Wave 6** |
| **#533** | head-sampling 0.3–0.4 + align spec otel | `infra/otel` + spec; sendiri | **Wave 5** |
| **#535** | `provision-schedulers.sh` untuk 9 worker (jalankan di staging = tugasmu) | skrip baru + docs | **Wave 4** (kode) → operator run |
| **#534** | backup job + deletion-log + restore drill (L) | Cloud Run Job + skrip + scheduler; R2 creds staging sudah ada | **Wave 6** (L, sendiri) |
| **#530** | Cloudflare proxied DNS (operator) + origin-header check `ClientIpExtractor` | DNS = kamu; kode kecil | kode **Wave 5**, DNS = tugasmu |
| **#538** | 5 runbook insiden (docs) | `dev/docs/runbooks/` | **Wave 7** |
| **#540** | Dependabot + SHA-pin actions | menyentuh SEMUA workflow → setelah W2-4 (#184/#348) merge | **Wave 3** (slot workflows) |
| ~~#539~~ | `/opsx:*` jadi wrapper tipis ke skill | — | **SELESAI** via PR #557 (merged 2026-10-04); #539 closed |

Catatan: #530 (Cloudflare DNS) dan #535 (menjalankan skrip scheduler) punya separuh operator → tambahkan ke § 6. Wave 1–2 tidak berubah.

**Wave 2b** (isi slot begitu sesi Wave 2 selesai; semua kecil, nol kontensi dengan Wave 2): #543+#536 · #537 · #558 · #549 · #541.

**Catatan merge Wave 2 (dari sesi review arsitektur):** W2-2 (#173/#518) dan W2-3 (#516/#517) sama-sama menyentuh `NearbyTimelineScreen.kt` + `GlobalTimelineScreen.kt` → merge berurutan, yang kedua rebase dulu. Semua sesi W2 diminta sync `main` (#557 mengubah skill/command) sebelum apply.

**Opsi urutan Wave 3 (keputusan operator):** review § 17.4 menyarankan trust-boundary + staging-proof lebih dulu (#544+#545, #535, #537, #534, #546) sebelum vertikal mobile. Rencana ini menaruh vertikal mobile di W3 dan trust-boundary di W4–W6 mengikuti arah "feature-complete dulu" (§ 15). Keduanya sah; campuran yang kusarankan: **W3 = #390 (V40) · #544+#545 · B17 (#497+#242+#542) · #535 · B3 (#253+#255)**, lalu B5 (#333+#335) dan #277 geser ke W4.

## 10. Klasifikasi lengkap 80 issue

Kelas: Low-hanging (4) · Bundle (33) · Berat (21) · Terblokir/operator (14) · Butuh keputusanmu (8). Kolom r-t-b = berlabel `ready-to-burndown`.

| Issue | Judul | Kelas | Bentuk | Bundle | Wave | r-t-b | Catatan |
|---|---|---|---|---|---|---|---|
| [#173](https://github.com/aditrioka/nearyou-id/issues/173) | mobile-post-creation-refresh-nearby-on-return | Bundle | OpenSpec MODIFY mobile-post-creation | B2 Nearby (+#518) | 2 | ✓ | Nearby VM reload + AppEntryProvider onPostCreated |
| [#174](https://github.com/aditrioka/nearyou-id/issues/174) | mobile-post-creation-ios-flow-tests | Bundle | regular | B7 iOS tests | 2 | ✓ | butuh Xcode lokal; setelah #348 hijau |
| [#175](https://github.com/aditrioka/nearyou-id/issues/175) | observability-otel-collector-tail-sampling | Terblokir/operator | OpenSpec + operator | — | — | ✗ | butuh deploy OTel Collector di GCP; L |
| [#176](https://github.com/aditrioka/nearyou-id/issues/176) | observability-otel-fcm-traceparent | Berat | OpenSpec (otel + fcm-push-dispatch) | — | 7 | ✗ | TracingHttpTransport di :infra:fcm; M |
| [#177](https://github.com/aditrioka/nearyou-id/issues/177) | otel-attribute-rule-value-aware-userid-aliases | Bundle | OpenSpec MODIFY observability-otel-foundation | B8 OTEL-LINT | 1 | ✗ | OtelForbiddenAttributeRule + test saja |
| [#178](https://github.com/aditrioka/nearyou-id/issues/178) | otel-attribute-rule-location-key-patterns | Bundle | OpenSpec | B8 OTEL-LINT | 1 | ✗ | jangan false-positive display_location |
| [#179](https://github.com/aditrioka/nearyou-id/issues/179) | otel-attribute-rule-opaque-secrets | Bundle | OpenSpec | B8 OTEL-LINT | 1 | ✗ | Tier-2 regex tambahan |
| [#180](https://github.com/aditrioka/nearyou-id/issues/180) | otel-attribute-rule-psi-context-restricted-mode-a | Bundle | OpenSpec | B8 OTEL-LINT | 1 | ✗ | Mode A PSI-context user_id |
| [#182](https://github.com/aditrioka/nearyou-id/issues/182) | firebase-admin-server-template-evaluate-bypass-removal | Terblokir/operator | regular (S) | — | — | ✗ | upstream firebase-admin; cek changelog ≥9.8.0 dulu (tugas verifikasi 10 menit) |
| [#184](https://github.com/aditrioka/nearyou-id/issues/184) | mobile-negative-requirement-ci-grep | Bundle | regular | B7 iOS tests (ci.yml) | 2 | ✓ | grep hardcoded-string di ci.yml; satu file dgn #348 |
| [#186](https://github.com/aditrioka/nearyou-id/issues/186) | mobile-auth-signin-credential-manager-legacy-fallback | Butuh keputusanmu | regular (M) | — | — | ✗ | tambah dependency deprecated; tunggu bukti kegagalan Credential Manager di Sentry (#492 sudah merge) |
| [#191](https://github.com/aditrioka/nearyou-id/issues/191) | admin-report-queue-has-edit-history-filter | Berat | OpenSpec MODIFY admin-report-queue | — | 4 | ✓ | EXISTS post_edits; admin-only; S-M |
| [#194](https://github.com/aditrioka/nearyou-id/issues/194) | mobile-notifications-actor-username-enrichment | Berat | OpenSpec (in-app-notifications + mobile-notifications-list) | — | 5 | ✓ | backend+mobile; reuse JdbcActorUsernameLookup |
| [#197](https://github.com/aditrioka/nearyou-id/issues/197) | mobile-notifications-live-unread-badge | Bundle | OpenSpec MODIFY mobile-home-tab-host | B14 Push code (+#495) | ops→ | ✓ | varian polling bisa lebih awal; varian FCM setelah #258/#430 |
| [#203](https://github.com/aditrioka/nearyou-id/issues/203) | mobile-localization-language-switching | Butuh keputusanmu | OpenSpec MODIFY mobile-design-system | — | — | ✗ | keputusan produk: locale non-ID; L |
| [#204](https://github.com/aditrioka/nearyou-id/issues/204) | mobile-location-disambiguation-onboarding-hint | Berat | OpenSpec (requirement baru) | (B2 opsional) | 6 | ✓ | hint onboarding lokasi; strings |
| [#212](https://github.com/aditrioka/nearyou-id/issues/212) | Batch timeline read-limiter increments into one Lua round-trip (needs  | Berat | OpenSpec (timeline-read-rate-limit + rate-limit-infra) | — | 6 | ✗ | Lua batch; backend-only; M |
| [#238](https://github.com/aditrioka/nearyou-id/issues/238) | mobile-post-card-send-message-action | Berat | OpenSpec MODIFY mobile-post-card + mobile-chat | — | 8 | ✓ | L; ChatThread + 3 feed VM + nav; setelah B1 & #440 |
| [#242](https://github.com/aditrioka/nearyou-id/issues/242) | Restyle PostDetailScreen to mockup frame 7 (after #234 merges) | Bundle | regular (render frame 7 dulu) | B17 PostDetail (+#497) | 3 | ✓ | verifikasi dulu: PostDetailScreen:931,966 sudah cite frame 7 |
| [#252](https://github.com/aditrioka/nearyou-id/issues/252) | mobile-search: username autocomplete / typeahead | Bundle | OpenSpec MODIFY mobile-search (backend-first) | B6 Autocomplete (+#336) | 7 | ✓ | endpoint baru GET /search/usernames; Application.kt |
| [#253](https://github.com/aditrioka/nearyou-id/issues/253) | mobile-search: proactive Premium upsell on tap (before typing) | Bundle | OpenSpec MODIFY mobile-search | B3 Search (+#255) | 3 | ✓ | sisa: gate on-entry saja; re-eval pasca-beli sudah ada |
| [#255](https://github.com/aditrioka/nearyou-id/issues/255) | mobile-search: enrich the search-origin PostDetailRoute fields | Bundle | OpenSpec MODIFY mobile-search | B3 Search (+#253) | 3 | ✓ | AppEntryProvider:355-380; fetchFullPost sudah ada |
| [#258](https://github.com/aditrioka/nearyou-id/issues/258) | [operator-setup] Firebase client config + APNs key for mobile FCM (sta | Terblokir/operator | operator | B15 iOS push setup | — | ✗ | Firebase client config + APNs key |
| [#259](https://github.com/aditrioka/nearyou-id/issues/259) | mobile-profile-edit: edit bio / display name / username on the profile | Berat | OpenSpec MODIFY mobile-profile (mobile+backend) | — | 8 | ✓ | L; endpoint tulis profil + moderasi + UI |
| [#266](https://github.com/aditrioka/nearyou-id/issues/266) | mobile-settings: durable consent snapshot + server consent-read endpoi | Berat | OpenSpec MODIFY analytics-consent-update + mobile-settings | — | 4 | ✓ | part 2 saja (GET consent); KoinInit + RootRouter + Application.kt |
| [#272](https://github.com/aditrioka/nearyou-id/issues/272) | Per-conversation unread badge + last_read_at write (needs a backend en | Berat | OpenSpec (requirement baru, backend-first) | — | 5 | ✓ | last_read_at sudah ada di V15 → tanpa migrasi; endpoint + badge |
| [#277](https://github.com/aditrioka/nearyou-id/issues/277) | admin: in-panel expedite/clear stuck privacy-flip write action | Berat | OpenSpec MODIFY admin-privacy-flip-monitor | — | 3 | ✓ | write action + audit + re-check subscription_status |
| [#278](https://github.com/aditrioka/nearyou-id/issues/278) | admin: composite (privacy_flip_scheduled_at, id) partial index if the  | Terblokir/operator | regular (migrasi) | — | — | ✗ | pemicu skala belum ada |
| [#279](https://github.com/aditrioka/nearyou-id/issues/279) | admin-block-registry: add a (created_at, blocker_id, blocked_id) keyse | Terblokir/operator | regular (migrasi) | — | — | ✗ | pemicu skala belum ada |
| [#280](https://github.com/aditrioka/nearyou-id/issues/280) | Manual two-device chat realtime smoke (§15.1) + fresh-token-rejoin (12 | Terblokir/operator | operator (verifikasi) | — | — | ✗ | 2 device nyata + iOS Supabase config |
| [#282](https://github.com/aditrioka/nearyou-id/issues/282) | admin profile page: redline to the frame 6/7 mockup measurement annex  | Bundle | regular (annex frame 6/7) | B9 ADMIN-UI (+#501) | 1 | ✓ | user-profile.peb + admin.css |
| [#286](https://github.com/aditrioka/nearyou-id/issues/286) | Chat screens mockup pixel-conformance pass (frames 2 + 5 measurement a | Berat | regular (annex frame 2/5) | (B1 lanjutan) | 4 | ✓ | ChatThread + ConversationList redline; setelah B1 merge |
| [#303](https://github.com/aditrioka/nearyou-id/issues/303) | Operational dashboard: deferred widgets (data-source-absent) | Berat | OpenSpec MODIFY admin-operational-dashboard | — | 5 | ✓ | hanya tile Premium + CSAM yang sudah punya sumber; sisanya tetap open |
| [#307](https://github.com/aditrioka/nearyou-id/issues/307) | mobile-follow-lists: inline follow/unfollow action on follower/followi | Berat | OpenSpec MODIFY mobile-follow-lists + follow-system | — | 4 | ✓ | perlu field followedByViewer di backend (koreksi audit) |
| [#315](https://github.com/aditrioka/nearyou-id/issues/315) | admin-chat-redaction: optional FCM push for chat_message_redacted (cur | Butuh keputusanmu | regular / OpenSpec kecil | (B10 opsional) | — | ✗ | keputusan produk: push saat redaksi? PushCopy + dispatcher ke admin() |
| [#332](https://github.com/aditrioka/nearyou-id/issues/332) | privacy-flip worker: wire Redis profile-cache bust if/when a profile r | Terblokir/operator | regular | — | — | ✗ | belum ada profile read-cache |
| [#333](https://github.com/aditrioka/nearyou-id/issues/333) | mobile-premium-username: proactive cooldown disabled-entry state | Bundle | OpenSpec MODIFY mobile-premium-username (mobile+backend) | B5 Username (+#335) | 3 | ✓ | expose usernameLastChangedAt di UserProfileResponse |
| [#335](https://github.com/aditrioka/nearyou-id/issues/335) | mobile-premium-username: distinct downgrade banner | Bundle | OpenSpec (sama) | B5 Username (+#333) | 3 | ✓ | sinyal = usernameLastChangedAt non-null |
| [#336](https://github.com/aditrioka/nearyou-id/issues/336) | mobile-premium-username: username autocomplete / typeahead | Bundle | OpenSpec (sama #333 spec; endpoint #252) | B6 Autocomplete (+#252) | 7 | ✗ | operator: spec cleanup lalu tutup duplikat #252 |
| [#338](https://github.com/aditrioka/nearyou-id/issues/338) | mobile: timeline-card "Diedit" badge (Nearby/Following/Global) | Berat | OpenSpec MODIFY mobile-post-editing (backend-first) | — | 6 | ✓ | L; indikator edited di 3 query timeline hot-path + PostCard |
| [#348](https://github.com/aditrioka/nearyou-id/issues/348) | iOS flow-test suite red (22/778) — pre-existing DI drift since #234, n | Bundle | regular | B7 iOS tests | 2 | ✓ | ≈22 test merah; RootRouterFlowIosTest sudah fix di #468; tambah step compileTestKotlinIosSimulatorArm64 di ci.yml |
| [#379](https://github.com/aditrioka/nearyou-id/issues/379) | Notification deep-link: reply-target + actor-less chat_message_redacte | Berat | OpenSpec MODIFY mobile-notifications-list (backend-first) | — | 6 | ✓ | reply-by-id read / conversation-by-id; Application.kt + nav |
| [#381](https://github.com/aditrioka/nearyou-id/issues/381) | cloudflare-images + cloud-vision secrets use prefixed resolve → silent | Terblokir/operator | operator → PR workflow kecil | B15 OPS-SECRETS (+#506) | ops→ | ✗ | kode sudah fix (PR #460); tinggal provision slot + uncomment deploy-staging.yml:136-140 |
| [#382](https://github.com/aditrioka/nearyou-id/issues/382) | Complete account-data-export staging worker-runtime smoke (data-export | Terblokir/operator | operator | — | — | ✗ | Cloud Scheduler data-export + run.invoker di staging |
| [#383](https://github.com/aditrioka/nearyou-id/issues/383) | mobile-chat-message-report: iOS-sim run + chat long-press menu mockup  | Bundle | operator-lokal + polish | B7 iOS tests (sim run) | 2 | ✓ | jalankan ChatThreadReportFlowIosTest; redline ikut #286 |
| [#390](https://github.com/aditrioka/nearyou-id/issues/390) | content-moderation-appeal: proactive notification on appeal decision | Bundle | OpenSpec MODIFY content-moderation-appeal + in-app-notifications | B10 APPEAL-NOTIFY | 3 | ✓ | MIGRASI V40 (CHECK notifications.type += appeal_decided); #491 sudah closed → unblocked |
| [#392](https://github.com/aditrioka/nearyou-id/issues/392) | content-moderation-appeal: styled admin appeal-review mockup frame | Butuh keputusanmu | regular (desain) | (B11) | — | ✓ | butuh frame baru di admin mockup board → input desain operator |
| [#393](https://github.com/aditrioka/nearyou-id/issues/393) | content-moderation-appeal: appeal_text moderation/redaction posture | Butuh keputusanmu | OpenSpec (migrasi) | B11 APPEAL-ADMIN | — | ✗ | pemicu: PII pertama di appeal_text / review UU PDP pre-launch |
| [#395](https://github.com/aditrioka/nearyou-id/issues/395) | Amplitude: pre-auth app_opened event + device_id seam | Bundle | OpenSpec MODIFY mobile-amplitude-analytics | B12 Amplitude (+#397) | 5 | ✓ | app_opened pre-auth + device_id |
| [#396](https://github.com/aditrioka/nearyou-id/issues/396) | Amplitude: event-taxonomy expansion (engagement/premium/chat/moderatio | Butuh keputusanmu | OpenSpec (L) | — | — | ✓ | keputusan produk: semantik post_viewed; backend security events |
| [#397](https://github.com/aditrioka/nearyou-id/issues/397) | Amplitude: identify + user-property sourcing | Bundle | OpenSpec (sama) | B12 Amplitude (+#395) | 5 | ✓ | identify + user props; purchaseConfirmed sudah ada |
| [#400](https://github.com/aditrioka/nearyou-id/issues/400) | Amplitude/consent: iOS behavioral round-trip test for DurableConsentSn | Bundle | regular | B7 iOS tests | 2 | ✓ | iosSimulatorArm64Test round-trip NSUserDefaults |
| [#430](https://github.com/aditrioka/nearyou-id/issues/430) | [operator-setup] iOS Notification Service Extension Xcode target + App | Terblokir/operator | operator | B15 iOS push setup | — | ✗ | NSE Xcode target + App Group (Apple dev account) |
| [#434](https://github.com/aditrioka/nearyou-id/issues/434) | mobile-referral: native share-sheet (deferred from mobile-referral-inv | Berat | OpenSpec MODIFY mobile-referral | — | 7 | ✓ | expect/actual share sheet; strings |
| [#435](https://github.com/aditrioka/nearyou-id/issues/435) | mobile-referral: referral surface mockup frame (deferred from mobile-r | Terblokir/operator | desain operator | — | — | ✓ | frame referral di mobile mockup board |
| [#438](https://github.com/aditrioka/nearyou-id/issues/438) | Admin anomaly-review surface for login-source-spread moderation_queue  | Berat | OpenSpec (capability baru + MODIFY auth-login-anomaly-detection) | — | 5 | ✓ | L; viewer moderation_queue generik; layout.peb nav |
| [#440](https://github.com/aditrioka/nearyou-id/issues/440) | chat-embedded-posts: wire the live edit-state source for the 'diedit s | Berat | OpenSpec MODIFY mobile-chat-embedded-posts | — | 4 | ✓ | backend expose latest edit id + ChatThreadScreen:399; setelah B1 |
| [#442](https://github.com/aditrioka/nearyou-id/issues/442) | Ads: interstitial placements (app-open 5/10/15, post-submit 1-in-5) | Bundle | OpenSpec MODIFY mobile-ads | B13 Ads (+#443) | 8 | ✓ | interstitial; test ad units; gate ads_enabled |
| [#443](https://github.com/aditrioka/nearyou-id/issues/443) | Ads: profile-banner + conversation-list native placements | Bundle | OpenSpec (sama) | B13 Ads (+#442) | 8 | ✓ | profile banner + conversation-list native |
| [#444](https://github.com/aditrioka/nearyou-id/issues/444) | Ads: AppLovin MAX mediation (Phase 2+) | Terblokir/operator | OpenSpec | — | — | ✗ | Phase 2+ belum terpicu |
| [#479](https://github.com/aditrioka/nearyou-id/issues/479) | SharedStringsCatalogTest's 1:1-mirror claim has drifted (356 declared  | Low-hanging | regular | B0 (sendiri, DULUAN) | 1 | ✓ | SharedStringsCatalogTest: 364 key vs assertEquals(243); REKOMENDASI: ganti hitungan hardcoded dengan enumerasi turunan agar bukan hotspot lagi |
| [#487](https://github.com/aditrioka/nearyou-id/issues/487) | Chat first-send notification rationale dialog confirms with "Kirim pes | Bundle | regular | B1 ChatThread | 2 | ✗ | label confirm dialog rationale; strings |
| [#488](https://github.com/aditrioka/nearyou-id/issues/488) | Chat thread top-bar title invisible in dark mode (black-on-black; no S | Bundle | regular | B1 ChatThread | 2 | ✗ | Scaffold/Surface di top bar dark mode |
| [#489](https://github.com/aditrioka/nearyou-id/issues/489) | Empty conversation (no messages) surfaces in the recipient's Pesan lis | Butuh keputusanmu | OpenSpec (chat-conversations) | — | — | ✗ | keputusan produk: opsi 1 (created_by + migrasi) vs opsi 2 (filter klien) |
| [#494](https://github.com/aditrioka/nearyou-id/issues/494) | Chat thread: NetworkRetry/TooLong send states not rendered, no pull-to | Bundle | regular (spec-compliance) | B1 ChatThread | 2 | ✓ | render NetworkRetry/TooLong + pull-to-refresh + flag prompt persisten |
| [#495](https://github.com/aditrioka/nearyou-id/issues/495) | iOS push code gaps: FirebaseApp never configured, permission asked at  | Bundle | regular | B14 Push code (+#197) | ops→ | ✗ | FirebaseApp.configure di iOSApp.swift + AppDelegate; verifikasi live butuh #258/#430 |
| [#496](https://github.com/aditrioka/nearyou-id/issues/496) | Admin user history omits bans/unbans applied via the report queue and  | Berat | OpenSpec MODIFY admin-user-management | — | 4 | ✓ | UserProfileRepository:74 filter; mungkin index after_state->>user_id (V41) → setelah #390 |
| [#497](https://github.com/aditrioka/nearyou-id/issues/497) | Own-reply delete: backend DELETE ships with no mobile client and no de | Bundle | OpenSpec MODIFY mobile-post-detail | B17 PostDetail (+#242) | 3 | ✓ | ReplyApiClient.delete + UI; backend DELETE sudah ada |
| [#498](https://github.com/aditrioka/nearyou-id/issues/498) | ProfileViewModel: block/report POST inside StateFlow.update can re-sen | Low-hanging | regular | — | 1 | ✓ | ProfileViewModel:128-129,145-146 POST di dalam state.update; self-profile refresh |
| [#499](https://github.com/aditrioka/nearyou-id/issues/499) | Spec hygiene: six canonical specs still describe shipped surfaces as d | Low-hanging | OpenSpec (spec-only) | — | 2 | ✓ | 6 spec stale + tambah mobile-post-detail "by-id fetch deferred" (stale) |
| [#500](https://github.com/aditrioka/nearyou-id/issues/500) | Spec'd test-scenario gaps across mobile + admin (+ null-preview chat n | Berat | regular (tests) | — | 6 | ✓ | banyak file test + fix null-preview push; setelah #516/#238 agar tidak rebase feed tests 2x |
| [#501](https://github.com/aditrioka/nearyou-id/issues/501) | Admin: Material-Symbols icon names render as literal text (no .ms rule | Bundle | regular | B9 ADMIN-UI (+#282) | 1 | ✓ | 6 template class="ms" + icons.peb; admin.css belum punya .ms |
| [#502](https://github.com/aditrioka/nearyou-id/issues/502) | Settings Premium rows: Kelola langganan + Restore Purchases + Perjalan | Berat | OpenSpec MODIFY mobile-settings | — | 6 | ✗ | Kelola langganan + Restore (bisa); Perjalanan Premium → keputusan tenure (DEC) |
| [#503](https://github.com/aditrioka/nearyou-id/issues/503) | Permanent-ban in-app appeal form (tracker lost when #391 closed) | Butuh keputusanmu | OpenSpec MODIFY mobile-appeal | — | — | ✗ | #491 closed; keputusan: ship form permanent-ban sekarang atau tetap deferred |
| [#504](https://github.com/aditrioka/nearyou-id/issues/504) | [operator-setup] Sentry symbol upload (mapping + dSYM) for release cra | Terblokir/operator | operator | — | — | ✗ | Sentry auth token + plugin Gradle + dSYM phase |
| [#506](https://github.com/aditrioka/nearyou-id/issues/506) | Provision the backend Sentry DSN and verify a live staging event (back | Terblokir/operator | operator → PR workflow kecil | B15 OPS-SECRETS (+#381) | ops→ | ✗ | buat Sentry project + slot; uncomment deploy-staging.yml:141 |
| [#507](https://github.com/aditrioka/nearyou-id/issues/507) | Mobile date labels show the UTC date, not the device-local (WIB) date | Low-hanging | regular | — | 1 | ✗ | 5 call site substringBefore(T) → kotlinx-datetime TimeZone.currentSystemDefault() |
| [#516](https://github.com/aditrioka/nearyou-id/issues/516) | Timeline read cap: the soft/hard limit states have no paywall path | Bundle | OpenSpec (mobile-cap-upsell / mobile-paywall) | B4 Cap/paywall (+#517) | 2 | ✗ | PaywallEntry.TIMELINE_CAP; ListStates + 2 feed screen; strings |
| [#517](https://github.com/aditrioka/nearyou-id/issues/517) | Cap dialogs during the post-purchase webhook-lag window tell a buyer t | Bundle | regular | B4 Cap/paywall (+#516) | 2 | ✗ | issue bilang blocked #512 — SUDAH MERGE, purchaseConfirmed ada (10 ref) |
| [#518](https://github.com/aditrioka/nearyou-id/issues/518) | Nearby: a 403 radius_premium_only on the reload/retry path shows the n | Bundle | regular | B2 Nearby (+#173) | 2 | ✗ | NearbyTimelineViewModel:286-297 reload() tidak map PremiumGated |
