# 12 — Integration Contracts (cross-layer cohesion)

**Status:** canonical. Read alongside [`11-Engineering-Standards.md`](11-Engineering-Standards.md) for any product change that spans more than one layer. `docs/11` governs *per-layer* quality (state/nav/data contracts, backend layering, the Pattern Registry, the Definition of Done). **This file governs the seam *between* layers** — backend ↔ admin ↔ mobile (KMP/CMP) — so a single user-facing capability stays cohesive instead of drifting across several disjoint changes.

On any conflict, behavior specs (`openspec/specs/**`, `docs/02`/`03`/`05`) win over this file; this file wins over individual skill prose on the question of *scope cohesion*.

---

## 1. Why this exists

The recurring failure mode is **layer drift**: the backend keystone of a feature ships in one change, and the client surface (mobile and/or admin) that makes it *usable* follows — if at all — as separate, later changes. The seam between them is managed by tribal memory rather than a contract, so wire/DTO shapes diverge from what clients expect.

Observed precedent (all on `main`):

- **Image posts** — backend pipeline + read-path shipped in [#325](https://github.com/aditrioka/nearyou-id/pull/325); the mobile authoring UI, Coil render, and the `imageUrl` DTO surfacing didn't land until [#354](https://github.com/aditrioka/nearyou-id/pull/354), whose own body says it "completes the clientless pipeline shipped in #325." The notification single-post-read path was *still* missing `imageUrl` afterward → follow-up [#388](https://github.com/aditrioka/nearyou-id/issues/388).
- **Account deletion** — one domain fractured into four PRs ([#329](https://github.com/aditrioka/nearyou-id/pull/329) worker → [#355](https://github.com/aditrioka/nearyou-id/pull/355) admin queue → [#356](https://github.com/aditrioka/nearyou-id/pull/356) export → [#360](https://github.com/aditrioka/nearyou-id/pull/360) retention) with cross-PR FK/worker dependencies.
- **User-visible drift** — the shipped 3/day probe `409` envelope "carries no reason discriminator," so mobile shows one generic message (follow-ups [#333](https://github.com/aditrioka/nearyou-id/issues/333)/[#334](https://github.com/aditrioka/nearyou-id/issues/334)); the docs described a "live debounced probe" the backend never shipped ([#337](https://github.com/aditrioka/nearyou-id/issues/337)).

This is not "PRs can't span layers" — [#354](https://github.com/aditrioka/nearyou-id/pull/354) is a genuinely cohesive backend + mobile + shared + infra change. The problem is that nothing *required* cohesion, and `/next-change` actively *rewarded* a narrow, footprint-disjoint pick. This file supplies the missing rule.

---

## 2. The vertical-slice rule (MUST)

A **user-facing capability** is one a real end user or operator can observe — a screen, an action, a notification, an admin surface. For every user-facing capability:

> **Ship the full vertical slice in one OpenSpec change, OR declare each missing layer as an explicit deferred requirement (§3).**

The full vertical slice is, for whichever layers the capability touches:

1. **Backend** — the endpoint/worker/migration **and the wire contract** (response DTO fields, status/error envelope) that the client consumes.
2. **Client(s)** — every client that surfaces the capability:
   - **Mobile** (`:mobile:app`, CMP) — the screen/affordance + the client DTO that parses the wire shape (§4) + the per-screen `mobile-ui-foundation` pass.
   - **Admin** (`backend/ktor/.../admin`, Pebble+HTMX) — the operator surface, when the capability has a moderation/ops dimension.
3. **The consuming read-paths** — if a capability adds a field, *every* path that returns the same entity must carry it (the `imageUrl`-in-notifications gap, [#388](https://github.com/aditrioka/nearyou-id/issues/388), is the canonical miss). Enumerate the read-paths for the entity before calling the slice complete.

"Backend-only because the client is a separate lane" is **not** a sufficient reason to split. The three delivery lanes (admin / Phase 4-premium / mobile follow-ups; see [`../openspec/project.md`](../openspec/project.md) § Priority and CLAUDE.md) are *prioritization* buckets, **not** layer boundaries — a single capability may and often should span lanes. Do not force-split a capability to fit one lane.

---

## 3. The deferred-layer escape hatch (the only sanctioned split)

Splitting a capability across changes is allowed **only** when the deferred layer is captured as an explicit spec requirement in the *originating* change — never as a bare "deferred to follow-up" line in prose. Use the shape from the project memory `feedback_defer_as_explicit_requirement`:

For each deferred layer, the originating change's `specs/**` MUST add a requirement with:

1. **A positive statement** of what the deferred layer *will* do (so the follow-up has something concrete to `MODIFY` via the OpenSpec RENAMED+MODIFIED convention — a `## RENAMED` block whose `FROM` byte-matches the current header, plus a `## MODIFIED` block keyed by the new header).
2. **A negative guard scenario** asserting the current (pre-follow-up) behavior — e.g. "WHEN the mobile client requests X THEN it receives the backend field but does not yet render it" — so the gap is a *tested decision*, not an accident.
3. **A tracking follow-up issue** (`gh issue create --label follow-up` + the layer label) referenced by number in the requirement.

If you cannot write the negative-guard scenario, the layer is not actually separable — ship it in the slice.

---

## 4. Wire-contract documentation (MUST when adding/altering a response field)

Wire shapes are a contract, not an implementation detail, and they have drifted from their specs before (project memory `reference_timeline_dto_camelcase_wire`: the timeline DTOs are mixed-case on the wire while the specs show stale snake_case; client DTOs generated from the spec silently fail to parse — [#128](https://github.com/aditrioka/nearyou-id/pull/128)).

When a change adds or alters a response field:

- The **authoritative shape is the Kotlin response DTO on the wire**, not the spec's JSON example. If the spec example diverges, fix the example in the same change (or file the reconciliation per `/next-change` B.3).
- Thread the field through **every** read-path that returns the entity (§2.3) and through the **client DTO(s)** that parse it — in the same change, or as a §3 deferred requirement.
- Note casing explicitly when it is mixed (e.g. `authorUserId`/`distanceM`/`nextCursor` camelCase but `city_name`/`liked_by_viewer`/`reply_count` snake) so a client author copies the real shape, not an idealized one.

---

## 5. Where this is enforced

| Gate | Skill / step | What it checks |
|---|---|---|
| **Pick scope** | `next-change` A.2 | For a user-facing capability, prefer the complete vertical slice even if its footprint overlaps an in-flight claim (coordinate, don't slice). The footprint-disjoint heuristic applies to *independent/infra* work, not to splitting a feature across its layers. |
| **Scope declaration** | `openspec-propose` (design.md "Standards conformance" note) | `design.md` declares which layers the capability spans; any deferred layer points to its §3 requirement. |
| **Preflight** | `openspec-preflight` (counterpart-layer check) | Surfaces a missing client/admin surface or an un-threaded wire field *before* `/opsx:apply` writes code. |
| **Review** | `next-change` D / `openspec-apply-change` 8 (general lens) | Cross-layer cohesion: a user-facing capability either ships its full slice or declares deferred layers as explicit requirements (§3). An undeclared single-layer slice of a multi-layer capability is a blocking finding. |

---

## 6. Relationship to docs/11

`docs/11` § Pattern Registry stops *intra-layer* patchwork (two state-holder patterns, two nav patterns). This file stops *inter-layer* patchwork (a backend with no client, a wire field no client parses). They compose: a change conforms to the Pattern Registry **and** ships a cohesive vertical slice. Neither subsumes the other.
