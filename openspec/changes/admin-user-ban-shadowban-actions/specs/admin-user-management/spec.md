## MODIFIED Requirements

### Requirement: The profile page surfaces the action controls and the destructive-quota chip

The page SHALL render the moderation action controls — the existing suspend and unban controls (posting to `/admin/users/{id}/suspend` and `/admin/users/{id}/unban`, owned by `admin-user-moderation`), the warning control (posting to `/admin/users/{id}/warn`), and the NEW ban / shadow-ban / un-shadow-ban controls (posting to `/admin/users/{id}/ban`, `/admin/users/{id}/shadow-ban`, and `/admin/users/{id}/shadow-unban`, owned by `admin-user-moderation`) — each carrying the session CSRF token as a hidden field. The destructive controls (suspend, warn, ban, shadow-ban) SHALL carry an `hx-confirm` confirmation affordance. The ban / shadow-ban / un-shadow-ban controls SHALL reflect the target's current state: the **ban** control is offered when the target is not already permanently banned, the **shadow-ban** control is offered when `is_shadow_banned = FALSE`, and the **un-shadow-ban** control is offered when `is_shadow_banned = TRUE` (the shadow-ban and un-shadow-ban controls are mutually exclusive for a given state). The ban control SHALL be presented only to `owner` / `admin` sessions (the role tier that may permanently ban); the shadow-ban / un-shadow-ban controls SHALL be presented to all write roles. The page SHALL ALSO render the acting admin's live **destructive-action quota chip** showing the current count against the cap (e.g. "3/20 this hour"), sourced from the `admin-destructive-action-rate-limit` count for the acting admin.

#### Scenario: The action controls and quota chip render

- **GIVEN** an authenticated `owner`/`admin` session AND a target user with `is_banned = FALSE`, `is_shadow_banned = FALSE`
- **WHEN** the profile page is served
- **THEN** the rendered body SHALL contain a suspend control posting to `/admin/users/<id>/suspend`, an unban control posting to `/admin/users/<id>/unban`, a warning control posting to `/admin/users/<id>/warn`, a ban control posting to `/admin/users/<id>/ban`, and a shadow-ban control posting to `/admin/users/<id>/shadow-ban`, each with a `_csrf` hidden field
- **AND** the destructive controls (suspend, warn, ban, shadow-ban) SHALL each carry an `hx-confirm` affordance
- **AND** the rendered body SHALL display a destructive-action quota indicator showing the acting admin's current destructive-action count against the cap of 20

#### Scenario: A shadow-banned target shows the un-shadow-ban control instead of shadow-ban

- **GIVEN** an authenticated write-role session AND a target user with `is_shadow_banned = TRUE`
- **WHEN** the profile page is served
- **THEN** the rendered body SHALL contain an un-shadow-ban control posting to `/admin/users/<id>/shadow-unban` (with a `_csrf` hidden field) AND SHALL NOT offer a shadow-ban control for that target

#### Scenario: A moderator session is not offered the permanent-ban control

- **GIVEN** an authenticated `moderator` session AND a target user with `is_banned = FALSE`
- **WHEN** the profile page is served
- **THEN** the rendered body SHALL NOT contain a ban control posting to `/admin/users/<id>/ban` (permanent ban is owner/admin only) AND SHALL still contain the shadow-ban control
