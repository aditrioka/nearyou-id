---
name: openspec-propose
description: Propose a new change with all artifacts (proposal, design, specs, tasks) generated in one step. Use when the user wants to describe what they want to build and get a complete, implementation-ready proposal. NOT for infra/tooling/CI/docs-only work (use a regular PR) — OpenSpec is for spec-driven product changes.
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.0"
---

Create a change and generate all artifacts: `proposal.md` (what & why), `design.md` (how), `specs/**` (deltas), `tasks.md` (steps). When ready to implement, run `/opsx:apply`.

**Input**: a change name (kebab-case) OR a description of what to build.

**Steps**

1. **If input is unclear, ask what to build** via AskUserQuestion (open-ended): "What change do you want to work on? Describe what you want to build or fix." Derive a kebab-case name from the answer. Do NOT proceed without understanding the goal.

2. **Create the change**: `openspec new change "<name>"` → scaffolds `openspec/changes/<name>/` with `.openspec.yaml`.

3. **Get build order**: `openspec status --change "<name>" --json` → `applyRequires` (artifact IDs needed before implementation) + `artifacts` (status + dependencies).

4. **Create artifacts in dependency order until apply-ready.** Track progress with TodoWrite. For each `ready` artifact:
   - `openspec instructions <artifact-id> --change "<name>" --json` → returns `context` + `rules` (constraints for YOU, never copied into the file), `template` (the structure to fill), `instruction` (schema guidance), `outputPath`, `dependencies`.
   - Read completed dependency files, then write the artifact using `template`.
   - Re-run `openspec status` after each; stop when every `applyRequires` artifact is `done`.
   - If an artifact needs unclear context, clarify via AskUserQuestion, then continue — but prefer reasonable decisions to keep momentum; only ask when context is critically unclear.

5. **Show final status**: `openspec status --change "<name>"`.

**Standards-conformance note (nearyou-id — MUST for `:mobile:app` / `:backend:ktor` changes).** Read [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) before authoring `design.md`. The design MUST include a short **"Standards conformance"** note naming which Pattern-Registry patterns it builds on (state holder, navigation, data layer, backend layering — whichever apply), and declare any deviation as an explicit Decision **plus** a `tasks.md` item amending docs/11 § Pattern Registry in the same PR — the anti-patchwork contract, so each isolated-session change consumes the shared skeleton instead of inventing a parallel one. `/next-change` Phase B.4 verifies this note exists.

**Cross-layer scope declaration (nearyou-id — MUST for user-facing capabilities).**

Per [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md), `design.md` MUST declare which layers the capability spans (backend / admin / mobile) and the change MUST ship the full vertical slice — backend wire contract + every client surface that makes the capability usable + every read-path that returns the entity. Any layer deferred to a later change MUST be captured in `specs/**` as a docs/12 §3 deferred requirement (positive statement + negative-guard scenario + tracking `follow-up` issue), never as bare "deferred to follow-up" prose. An undeclared single-layer slice of a multi-layer capability is what `/opsx:preflight` and `/next-change` Phase D flag as blocking.

**Substrate-introducing proposals — propose-time WebSearch (SHOULD, not MUST).** When `What Changes` adds, removes, or activates an entry in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (new pin, version bump, plugin add/remove, OR activation of a previously-pinned-but-unused library), run a fresh dated `WebSearch` BEFORE finalizing artifacts — e.g. `"<library> vs <alternative> <year> best practice"`, `"<library> production ready <month-year>"`, `"<approach> canonical pattern <year>"`. Read 2–3 sources, weighting official framework/library/vendor docs over community blogs. Reasoning: pretrained "canonical pattern for X" knowledge can be 1–2 years stale; this anchors the proposal in current-year reality before Phase D's substrate-rationale lens has to flag it.

- **Search confirms the design-time direction** → drop a 1-line evidence note in the `design.md` Decision rationale (`verified 2026-MM-DD: <library> remains <position> per <source>`) and finalize.
- **Search surfaces a different canonical pattern** → either (a) revise `What Changes` + the `design.md` Decision to match, or (b) log the divergence in `design.md` § Open Questions for the user to resolve BEFORE `/opsx:apply`.

Skip for non-substrate proposals (product features not touching `libs.versions.toml`, refactors, docs-only, format-only OpenSpec changes). SHOULD not MUST because proposals exploring open-ended substrates may benefit from fresh-search *after* exploration; use judgment when the substrate is unclear. This is the earliest gate in the substrate-drift family (propose-time WebSearch → Phase D substrate-rationale lens → project.md "Pre-implementation library re-check" at apply kickoff → "Apply-phase design-revision re-check" mid-implementation), so downstream gates don't have to flip direction mid-review. Precedent: PR [#119](https://github.com/aditrioka/nearyou-id/pull/119) (`shared-resources-swap-to-cmp-resources`) — a propose-time search would have flagged the canonical preload pattern + SVG-on-Android constraint earlier.

**Artifact guidelines**

- Follow the `instruction` field per artifact type; the schema defines what each contains.
- Read dependency artifacts before creating a new one; use `template` as the structure.
- `context` / `rules` / `project_context` blocks are constraints for YOU — NEVER copy them into the output file.

**Output**: summarize change name + location, artifacts created, and prompt "Run `/opsx:apply` to start implementing."

## Safety

Writes only under `openspec/changes/<name>/`; no git push, branch, or destructive ops. If a change with that name already exists, ask whether to continue it or create a new one. Verify each artifact file exists after writing before proceeding.
