## MODIFIED Requirements

### Requirement: Hourly worker flips elapsed-grace private profiles to public

The system SHALL expose an internal endpoint `POST /internal/privacy-flip-worker` that, on each invocation, applies every privacy flip whose 72h grace window has elapsed for a user whose subscription reads `free`. In a single data-modifying transaction it SHALL find every `users` row matching ALL of:

- `privacy_flip_scheduled_at IS NOT NULL`;
- `privacy_flip_scheduled_at <= NOW()`;
- `deleted_at IS NULL`;
- `subscription_status = 'free'` (fail-closed: any other status, including one added later, is never flipped).

For each such row it SHALL set `private_profile_opt_in = FALSE` and clear `privacy_flip_scheduled_at = NULL`. Each of the following SHALL NOT be flipped:

- rows whose deadline is still in the future;
- rows with no scheduled flip;
- soft-deleted rows (`deleted_at IS NOT NULL`);
- rows whose `subscription_status` is anything other than `free` (`premium_active` / `premium_billing_retry`; for instance a `BILLING_ISSUE` delivered after the `EXPIRATION` that scheduled the flip).

A non-`free` row with an elapsed deadline SHALL be left untouched, so the worker never resets the privacy opt-in of a user whose stored subscription reads premium. It stays visible as an OVERDUE row on the admin privacy-flip monitor, where `POST /admin/privacy-flips/{user_id}/resolve` clears its schedule. The endpoint SHALL respond `200` with body `{"flipped_count": N}`, where `N` is the number of rows flipped in that run.

#### Scenario: An elapsed grace window is flipped to public
- **WHEN** the worker runs AND a user has `private_profile_opt_in = TRUE` AND `privacy_flip_scheduled_at` set to a past instant AND `deleted_at IS NULL` AND `subscription_status = 'free'`
- **THEN** that user's `private_profile_opt_in` becomes `FALSE` AND `privacy_flip_scheduled_at` becomes `NULL` AND the user is counted in `flipped_count`

#### Scenario: A still-in-window row is left untouched
- **WHEN** the worker runs AND a user has `private_profile_opt_in = TRUE` AND `privacy_flip_scheduled_at` set to a future instant
- **THEN** that user's `private_profile_opt_in` remains `TRUE` AND `privacy_flip_scheduled_at` is unchanged AND the user is NOT counted in `flipped_count`

#### Scenario: A soft-deleted row with an elapsed deadline is excluded
- **WHEN** the worker runs AND a user has `privacy_flip_scheduled_at` in the past AND `deleted_at IS NOT NULL`
- **THEN** that user is NOT flipped (the `deleted_at IS NULL` guard excludes it; a soft-deleted row carrying an elapsed flip is left for the admin privacy-flip monitor to surface as a stuck-row anomaly)

#### Scenario: A premium user with an elapsed deadline is excluded
- **WHEN** the worker runs AND a user has `private_profile_opt_in = TRUE` AND `privacy_flip_scheduled_at` in the past AND `deleted_at IS NULL` AND `subscription_status` is `premium_active` OR `premium_billing_retry`
- **THEN** that user's `private_profile_opt_in` remains `TRUE` AND `privacy_flip_scheduled_at` is unchanged AND the user is NOT counted in `flipped_count` AND no `admin_actions_log` row is written for that user (the row is left for the admin monitor's resolve action)
