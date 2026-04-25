---
name: openspec-archive-change
description: Archive a completed change in the experimental workflow. Use when the user wants to finalize and archive a change after implementation is complete.
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.0"
---

Archive a completed change in the experimental workflow.

**Input**: Optionally specify a change name. If omitted, check if it can be inferred from conversation context. If vague or ambiguous you MUST prompt for available changes.

**Steps**

1. **If no change name provided, prompt for selection**

   Run `openspec list --json` to get available changes. Use the **AskUserQuestion tool** to let the user select.

   Show only active changes (not already archived).
   Include the schema used for each change if available.

   **IMPORTANT**: Do NOT guess or auto-select a change. Always let the user choose.

2. **Check artifact completion status**

   Run `openspec status --change "<name>" --json` to check artifact completion.

   Parse the JSON to understand:
   - `schemaName`: The workflow being used
   - `artifacts`: List of artifacts with their status (`done` or other)

   **If any artifacts are not `done`:**
   - Display warning listing incomplete artifacts
   - Use **AskUserQuestion tool** to confirm user wants to proceed
   - Proceed if user confirms

3. **Check task completion status**

   Read the tasks file (typically `tasks.md`) to check for incomplete tasks.

   Count tasks marked with `- [ ]` (incomplete) vs `- [x]` (complete).

   **If incomplete tasks found:**
   - Display warning showing count of incomplete tasks
   - Use **AskUserQuestion tool** to confirm user wants to proceed
   - Proceed if user confirms

   **If no tasks file exists:** Proceed without task-related warning.

4. **Assess delta spec sync state**

   Check for delta specs at `openspec/changes/<name>/specs/`. If none exist, proceed without sync prompt.

   **If delta specs exist:**
   - Compare each delta spec with its corresponding main spec at `openspec/specs/<capability>/spec.md`
   - Determine what changes would be applied (adds, modifications, removals, renames)
   - Show a combined summary before prompting

   **Prompt options:**
   - If changes needed: "Sync now (recommended)", "Archive without syncing"
   - If already synced: "Archive now", "Sync anyway", "Cancel"

   If user chooses sync, use Task tool (subagent_type: "general-purpose", prompt: "Use Skill tool to invoke openspec-sync-specs for change '<name>'. Delta spec analysis: <include the analyzed delta spec summary>"). Proceed to archive regardless of choice.

5. **Perform the archive**

   Create the archive directory if it doesn't exist:
   ```bash
   mkdir -p openspec/changes/archive
   ```

   Generate target name using current date: `YYYY-MM-DD-<change-name>`

   **Check if target already exists:**
   - If yes: Fail with error, suggest renaming existing archive or using different date
   - If no: Move the change directory to archive

   ```bash
   mv openspec/changes/<name> openspec/changes/archive/YYYY-MM-DD-<name>
   ```

6. **Update PR body to merge-ready state (one-PR-per-change convention).**

   Per `openspec/project.md` § Change Delivery Workflow + § "PR title and body MUST stay current at every phase boundary," the archive commit lands on the SAME PR that `/next-change` opened. After `openspec archive` moves the change directory and syncs specs, push the archive commit to the PR's branch and refresh the PR body to a "merge-ready" shape.

   ```bash
   gh pr list --head "<change-name>" --state open --json number --jq '.[0].number'
   gh pr edit <pr-number> --body "$(cat <<'EOF'
   <updated body — merge-ready shape, see openspec/project.md for the prescription>
   EOF
   )"
   ```

   The merge-ready body should:
   - Lead with **Status: ✅ Implementation + archive complete. Merge-ready.**
   - Drop the in-progress framing.
   - Include final test counts + capability deltas (ADDED/MODIFIED summary from `openspec archive` output).
   - List any post-merge tasks (e.g., staging smoke test) explicitly as "ticks after squash-merge."
   - Cite the `one-PR-per-change` convention so the reviewer understands why the PR has 10+ commits + the squash will produce ONE commit on `main`.

   The title may need a final retitle if the dominant prefix changed during the lifecycle (use `gh pr edit <pr> --title '<new-title>'`). Usually `feat(<area>): <name>` from the first feat-commit transition still fits.

   This step is required by `openspec/project.md` § "PR title and body MUST stay current at every phase boundary." Skipping it leaves the PR description in its in-progress / proposal shape after archive, which misleads reviewers at squash-merge time. Precedent: an earlier `/opsx:archive` run on PR #37 skipped this step and the user had to manually request the body refresh — that gap is closed by making this an explicit skill step.

7. **Display summary**

   Show archive completion summary including:
   - Change name
   - Schema that was used
   - Archive location
   - Whether specs were synced (if applicable)
   - PR body refresh confirmation (`gh pr edit <pr> --body` ran successfully)
   - Note about any warnings (incomplete artifacts/tasks)

**Output On Success**

```
## Archive Complete

**Change:** <change-name>
**Schema:** <schema-name>
**Archived to:** openspec/changes/archive/YYYY-MM-DD-<name>/
**Specs:** ✓ Synced to main specs (or "No delta specs" or "Sync skipped")

All artifacts complete. All tasks complete.
```

**Guardrails**
- Always prompt for change selection if not provided
- Use artifact graph (openspec status --json) for completion checking
- Don't block archive on warnings - just inform and confirm
- Preserve .openspec.yaml when moving to archive (it moves with the directory)
- Show clear summary of what happened
- If sync is requested, use openspec-sync-specs approach (agent-driven)
- If delta specs exist, always run the sync assessment and show the combined summary before prompting

**Branching (nearyou-id project — one PR per change lifecycle)**

Commit and push the archive commit to the **existing change branch** (the one `/next-change` opened, branch name = change name) — the same branch that already carries the proposal commits and the feat commits from `/opsx:apply`. Do NOT create a separate `openspec/archive-<change-name>` branch and do NOT open a separate archive PR. The archive commit is the LAST commit before squash-merge to `main`.

Suggested commit shape: `chore(openspec): archive <change-name>` (or `docs(openspec): archive <change-name>` to match prior precedent), with a body summarizing capability spec changes (capabilities ADDED / MODIFIED / REMOVED). The archive commit lands BEFORE the unified PR's squash-merge — see `openspec/project.md` § Change Delivery Workflow → Archive timing for the gating sequence (CI green → archive commit pushed → CI green again → squash-merge → staging deploy green).

After the archive commit lands, the next step is the user squash-merging the unified PR, NOT a separate archive PR. If you find yourself about to run `gh pr create` for an archive PR on this nearyou-id project, stop — that's the OLD 3-PR convention (V5–V11 archives, e.g., PRs [#34](https://github.com/aditrioka/nearyou-id/pull/34) / [#35](https://github.com/aditrioka/nearyou-id/pull/35)). PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) was the first change to ship under the new one-PR convention; PR [#38](https://github.com/aditrioka/nearyou-id/pull/38) codified the convention in docs.
