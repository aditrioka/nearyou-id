package id.nearyou.app.data.dataexport

/**
 * ISO-8601 `downloadExpiresAt` → its date portion ("2026-07-01"), for the deadline line in the ready
 * banner. Pure + deterministic (no wall clock, no timezone conversion) — the same shape as
 * `deletionDateLabel` (`data/accountdeletion/DeletionDateLabel.kt`); a fuller locale-aware formatter is a
 * later polish, but this keeps the seam testable and avoids a kotlinx-datetime parse on the render path. A
 * value with no `T` (already a bare date, or empty) is returned unchanged.
 */
fun exportDeadlineLabel(downloadExpiresAt: String): String = downloadExpiresAt.substringBefore('T')
