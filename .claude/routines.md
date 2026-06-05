# Routines (Claude Code web) — nearyou-id

Draft specs for cloud-hosted **routines** (the "Stop babysitting your agents" pattern: `/loop`, but running remotely on a schedule). Each one spawns a fresh Claude Code session in the cloud container with the prompt below. They are **not auto-applied** — paste each into the **Routines** tab on [claude.ai/code](https://claude.ai/code) (or the desktop app), or use the `/schedule` skill.

## Prerequisites (gate everything here)

1. **Connect this repo to Claude Code web** — log in at [claude.ai/code](https://claude.ai/code) and connect `aditrioka/nearyou-id`. Routines run in that cloud env, decoupled from your laptop.
2. **Tools must exist in the cloud env**, not just locally: `gh` authenticated as `aditrioka` (this repo 403s if the active gh account is `adi-at-buku`); the Slack MCP if you want the digest routine to post.
3. Every routine respects CLAUDE.md: **never push to `main`**, never `--no-verify`, open a feature-branch PR for any change, squash-merge stays a human action.

---

## Routine 1 — Daily docs sync  ·  time trigger, daily ~06:00 WIB

> You are a scheduled routine in the `aditrioka/nearyou-id` repo. Goal: keep generated docs + the module list in sync and surface doc drift.
>
> 1. Run `dev/scripts/sync-readme.sh --check`. If it reports drift, run `dev/scripts/sync-readme.sh --write`.
> 2. If a module was added to `settings.gradle.kts` without a matching line in `dev/module-descriptions.txt`, add a one-line description, then re-run `--write`.
> 3. Skim `docs/` for present-tense claims clearly contradicted by the last day of merges (`git log --since='1 day ago' --oneline`). **Note** stale spots; don't rewrite product intent unprompted.
> 4. If anything changed, open a PR `docs: daily docs sync (<date>)` on branch `docs/daily-sync-<date>` (never push to main; respect hooks). If nothing changed, exit quietly.

## Routine 2 — Weekly FOLLOW_UPS triage  ·  time trigger, Mondays ~07:00 WIB

> Scheduled routine in `aditrioka/nearyou-id`. Invoke the repo's `triage-follow-ups` skill end-to-end against `FOLLOW_UPS.md`: read every open entry, run the staleness checks, classify each (silently-resolved / superseded / migrate-to-canonical-doc / still-valid / in-progress), and act per disposition — delete resolved entries, migrate where canonical docs now cover it, surface promotions for `/next-change`.
>
> Open one PR `chore: triage FOLLOW_UPS.md (<date>)` for the deletions/migrations (the established shape — see the `#146` precedent), with the triage summary as the PR body. Never push to main. If no entry changed disposition, exit quietly.

## Routine 3 — Repo digest  ·  time trigger, every 6h  ·  *optional Slack*

> Scheduled routine in `aditrioka/nearyou-id`. Produce a short digest of repo state since the last run (~6h):
> - **Open PRs needing attention** — reuse the `/babysit-prs` triage logic (conflicts, red CI, ready-to-merge).
> - **New/updated issues** — `gh issue list --repo aditrioka/nearyou-id --search 'updated:>$(date -u -v-6H +%Y-%m-%dT%H:%M:%SZ)'`.
> - **CI on `main`** — `gh run list --repo aditrioka/nearyou-id --branch main --limit 5`.
>
> Keep it to ~10 lines, decisions at the top. If a Slack channel is set below, post it there; otherwise just emit the summary as the routine output. If nothing changed, emit nothing.
>
> **Slack channel:** `<#fill-in — or leave blank to skip Slack>`  *(nearyou-id is a solo project; only wire Slack if you actually want pings there.)*

---

## Notes

- **Routine vs `/loop`:** `/loop` runs in a local session tied to this laptop; a routine runs in the cloud on a cron, surviving a closed laptop. Routine 3 overlaps with `/loop 15m /babysit-prs` — run the loop while you're actively working, lean on the routine when you're away.
- **`/remote-control`** pairs nicely here: when a routine opens a PR or needs input, your phone buzzes and you can steer from anywhere.
- Manage/edit these later with the `/schedule` skill or the Routines tab.
