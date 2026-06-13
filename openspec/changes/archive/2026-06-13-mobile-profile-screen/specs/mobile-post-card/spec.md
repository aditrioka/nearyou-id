## ADDED Requirements

### Requirement: Whole-card tap opens the detail; the identity header opens the author's profile

The card SHALL invoke a hoisted `onOpen` callback when tapped anywhere on the card OUTSIDE the identity header and the two action-row affordances. The **identity header** (the letter avatar + display name + @handle region) SHALL be a separate tap target invoking a hoisted **`onOpenProfile: () -> Unit`** callback (parameterless at the card boundary — the card holds NO author UUID; the host binds the target user id by closure, per `mobile-nearby-timeline` / `mobile-global-timeline`). Activating the identity header MUST NOT also fire the whole-card `onOpen`. The action-row affordances (§ "Action row renders interactive reply and like affordances per mockup frame 1") remain the only other interactive sub-controls. The card itself SHALL NOT hold navigation references and SHALL NOT carry or render the author UUID; navigation wiring (resolving the author id and building `ProfileRoute`) stays with the host screens per their specs. This supersedes the prior "identity is not separately tappable (no profile screen exists yet — issue [#196](https://github.com/aditrioka/nearyou-id/issues/196))" posture now that the profile screen ships (`mobile-profile`).

#### Scenario: Tapping the identity header fires onOpenProfile, not the whole-card open

- **GIVEN** a rendered card with recording `onOpen` / `onOpenProfile` callbacks
- **WHEN** the test taps on the avatar/display-name/handle identity region
- **THEN** `onOpenProfile` fires exactly once AND `onOpen` does NOT fire

#### Scenario: Tapping the card body outside identity and actions fires onOpen

- **GIVEN** a rendered card with recording `onOpen` / `onOpenProfile` / `onToggleLike` / `onReplyShortcut` callbacks
- **WHEN** the test taps the card content region (outside the identity header and the action row)
- **THEN** `onOpen` fires exactly once AND `onOpenProfile` / `onToggleLike` / `onReplyShortcut` do NOT fire

#### Scenario: The card carries and renders no author UUID

- **WHEN** inspecting the card model/API and the rendered tree for a post authored by `author_user_id = "11111111-1111-1111-1111-111111111111"`
- **THEN** the card model accepts no author-UUID field, no UI node contains the UUID, AND `onOpenProfile` is a parameterless callback (the host supplies the id)

## REMOVED Requirements

### Requirement: Whole-card tap opens the detail and identity is not separately tappable

**Reason**: Un-deferred — the profile screen now exists (`mobile-profile`), so the author identity becomes a tap target to the author's profile; the "identity is not separately tappable (no profile screen exists yet — issue #196)" rationale no longer holds.
**Migration**: Replaced by § "Whole-card tap opens the detail; the identity header opens the author's profile" (ADDED above) — the same `onOpen` whole-card contract and the same no-author-UUID / no-navigation-reference card discipline, restated with the identity header now invoking a hoisted parameterless `onOpenProfile`.
