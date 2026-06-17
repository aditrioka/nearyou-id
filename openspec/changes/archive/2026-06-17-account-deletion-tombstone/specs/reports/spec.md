## ADDED Requirements

### Requirement: Submitted reports are retained under the account-deletion tombstone model

The `reports.reporter_id ON DELETE CASCADE` FK and its "reporter_id CASCADE removes reports on user delete" scenario describe the DB-level cascade behavior of a **raw row-delete** of a `users` row, and remain valid as such. But that path is **NOT** the account-deletion path: the `account-deletion-tombstone` hard-delete worker **tombstones** the user (an `UPDATE` setting `deleted_at` + nulling PII; it does NOT row-delete the user), so the `ON DELETE CASCADE` **never fires** and the user's submitted `reports` MUST be **RETAINED** (reporter pointing at the tombstoned user) — consistent with `docs/06` § Account Deletion ("reports submitted by the user … audit integrity") and the Data Export scope ("user has their copy pre-deletion"). This resolves the contradiction with `docs/05-Implementation.md:508` ("on user hard-delete, submitted reports cascade"), which conflated the tombstone path with a raw row-delete and is flagged for a doc-amend follow-up. The `reviewed_by` side is independent (admin FK, `ON DELETE SET NULL`) and is untouched by a user tombstone.

#### Scenario: A tombstoned reporter's reports are retained
- **WHEN** the hard-delete worker tombstones a user who had submitted reports
- **THEN** those `reports` rows still exist with `reporter_id` pointing at the now-tombstoned user (the `ON DELETE CASCADE` did not fire — the worker `UPDATE`d the user, it did not row-delete them)

#### Scenario: reviewed_by is unaffected by a user tombstone
- **WHEN** the hard-delete worker tombstones a user (not an admin) who had submitted reports that an admin reviewed
- **THEN** the `reports.reviewed_by` value (an admin FK) is unchanged by the user tombstone
