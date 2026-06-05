---
name: "Babysit PRs"
description: Triage open nearyou-id PRs — surface what needs your attention, handle safe bookkeeping autonomously, never auto-merge. Designed to run under /loop.
category: Workflow
tags: [pr, ci, loop, bookkeeping]
argument-hint: "[PR number | empty = all open PRs]"
---

Babysit the open pull requests on `aditrioka/nearyou-id`. Built to run unattended under `/loop` (e.g. `/loop 15m /babysit-prs`), so be **quiet when nothing needs attention** and **never take an irreversible action without surfacing it first**.

**Scope:** `$ARGUMENTS` — a PR number triages just that PR; empty triages all open PRs.

## Guardrails (hard rules)

- **Never `gh pr merge`.** Surface ready-to-merge PRs for the human; do not auto-merge.
- **Never force-push to resolve a conflict** without surfacing the conflict and the plan first.
- **Active gh account for this repo must be `aditrioka`** (not `adi-at-buku`) or `gh` 403s with "must be a collaborator". Check with `gh auth status` if a call fails.
- **Mergeable precheck BEFORE waiting on any CI.** A `CONFLICTING` branch never runs CI lanes — waiting on it idles forever. Check mergeable state first.
- Never `--no-verify` / `--no-gpg-sign`. Never push to `main`.

## Steps

1. **List in scope:**
   ```bash
   gh pr list --repo aditrioka/nearyou-id --state open \
     --json number,title,headRefName,isDraft,updatedAt,author
   ```

2. **Per PR, gather state in one shot:**
   ```bash
   gh pr view <n> --repo aditrioka/nearyou-id \
     --json mergeable,mergeStateStatus,statusCheckRollup,reviewDecision,isDraft,title,headRefName
   ```

3. **Triage each PR into a bucket and act:**

   | Bucket | Signal | Action |
   |---|---|---|
   | **Conflicting** | `mergeable = CONFLICTING` | Surface for rebase. Do NOT auto-resolve/force-push. If the conflict is trivial and you can see the resolution, propose it — but wait for approval. |
   | **CI red** | `statusCheckRollup` has failures | `gh run view <id> --log-failed` and diagnose. First rule out the two known false-failures (below). If it's a real code/test failure, summarize root cause + a proposed fix; don't push code unattended unless the fix is trivial and safe. |
   | **CI skipped/empty after a rebase** | heavy lanes show no run; branch was force-pushed | Force-push orphans `github.event.before` → path filter sees "bad object" → empty diff → code lanes skip. Fix with a tiny fast-forward re-poke commit (an empty/whitespace commit on the branch), then re-check. |
   | **CI pending** | checks `IN_PROGRESS` / `QUEUED` | Note it; re-check next cycle. Don't block. If a prior docs-only tick may have cancelled the code run (`cancel-in-progress`), flag it. |
   | **Ready to merge** | `mergeable = MERGEABLE` + CI green + (`reviewDecision = APPROVED` or solo-acceptable) | **Surface as ready-to-merge.** Do not merge. |
   | **Needs `/review`** | OpenSpec impl PR, qodo not yet run on the impl diff | Note that `/opsx:apply` step 8 posts `/review`; don't auto-post unless asked. |
   | **Draft / in-progress** | `isDraft = true` | Skip unless `$ARGUMENTS` named it explicitly. |

4. **Report concisely.** One scannable table: `PR | branch | mergeable | CI | next action`. **If nothing needs the human** (all green/pending, no conflicts, no reds), say so in a single line — don't restate every PR. Put anything that needs a decision (conflicts, real CI failures, ready-to-merge) at the top, bolded.

Cross-reference: to actually verify a fix on a red PR's branch, use the `verify-loop` skill. To merge, hand back to the human.
