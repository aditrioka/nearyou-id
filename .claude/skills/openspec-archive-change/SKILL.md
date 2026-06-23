---
name: openspec-archive-change
description: Archive a completed change in the experimental workflow — gate on Definition-of-Done + TBD-Purpose, sync delta specs, move the change under archive/, and push the archive commit to the existing PR. Use when finalizing a change after implementation + review are done. NOT before implementation/smoke/verification are complete.
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.0"
---

Archive a completed change.

**Input**: optionally a change name. If omitted, infer from context; if ambiguous you MUST prompt.

**Steps**

1. **Select the change.** If no name given, `openspec list --json` + AskUserQuestion (active changes only, with schema). Do NOT guess or auto-select.

2. **Check artifact completion.** `openspec status --change "<name>" --json` → if any artifact isn't `done`, warn (list incomplete) + AskUserQuestion to confirm before proceeding.

3. **Check task completion.** Read the tasks file; count `- [ ]` vs `- [x]`. If incomplete tasks exist, warn + AskUserQuestion to confirm. No tasks file → proceed.

3.5. **Definition-of-Done gate (nearyou-id — `docs/11-Engineering-Standards.md` §5).** On the change's PR, verify:
   - **UI-affecting** → manual verification evidence (screenshots / artifact path from `verify-loop`, per `/opsx:apply` step 7.5) is in the PR body. A UI change with no evidence and no explicit "Verification: N/A" line is NOT done — tests green alone doesn't clear this.
   - **Runtime-impacting backend** → pre-archive staging smoke ran (or Section 6 is explicitly N/A).
   - **Gates** → flavor-qualified mobile test tasks ran when mobile was touched (not just the backend gate).

   If anything is missing: warn, then AskUserQuestion — (a) run the missing gate now (recommended), (b) proceed with an explicit waiver recorded in the PR body, (c) cancel. Never proceed silently.

4. **Assess delta-spec sync.** Check `openspec/changes/<name>/specs/`. If delta specs exist, compare each against `openspec/specs/<capability>/spec.md`, determine the changes (adds/mods/removals/renames), and show a combined summary before prompting (sync now (recommended) / archive without syncing / cancel). If the user chooses sync, use the Task tool (subagent_type `general-purpose`, prompt: invoke `openspec-sync-specs` for `<name>` with the analyzed delta summary). Proceed to archive regardless.

5. **Block archive if any resulting spec would carry a TBD Purpose placeholder.** The historical `/opsx:archive` default produced `## Purpose\nTBD - created by archiving change <name>…` and the handoff almost always forgot to fill it (a 2026-05-07 audit found 26/41 specs still carrying it). This step refuses to archive until every affected Purpose is grounded — it is **mandatory, not advisory**: the Purpose is the first thing agents/humans read when grepping `openspec/specs/`; a TBD placeholder makes them skip the spec or treat the stub as authoritative.

   For each `openspec/changes/<name>/specs/<capability>/spec.md`: determine the post-archive `openspec/specs/<capability>/spec.md` (the file the world reads going forward), and check it for the literal substring `TBD - created by archiving`. If found, do NOT proceed silently. Offer all three via AskUserQuestion:
   - **(a) Fill the Purpose now (recommended).** From `proposal.md` § Why + § What Changes, synthesize a 2–4 sentence Purpose; replace the TBD line in the spec.md (or delta spec.md if sync hasn't run). Re-run `openspec validate <name> --strict` + re-run this step.
   - **(b) Defer with an explicit `follow-up` issue.** Only when filling meaningfully needs inputs you lack: `gh issue create --label follow-up` (+ area label) capturing the capability + missing inputs, then proceed. Real tracked debt, not a free pass — don't use for laziness.
   - **(c) Cancel archive.** Surface the affected capability list and let the user decide.

   Check command (halts archive on any output; empty = proceed):
   ```bash
   grep -rn "TBD - created by archiving" openspec/specs/ openspec/changes/<name>/specs/ 2>/dev/null
   ```

6. **Perform the archive.** `mkdir -p openspec/changes/archive`; target `YYYY-MM-DD-<change-name>` (current date). If the target exists, fail (suggest a different date). Else `mv openspec/changes/<name> openspec/changes/archive/YYYY-MM-DD-<name>`.

7. **Update PR body to merge-ready state (one-PR-per-change).** The archive commit lands on the SAME PR `/next-change` opened (per `openspec/project.md` § Change Delivery Workflow + § "PR title and body MUST stay current at every phase boundary" — skipping this leaves the description in proposal shape and misleads reviewers at squash-merge; precedent: an earlier `/opsx:archive` on PR #37 skipped it and the user had to request the refresh).
   ```bash
   gh pr list --head "<change-name>" --state open --json number --jq '.[0].number'
   gh pr edit <pr-number> --body "$(cat <<'EOF'
   <merge-ready body — see openspec/project.md for the prescription>
   EOF
   )"
   ```
   Merge-ready body: lead with **Status: ✅ Implementation + archive complete. Merge-ready.**, drop in-progress framing, include final test counts + capability deltas (ADDED/MODIFIED from `openspec archive` output), list post-merge ticks (e.g. staging smoke) explicitly, and cite the one-PR-per-change convention so the reviewer understands the 10+ commits squash to ONE. Final retitle only if the dominant prefix changed (usually `feat(<area>): <name>` still fits).

8. **Display summary** — change name, schema, archive location, sync status, PR-body-refresh confirmation, any warnings.

**Output On Success**
```
## Archive Complete
**Change:** <change-name>
**Schema:** <schema-name>
**Archived to:** openspec/changes/archive/YYYY-MM-DD-<name>/
**Specs:** ✓ Synced to main specs (or "No delta specs" / "Sync skipped")
```

**Guardrails**
- Always prompt for change selection if not provided; never block archive on warnings (inform + confirm).
- Use the artifact graph (`openspec status --json`) for completion checking.
- `.openspec.yaml` moves with the directory; preserve it.
- If delta specs exist, always run the sync assessment + show the combined summary before prompting.

## Branching (nearyou-id — one PR per change lifecycle)

Push the archive commit to the **existing change branch** (the one `/next-change` opened, branch name = change name) — the LAST commit before squash-merge. Do NOT create a separate `openspec/archive-<name>` branch or a separate archive PR — that's the OLD 3-PR shape (V5–V11, e.g. PRs [#34](https://github.com/aditrioka/nearyou-id/pull/34)/[#35](https://github.com/aditrioka/nearyou-id/pull/35)); the one-PR convention started with PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) and was codified in PR [#38](https://github.com/aditrioka/nearyou-id/pull/38). Commit shape: `chore(openspec): archive <change-name>` (or `docs(openspec):`), body summarizing capabilities ADDED/MODIFIED/REMOVED. After it lands, the next step is the user squash-merging the unified PR — see `openspec/project.md` § Change Delivery Workflow → Archive timing for the gating sequence (CI green → archive commit → CI green → squash-merge → staging deploy green).

**qodo on the archive commit.** Qodo dashboard is Manual mode (see `openspec-apply-change` § Branching) — pushing the archive commit to the non-draft PR does NOT auto-trigger qodo; no `/review`, no polling, no gating here. (Qodo's only OpenSpec-lifecycle invocation is the `/review` comment from `/opsx:apply` step 8 against the implementation diff, which already ran by archive time.) Squash-merge proceeds when CI is green.

## Safety

The `mv` is reversible via git. Push the archive commit to the existing branch only — never `main`, never a new archive PR, never `--no-verify`. The TBD-Purpose grep (step 5) is a hard halt, not advisory.
