# nearyou-id — Analisis & Rencana Burndown Follow-up (2026-10-03)

> Sesi analisis-saja (tidak ada perubahan kode, label, issue, atau PR). Sumber: CLAUDE.md, `openspec/project.md`, `docs/08`, `docs/10`, `docs/11`, audit `dev/audits/2026-09-25-mobile-admin-review/REPORT.md`, 80 issue `follow-up` open (ditarik via `gh` akun `aditrioka`), `.claude/skills/*` + `.claude/commands/opsx/*`, dan grep kode di `origin/main` 311fa870.
>
> **Snapshot:** staging `GET /health/ready` → 200 (0.48 s). **0 PR open.** Tidak ada issue berlabel `burning-down` / `triaging`. 80 issue open, semuanya `follow-up`; 46 berlabel `ready-to-burndown`, 34 belum (sebagian besar temuan 2026-09-26 yang belum ditriage). Flyway terakhir **V39** → nomor bebas berikutnya **V40**.

## Status 2026-10-19 — sapu bersih jilid 1 selesai

> Diverifikasi 2026-10-19 via `gh` (state PR/issue, label, komentar) dan `git log origin/main` (`dcfa6f28`).

- **Sapu bersih jilid 1** (2026-10-12..19, workflow ultracode; berjalan setelah PR kebijakan anti-defer #582 merged): **13 PR merged**, semuanya 2026-10-18.

  | PR | Isi | Menutup |
  |---|---|---|
  | #583 | JWKS forced-refresh single-flight | #567 |
  | #584 | konformansi frame chat 2/5; load-older menjaga posisi scroll | #286, #565 (Refs #383) |
  | #585 | style kelas admin yang belum ber-style, hapus semua `style=` inline, drift test scaffold | #525, #563 |
  | #586 | dispatcher terbatas pool di 2 worker + probe `/health/ready`, hapus modul Koin mati, cap export per run; **invarian secret-resolution direkonsiliasi** di `openspec/project.md` + `CLAUDE.md` | #549 |
  | #587 | appeal: 401 di jalur banned diarahkan ke sign-in yang bisa dipakai | #581 |
  | #588 | bump image Temurin 21.0.12.1_1 + dicatat di docs/09 | #536 |
  | #589 | frame board appeal-review + referral; kalibrasi review gate | #392, #435, #541 |
  | #591 | smoke data-export memicu worker via Cloud Scheduler; `manifest.json` memuat `README.txt` — **diverifikasi smoke staging live** pasca-merge (SMOKE PASS) | #382 |
  | #592 | `SENTRY_BACKEND_DSN` dipetakan ke secret Cloud Run staging | Refs #506 (revisi `nearyou-backend-staging-00423-scd` log `event=sentry_enabled`) |
  | #593 | keep-warm ping staging `/health/ready` hari kerja | #537 |
  | #594 | `SettingsLogoutViewModelTest` menunggu logOut RevenueCat | #558 |
  | #596 | batas tunggu `AppealScreenTest` (401-setelah-refresh) dinaikkan ke 30 s (flaky di CI) | — |
  | #597 | `REVENUECAT_SECRET_API_KEY` dipetakan; operator sudah provision `staging-revenuecat-secret-api-key` | — |

- **Ditutup tanpa PR:** #383 (duplikat #286; bagian 1 sudah di #566, bagian 2 dicek di #584). #506 ditutup setelah verifikasi (`sentry_enabled` di revisi 00423, `/health/ready` 200).
- **Diparkir dengan label `deferred`** (11; tiap issue punya komentar kategori): **operator** #175 #280 #381 #444 · **produk** #315 #393 #489 #570 · **skala** #278 #279 #332.
- **Unpark:** #503 — blocker-nya (#491) sudah closed; sekarang kelas `large` / OpenSpec (MODIFY `mobile-appeal` + gate di `SignInViewModel`).
- **Issue baru:** #590 (elemen frame chat 2/5 yang menunggu wire backend atau keputusan spec; dipecah dari #584) · #595 (shared-auth: 401 pada retry setelah refresh sukses tidak pernah meng-invalidate sesi; butuh MODIFY `mobile-auth-signin`; ditemukan saat #587).
- **Backlog `follow-up`: 89 → 75 open** (dihitung ulang 2026-10-19). Label: 37 `ready-to-burndown` (8 di antaranya `burning-down`), 24 `deferred`, 14 tanpa keduanya (#176 #212 #502 #503 #522 #556 #569 #574 #575 #576 #577 #578 #590 #595). Flyway head **V40** (#568) → nomor bebas berikutnya **V41**.
- **Tugas operator selesai:** DSN Sentry staging (#592/#506), secret key RevenueCat staging (#597), smoke pasca-merge #591.
- **Masih opsional (tidak memblokir apa pun):** (1) satu grant manual admin ke user uji, lalu cek di dashboard RevenueCat; (2) error staging alami pertama muncul di Sentry (belum ada ERROR/500 sejak revisi 00423, jadi belum ada yang ditangkap); (3) satu run keep-warm saat cold-start (#593).
- **Wave 4 berjalan (per 2026-10-19), satu sesi per item:** #496 · #307 · #266 · #440 · #531+#532 · #333+#335 · #277 (lihat § 5). 8 issue sudah berlabel `burning-down`; #266 belum diklaim saat catatan ini ditulis; belum ada PR Wave 4 yang terbuka. Sesi **"sapu bersih jilid 2"** sedang menyurvei sisa backlog.
- **Diperbarui di dokumen ini:** § 0, § 1, § 4, § 5 (Wave 4–5 + B15), § 6, § 7, § 9b (status). § 10 tetap snapshot 2026-10-03.

## Status 2026-10-04 (update)

- **Wave 1:** 4/5 merged — #479 (PR #523, hitungan pinned dihapus → `strings.xml` bukan hotspot lagi), #501+#282 (PR #527), #498 (PR #521), #507 (PR #524). **#177–#180 = PR #520, CI hijau + mergeable, menunggu merge** (label `burning-down` masih menempel, lepas otomatis saat issue tertutup).
- **Konteks baru:** review arsitektur merged (PR #526, `dev/audits/2026-10-03-architecture-review/REPORT.md`: 27 usulan issue BELUM dibuat, top-5 pre-launch di § 14, addendum § 15 "feature-complete dulu, produksi belakangan"); doc drift fix merged (#528); PR #529 open = keputusan operator Android-first + production spend gate + lane `apple-paid` (#258/#430/#495 ikut ke lane itu — konsisten dengan rencana ini). Issue baru: #522 (OTel sentinel scenario tests, lanjutan #520), #525 (kelas admin tanpa style, lanjutan #501/#282). 77 issue follow-up open.
- **Wave 2 (disetujui untuk di-list, chip belum dibuat):** lihat § 5 Wave 2; perubahan: aturan "maks 3 sesi menyentuh strings.xml" dilonggarkan (hotspot hilang), #499 harus re-cek sisa drift setelah #528/#529, #525 masuk sebagai cadangan Wave 2 / sesi Wave 3.

## Status 2026-10-12 — Wave 1–3 selesai

- **Wave 2: 5/5 merged** (+ #559 feed refresh, #566 iOS suite hijau + lane CI `ios-test` + rule Detekt string hardcoded). **Wave 3: 5/5 merged** — #568 (#390), #572 (#544+#545), #573 (#497+#242+#542), #579 (#535; skrip sudah dijalankan di staging dengan persetujuan operator, 9 job hidup, semua worker 200), #571 (#253+#255). Juga merged #580 (skill: verifikasi klaim live-state sebelum membangun). 0 PR burndown open; hanya #562 (dokumen ini).
- **Backlog: 89 open.** Wave 3 menutup 9 issue tetapi melahirkan **9 issue baru** (#567 flaky JwksCacheTest; #569 #570 #574 #575 #576 #577 dari sesi PostDetail; #578 spec hygiene scheduler; #581 appeal dead-end) — bukti langsung untuk kebijakan anti-defer (chip PR kebijakan sedang berjalan): #576 (LaunchedEffect edit-history, kelas bug sama dengan #542, file sama) dan #577 seharusnya diperbaiki in-PR.
- **Sisa operator dari #579:** setelah #572 ter-deploy, pensiunkan SA legacy `unban-scheduler-staging` per runbook. Staging tidak butuh apa pun lagi.
- **Berikutnya:** sesi sapu bersih ultracode (chip sudah ada; jalankan sendirian) → lalu Wave 4 per § 5 + § 9b (#496 V41, #307, #266, #440+#286, #531+#532, B5 #333+#335, #277).

## Status 2026-10-04 malam (sinkronisasi kedua)

- **Wave 1: 5/5 merged** (#520 #521 #523 #524 #527). **Wave 2: 3/5 merged** — W2-1 chat (#564), W2-3 cap→paywall (#560), W2-5 spec hygiene (#561; #336 ditutup duplikat, #563 lahir dari sini). **Masih open:** W2-2 = PR #559 (#173/#518; CI test/device-run/instrumented pending), W2-4 = PR #566 (#348/#400/#174/#184 + `ios-test` lane + rule Detekt `MobileHardcodedStringRule`; **CONFLICTING** di `ChatThreadReportFlowIosTest.kt` vs #564 → rebase oleh sesi W2-4). 6 issue berlabel `burning-down` = milik dua PR itu.
- **95 issue open (60 r-t-b).** Baru: #563 (drift test/komentar admin, S, nol kontensi), #565 (pull-to-refresh chat sulit dijangkau di thread panjang, S-M, `ChatThreadScreen.kt`).
- **Chip Wave 3 di-dismiss** atas permintaan operator untuk sinkronisasi; urutan start yang direkomendasikan di bawah.

**Mulai sekarang (tidak bergantung PR open):** W3-2 #544+#545 (backend) · W3-4 #535 (skrip scheduler; SA harus sama dgn allowlist #544) · W3-1 #390 (V40; hindari `Application.kt`). Pengisi slot kapan saja: #543+#536 · #558 · #549 · #563.
**Setelah #559 merge:** W3-5 #253+#255 (keduanya `AppEntryProvider.kt`). **Setelah #566 merge:** W3-3 #497+#242+#542 (#566 mengubah `PostDetailFlowIosTest.kt` dan menambah lane iOS yang akan menguji hasilnya) · #541 (#566 menyentuh `openspec/project.md`) · #565.

## 0. Ringkasan 1 menit

| | |
|---|---|
| **Sekarang (2026-10-19)** | **75 issue open** (37 `ready-to-burndown`, 24 `deferred`, 14 tanpa keduanya). Wave 1–3 + sapu bersih jilid 1 selesai; Wave 4 berjalan (7 sesi); sapu bersih jilid 2 menyurvei sisanya. Flyway bebas berikutnya **V41**. |
| **Progress** (estimasi 10-03, belum dihitung ulang) | Backend ≈ 90 % · Mobile ≈ 85 % (93 % dari spec, 85 % dari scope launch) · Admin ≈ 88 % · Kesiapan launch (non-kode + prod) ≈ 25 % |
| **80 issue** (snapshot 10-03) | 4 low-hanging · 33 dalam 17 bundle · 21 berat (solo) · 14 terblokir/operator · 8 butuh keputusanmu |
| **Wave** | 8 wave × 4–5 sesi; Wave 1 = 5 sesi tanpa satu pun menyentuh `strings.xml`/Flyway/`Application.kt` kecuali #479 yang justru memperbaiki hotspot-nya |
| **Keputusan yang kutunggu** | ~~(a) Wave 1~~ selesai · (b) keputusan produk: 4 kini diparkir `deferred` kategori produk (#315 #393 #489 #570), #503 sudah di-unpark, sisanya § 7 · (c) attestation: post-MVP vs wajib pre-launch (belum berubah) · ~~(d) drift `/opsx:*`~~ selesai di #557 |
| **Chip** | Selesai dipakai. Wave 4 dijalankan sebagai sesi terpisah per item (§ 5). |

---

## 1. Orientasi — hal yang berubah / perlu kamu tahu

- **Audit 2026-09-25 "burn order" sudah 4/6 selesai:** #490 (PR #512), #491 (#513), #492 (#519), #493 (#515) merged. Sisa: **#438** (viewer moderation_queue generik) dan **#501** (ikon admin jadi teks). Keduanya masuk rencana.
- **Premise beberapa issue sudah berubah** (diverifikasi grep): #517 bilang blocked #512 → sudah merge, `purchaseConfirmed` ada; #390 blocked #491 → closed; #381 sisi kode sudah fix (PR #460) tinggal operator; #253 bagian re-eval pasca-beli sudah ada; #266 part 1 sudah ship; #272 tidak butuh migrasi (`last_read_at` sudah ada di V15); #348 `RootRouterFlowIosTest` sudah fix di #468.
- ~~**CLAUDE.md stale:** "admin UI intentionally unstyled so far"~~ — sudah diperbaiki (CLAUDE.md kini menyebut `admin.css` sejak #226/#241 dan kelas terakhir ditutup #585).
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

## 4. Peta konflik (file rebutan) — aturan per wave (diperbarui 2026-10-19)

Baris yang sumber kontensinya sudah hilang dicoret; issue yang sudah closed tidak didaftar lagi. Versi 10-03 ada di riwayat git file ini.

| File / sumber daya | Issue open yang menyentuh | Aturan |
|---|---|---|
| ~~`strings.xml` + `SharedStringsCatalogTest.kt` (hitungan pinned)~~ | #204 #238 #252 #259 #307 #333/#335 #434 #502 #503; mungkin #569 #574 #590 #595 | **Bukan hotspot lagi:** #523 (#479) mengganti `assertEquals(243)` dengan enumerasi turunan dari `strings.xml`. Penambahan string cukup rebase biasa; batas "maks 3 sesi per wave" dicabut. |
| `screens/routing/AppEntryProvider.kt` / `NavKeys.kt` / `RootRouterScreen.kt` | #238 #266 (W4) #379; mungkin #503 (entry appeal dari sign-in) | Maks 2 per wave. W4 hanya #266. |
| `backend/.../Application.kt` (wiring route/service) | #176 #252 #259 #266 (W4) #272 #379; mungkin #531/#532 (W4) | Maks 2 per wave. W4: #266 + (bila perlu) bundle #531/#532 — tepat di batas. |
| **Flyway V41+** | #496 (W4, mungkin index `after_state->>'user_id'`), #546 (trigger immutability, W5), #547 (REVOKE guarded, W6); `deferred`: #393 #489 #570 #278 #279 | **V40 sudah dipakai #568 (#390).** Satu PR migrasi per wave; W4 = #496 saja. Sesi W4 lain yang ternyata butuh migrasi wajib `git mv` ke V berikutnya sebelum merge (memory `reference_flyway_version_collision_parallel_sessions`). |
| `.github/workflows/ci.yml` | #540 (Dependabot + SHA-pin, menyentuh SEMUA workflow) | #184/#348 selesai di #566 (lane `ios-test` + rule Detekt). #540 sendirian dalam satu wave. |
| `.github/workflows/deploy-staging.yml` | #381 (`deferred` operator), #175 (`deferred` operator); mungkin #534 (job backup) | #506 → #592 dan RevenueCat → #597 sudah merged (24 mapping `--set-secrets` live). Sisanya menunggu provision slot oleh operator. |
| Mobile `di/KoinInit.kt` | #266 (W4) #395 | Wave berbeda (W4 vs W5). |
| `iosApp/*` (Xcode) | #258 #430 #495 #504 | Semuanya `deferred` (lane `apple-paid` / operator). |
| `templates/admin/layout.peb` / `admin.css` / template per halaman | #496 (W4, `user-profile.peb`), #277 (W4, privacy-flip monitor), #531+#532 (W4, `layout.peb` + `login.peb`), #438 (nav, W5), #303 (dashboard) | B9 (#527) dan #525 (#585) selesai. **`style=` inline di template admin kini nol** dan dijaga `AdminTemplateStyleTest` (gagal di PR CI) → style baru wajib jadi kelas di `admin.css`. W4 punya 3 sesi admin → merge berurutan, sesi berikutnya rebase `admin.css`. Catatan #531: masih ada 3 blok `<script>` inline (`layout.peb:20`, `login.peb:36`, `wordlist-editor-content.peb:90`), jadi CSP `script-src 'self'` perlu memindahkannya ke file statis (+ SHA256SUMS admin static, cek CI-only). |
| Chat thread: **`ChatThreadScreen.kt` (345 LOC) + `ChatMessageList.kt` (243) + `ChatThreadBars.kt` (187)** / `ChatThreadUiState.kt` / VM | #440 (W4; gate `editedSinceShared` di `ChatMessageList.kt:204`), #590, #272 (daftar percakapan), #238 | #584 memecah `ChatThreadScreen.kt` jadi tiga file; B1 (#564) dan #286 (#584) selesai. Urutan: #440 (W4) → #590/#272 → #238 (W8). Referensi lama "`ChatThreadScreen:399`" di § 10 sudah tidak berlaku. |
| `NearbyTimelineViewModel.kt` | #204, #338 | B2 selesai (#559). #204/#338 tetap W6. |
| `screens/post/PostDetail*.kt` (#573 memecah `PostDetailScreen.kt` → `PostDetailHeader.kt` / `PostDetailReplies.kt` / `PostDetailComposer.kt`) | #569 #576; lintas layar: #575 #577 | B17 selesai (#573). #569 + #576 cocok satu bundle; #575 (badge Premium) dan #577 (`NonCancellable` di luar post-detail) menyentuh feed/VM lain juga. |
| 3 feed VM/Screen + `PostCard` | #338 #500 #575 #238 | #516 selesai (#560). Urutan: #500 tests → #338/#575 → #238. |
| `AppealReviewRepository.kt` + `AdminAuditLogger.kt` | #496 (W4) → #393 (`deferred`) | #390 selesai (#568). |
| Spec `mobile-auth-signin` / auth `HttpClient` + `SessionInvalidator` | #595, #503 (`SignInViewModel`) | Dua MODIFY spec auth/appeal mobile → jangan paralel; #595 dulu (bug), lalu #503. |
| `UserProfileResponse` / profil premium-username | #333+#335 (W4), #307 (W4, DTO follow list), #259 | #307 dan #333/#335 menyentuh DTO berbeda → aman paralel; #259 (W8) setelah keduanya. |

## 5. Rencana wave (maks 5 sesi paralel; 1 sesi = 1 burndown; bundle = 1 burndown)

Legenda bentuk: **R** = regular PR (`/issue-burndown N`, rute fix/refactor), **O** = OpenSpec penuh (`/issue-burndown N` memilih rute propose→preflight→apply→archive). Angka kurung = ukuran.

### Wave 1 — "zero-contention" (5 sesi) — ✅ selesai (#520 #521 #523 #524 #527)
| # | Sesi | Issue | Bentuk | Kenapa sekarang |
|---|---|---|---|---|
| 1 | B0 Catalog guard | **#479** | R (M) | Membuka semua PR string berikutnya; ganti `assertEquals(243)` dengan enumerasi turunan. Hanya 1 file test. |
| 2 | B9 ADMIN-UI | **#501 + #282** | R (S+S) | Cacat admin terlihat; `icons.peb` + 6 template + `user-profile.peb` + `admin.css`; butuh render annex frame 6/7. `Closes #501` `Closes #282`. |
| 3 | B8 OTEL-LINT | **#177 + #178 + #179 + #180** | O (M) | Satu rule + satu test + satu requirement spec; nol kontak dengan file lain. `Closes #177` `Closes #178` `Closes #179` `Closes #180`. |
| 4 | Profile VM | **#498** | R (S) | Bug kebenaran (POST ganda). 1 VM + 1 test. |
| 5 | Tanggal WIB | **#507** | R (S) | Bug terlihat user; 5 call site, kotlinx-datetime sudah ada; tanpa string baru. |

### Wave 2 — mobile polish + iOS (5 sesi; setelah #479 merge) — ✅ selesai (#559 #560 #561 #564 #566)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | B1 ChatThread | **#487 + #488 + #494** | R (S+S+M) | Satu layar/VM/UiState; strings. |
| 2 | B2 Nearby | **#173 + #518** | O (S+S) | MODIFY `mobile-post-creation` deferral; `AppEntryProvider.onPostCreated` + `reload()` map PremiumGated. |
| 3 | B4 Cap/paywall | **#516 + #517** | O (M+M) | `PaywallEntry.TIMELINE_CAP`; dialog cap pasca-beli; strings; `NavKeys.kt` (bukan AppEntryProvider → aman dgn B2). |
| 4 | B7 iOS tests + CI | **#348 + #400 + #174 + #383(sim run) + #184** | R (M) | **Hanya di Mac lokal** (Xcode). Tambah step `compileTestKotlinIosSimulatorArm64` + grep hardcoded-string ke `ci.yml`. |
| 5 | Spec hygiene | **#499** | O spec-only (S) | + fold stale `mobile-post-detail` "by-id fetch deferred" + spec cleanup #336 (keputusan operator 2026-09-26). |

### Wave 3 — campuran vertikal + trust-boundary (5 sesi) — DIPILIH operator 2026-10-04 — ✅ selesai (#568 #571 #572 #573 #579)
| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | B10 APPEAL-NOTIFY | **#390** | O (M-L) | **V40** CHECK `notifications.type` += `appeal_decided`; emit in-tx di `AppealReviewRepository`; in-app saja (#315 diputuskan TIDAK push). `Application.kt` hanya bila dispatcher perlu — hindari bila bisa. |
| 2 | TRUST-BOUNDARY | **#544 + #545** | O (M) | Allowlist identitas pemanggil OIDC `/internal/*` (MODIFY `internal-endpoint-auth`) + `RequestBodyLimit` global 64 KiB / 5 MiB images. Keduanya `Application.kt` → satu bundle. |
| 3 | B17 PostDetail | **#497 + #242 + #542** | O (L) | Hapus reply sendiri + restyle frame 7 (render annex dulu) + pindahkan 4 call repo dari composition scope ke `PostDetailViewModel`; pecah file 1161 LOC. |
| 4 | STAGING-PROOF | **#535** | R (M) | `dev/scripts/provision-schedulers.sh` idempoten untuk 9 worker `/internal/*` (OIDC SA + audience) + docs; **operator menjalankannya di staging** setelah merge. |
| 5 | B3 Search | **#253 + #255** | O (S+S) | Satu requirement `mobile-search`; `AppEntryProvider:355-380`. |

Digeser ke Wave 4: B5 (#333 + #335), #277. (Versi asli W3 tersimpan di riwayat git file ini.)

### Wave 4 — admin + konsen + chat lanjutan — 🟡 BERJALAN (per 2026-10-19; 7 sesi, satu per item)
Komposisi aktual (berbeda dari rencana 10-04): #286 sudah selesai di sapu bersih (#584); #191 tetap ke W5; #531+#532 naik dari W5. 7 sesi paralel melebihi pedoman 5 di judul § 5 → perhatikan baris admin, `Application.kt`, dan Flyway di § 4.

| # | Sesi | Issue | Bentuk | Catatan |
|---|---|---|---|---|
| 1 | Admin history | **#496** | O (M) | #390 sudah merge (#568); satu-satunya kandidat migrasi W4 (**V41** index `after_state->>'user_id'`). |
| 2 | Inline follow | **#307** | O (M) | Backend `followedByViewer` + FollowListRow. |
| 3 | Consent GET | **#266** (part 2) | O (M) | `ConsentRoutes` GET; `KoinInit`, `RootRouter`, `Application.kt`. Belum berlabel `burning-down` saat catatan ini ditulis. |
| 4 | Chat edited-since-shared | **#440** | O (M) | B1 (#564) + #286 (#584) sudah merge; gate ada di `ChatMessageList.kt:204` setelah pemecahan file. |
| 5 | Admin hardening | **#531 + #532** | O (M) | Header keamanan (CSP, frame-ancestors, HSTS) + rate-limit/lockout `POST /admin/login`. Lihat catatan `<script>` inline di § 4. |
| 6 | B5 Username | **#333 + #335** | O (S+S) | Expose `usernameLastChangedAt` di `UserProfileResponse` + banner downgrade. |
| 7 | Privacy-flip expedite | **#277** | O (M) | Write action + audit + re-cek `subscription_status`; template admin (rebase `admin.css` dengan #496/#531). |

### Wave 5 — backend-first + admin L (5 sesi)
Tambahan dari § 9b / W4: #191 (report-queue filter), #546 (migrasi = slot migrasi W5), #533, kode #530. #531+#532 sudah naik ke W4. Sapu bersih jilid 2 dapat mengubah susunan ini.
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
- **B15 OPS-SECRETS:** ✅ sebagian. Sentry → #592 (#506 closed setelah `sentry_enabled` di revisi 00423); RevenueCat secret → #597; #382 → #591 + smoke operator PASS. **Sisa:** #381 (`deferred` operator) — provision slot `staging-cloudflare-images-*` (produk berbayar) + `staging-gcp-vision-sa`, lalu satu PR kecil memindahkan mapping-nya ke list `--set-secrets` live.
- **B14 iOS push:** #258 + #430 (kamu) → #495 + #197 (Wave 8 sesi 3).

**Total yang masuk wave: 58 issue dalam 37 sesi** (W1–W6 masing-masing 5, W7 4 + cadangan, W8 3; sesi W8-3 menunggu operator) + 1 PR kecil B15 pasca-operator. 14 terblokir + 8 keputusan = 22 di luar wave (§6–§7).

## 6. Daftar tugasmu (operator) — diperbarui 2026-10-19

**Selesai sejak 10-12:** slot `staging-sentry-backend-dsn` + project Sentry backend (#506 → #592; `event=sentry_enabled` di revisi `nearyou-backend-staging-00423-scd`) · slot `staging-revenuecat-secret-api-key` (→ #597) · smoke data-export pasca-merge #591 (#382, SMOKE PASS) · skrip `provision-schedulers.sh` dijalankan di staging (#579) · frame mockup appeal-review + referral (#392/#435 → #589, digambar sesi sapu bersih).

**Masih terbuka:**

| Issue | Tugas | Membuka / status |
|---|---|---|
| — (sisa #579) | Pensiunkan SA legacy `unban-scheduler-staging` per runbook (#572 sudah ter-deploy). **Dicek 2026-10-19: SA masih ada dan aktif.** | kebersihan IAM staging |
| **#258** | Firebase client config (`google-services.json`, `GoogleService-Info.plist`) + APNs key | `deferred`, lane `apple-paid` → #495, #197 (varian push), #430 |
| **#430** | NSE Xcode target + App Group (Apple Developer account) | `deferred`, lane `apple-paid` → #495 live verify |
| **#504** | Sentry auth token + plugin Gradle + dSYM build phase | `deferred` → symbolication rilis |
| **#381** | Provision slot Cloudflare Images (produk berbayar) + Vision SA, lalu PR kecil mapping `deploy-staging.yml` | `deferred` operator (kode sudah fix di #460) |
| **#280** | Smoke chat realtime 2 device nyata (+ iOS Supabase config) | `deferred` operator (agen tidak bisa menyetir 2 device fisik) |
| **#175** | OTel Collector untuk tail sampling | `deferred` operator; terhalang gate production spend (Open Decision #36) |
| **#444** | Akun AppLovin MAX + SDK key | `deferred` operator; pemicu Phase 2+ belum terjadi |
| **#530** | Cloudflare proxied DNS untuk `api-staging` (bagian DNS; kodenya W5) | `ready-to-burndown` |
| **#182** | Cek changelog firebase-admin ≥ 9.8.0 apakah `ConditionEvaluator` sudah fix | `deferred` |
| #550–#555 | LB/IAP admin, keputusan age-assurance, alerting, metrics, env production, re-baseline biaya | `deferred` di balik gate production spend |
| #278, #279, #332 | Pemicu skala belum terjadi — biarkan | `deferred` skala |
| **Launch-ops** | Legal entity PT Perorangan → NPWP → NIB → D-U-N-S → Apple/Play org accounts + PSE Komdigi (memory `project_legal_entity_and_dev_accounts_plan`) | store submission |

**Opsional (tidak memblokir):** (1) satu grant manual admin ke user uji, lalu cek di dashboard RevenueCat (jalur `RevenueCatPromotionalGranter` kini punya secret); (2) tunggu error staging alami pertama muncul di Sentry; (3) amati satu run keep-warm (#593) saat cold-start.

## 7. Keputusan produk yang kuperlukan darimu (diperbarui 2026-10-19)

Sapu bersih jilid 1 memarkir keputusan yang belum diambil dengan label `deferred` + komentar kategori **produk**; #503 sudah tidak menunggu keputusan; #570 masuk sebagai keputusan baru.

| Issue | Pertanyaan | Default yang kusarankan | Status 2026-10-19 |
|---|---|---|---|
| **#315** | Push FCM saat pesan diredaksi admin? | **Tidak** (push hanya untuk hal time-sensitive; notifikasi in-app sudah memenuhi prinsip notice). Tutup "not planned". | `deferred` produk — menunggumu |
| **#489** | Percakapan kosong tampil di Pesan penerima: filter server (`created_by` + migrasi), buat percakapan saat kirim pertama (klien), atau terima perilaku sekarang? | **Filter server** (benar di semua klien); migrasi setelah slot V41 | `deferred` produk — menunggumu |
| **#393** | Redaksi `appeal_text` sekarang atau tunggu review UU PDP pre-launch? | Tunggu review pre-launch (pemicunya tidak mungkin terjadi sebelum launch) | `deferred` produk — menunggumu |
| **#570** | Like per-reply (frame 7): notifikasi ke penulis reply? masuk cap like Free 10/hari? | Belum ada default; docs/02 belum memuatnya → `/next-change` bila mau | **baru**, `deferred` produk |
| ~~#503~~ | Form appeal in-app untuk permanent-ban | Ship sebelum launch | **Diputuskan:** unpark (blocker #491 closed), `large` / OpenSpec MODIFY `mobile-appeal` |
| **#502** | Semantik "Perjalanan Premium"/tenure badge belum dispesifikasi | Spesifikasikan via `/next-change`; Kelola + Restore bisa jalan duluan | belum berlabel — disurvei sapu bersih jilid 2 |
| **#396** | `post_viewed` = buka detail atau impression? Security events backend via Amplitude JVM? | detail-open; security events ditunda (Sentry sudah menangkap) | `ready-to-burndown` dengan default ini |
| **#203** | Locale non-ID? | **Tidak** pre-launch | `deferred` |
| **#186** | Fallback GoogleSignIn legacy (dependency deprecated) | Tunggu bukti dari breadcrumb Sentry (#492) | `deferred` |

Plus **keputusan arsitektur:** attestation — `project.md` bilang post-MVP, `docs/08` Pre-Launch §5 mewajibkan ("reject emulator, pass legit device"). Pilihan: (a) wajib pre-launch → `/next-change attestation-play-integrity-app-attest` (L, lintas 3 lapisan + frame admin 11); (b) tetap post-MVP → amend `docs/08` + `docs/06` agar tidak kontradiktif. **Status 2026-10-19:** sisi iOS (App Attest) ikut lane `apple-paid` sejak #529; keputusan Play Integrity (Android) belum tercatat di docs.

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

**Status tabel ini per 2026-10-19:** selesai #536 (#588), #537 (#593), #558 (#594), #549 (#586), #541 (#589), #542 (#573), #544+#545 (#572), #535 (#579, sudah dijalankan di staging). #531+#532 sedang dikerjakan di W4. Masih open: #543 (tanpa pasangan #536 lagi), #540, #546, #547, #548, #533, #534, #530, #538; #550–#555 tetap `deferred`, #556 belum berlabel.

**Wave 2b** (isi slot begitu sesi Wave 2 selesai; semua kecil, nol kontensi dengan Wave 2): #543+#536 · #537 · #558 · #549 · #541.

**Catatan merge Wave 2 (dari sesi review arsitektur):** W2-2 (#173/#518) dan W2-3 (#516/#517) sama-sama menyentuh `NearbyTimelineScreen.kt` + `GlobalTimelineScreen.kt` → merge berurutan, yang kedua rebase dulu. Semua sesi W2 diminta sync `main` (#557 mengubah skill/command) sebelum apply.

**Opsi urutan Wave 3 (keputusan operator):** review § 17.4 menyarankan trust-boundary + staging-proof lebih dulu (#544+#545, #535, #537, #534, #546) sebelum vertikal mobile. Rencana ini menaruh vertikal mobile di W3 dan trust-boundary di W4–W6 mengikuti arah "feature-complete dulu" (§ 15). Keduanya sah; campuran yang kusarankan: **W3 = #390 (V40) · #544+#545 · B17 (#497+#242+#542) · #535 · B3 (#253+#255)**, lalu B5 (#333+#335) dan #277 geser ke W4.

## 10. Klasifikasi lengkap 80 issue (snapshot 2026-10-03 — kolom Wave/r-t-b tidak dipelihara; status terkini di blok Status + § 4–§ 7)

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
