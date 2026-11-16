## MODIFIED Requirements

### Requirement: Backend-only scope (mobile UI and anomaly-score effect deferred)

This capability SHALL expose only the two backend endpoints (`PATCH /api/v1/user/username` + `GET /api/v1/username/check`). The mobile Settings UI (Premium entry, paywall, live probe, cooldown countdown, downgrade banner) is OUT OF SCOPE here and is delivered by the separate `mobile-premium-username` capability (the proactive countdown and the downgrade banner read the self-only `usernameLastChangedAt` that `user-profile-read` exposes). The **numeric anomaly-score increment** on repeated flagged attempts remains explicitly OUT OF SCOPE and tracked as a separate follow-on change. The admin username-change oversight **surface** (`username_history` viewer, flagged-candidate accept/reject, manual handle release) is delivered by the separate `admin-premium-username-oversight` capability — no longer deferred; this capability participates in that flow only by consulting and consuming `username_flag_overrides` approvals inside its moderation gate (see the moderation requirement). This change SHALL NOT add an `anomaly_score` column or any anomaly-scoring path.

#### Scenario: No mobile or admin surface is added by this capability
- **WHEN** this capability is implemented
- **THEN** no `:mobile:app` screen and no `/admin/*` route SHALL be added by `premium-username-customization` itself
- **AND** the mobile Premium-username UI SHALL be delivered by the separate `mobile-premium-username` capability
- **AND** the admin username-change oversight surface SHALL be delivered by the `admin-premium-username-oversight` capability (the gate's consult/consume of approvals is the only override-related behavior in this capability)

#### Scenario: Anomaly-score effect is deferred to the anomaly-detection capability
- **WHEN** a user accumulates more than 3 moderation-flagged username attempts in 24 hours
- **THEN** this change SHALL NOT raise a numeric anomaly score and SHALL NOT introduce an `anomaly_score` substrate
- **AND** the numeric-score effect described in `docs/06` SHALL be implemented by the future anomaly-detection capability (docs/08 Phase 4 #17), for which this requirement is the tracking marker
