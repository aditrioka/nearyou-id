# mobile-profile — delta for post-detail-tap-to-profile

## RENAMED Requirements

- FROM: `### Requirement: Edit-profile, suspension countdown, and post-detail identity tap are deferred`
- TO: `### Requirement: Edit-profile and suspension countdown are deferred`

## MODIFIED Requirements

### Requirement: Edit-profile and suspension countdown are deferred

This change SHALL NOT ship: (a) an **edit-profile** affordance (bio / display-name / username editing) — no backend write endpoint is shipped (`user-profile-read` is read-only; Premium username customization is DESIGN-status with no `PATCH /api/v1/user/username`); (b) a **suspension-countdown** on the profile — `user-profile-read` deliberately does not carry suspension state (it is surfaced at the auth boundary), so there is no backing data. Each deferral SHALL be tracked by a `follow-up` GitHub issue. The self `ProfileScreen` SHALL render no edit control and no suspension field. The former deferral (c) — the **post-detail author-identity tap** → profile — SHIPPED via `post-detail-tap-to-profile` (issue #455): the `mobile-post-detail` freshness read sources the `authorUserId`, so the `PostDetailRoute` no-author-UUID serialization discipline stands unbroken; the in-scope entries to other-user profiles are now the feed-card identity tap (per `mobile-post-card` / `mobile-nearby-timeline` / `mobile-global-timeline`) AND the post-detail header/reply identity taps (per `mobile-post-detail`).

#### Scenario: No edit control and no suspension field on the self profile

- **GIVEN** a loaded self profile (`isSelf = true`)
- **WHEN** `ProfileScreen` is rendered
- **THEN** no edit-profile control is present AND no suspension-countdown field is rendered (neither is backed by a shipped endpoint)
