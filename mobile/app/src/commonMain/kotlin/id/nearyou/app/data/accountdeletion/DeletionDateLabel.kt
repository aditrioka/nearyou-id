package id.nearyou.app.data.accountdeletion

/**
 * ISO-8601 `scheduledHardDeleteAt` → its date portion ("2026-07-17"), for the restore-by line in the
 * scheduled-deletion banner. Pure + deterministic (no wall clock, no timezone conversion) — the same
 * shape as `postDateLabel` (`ui/components/PostCard.kt`); a fuller locale-aware formatter is a later
 * polish, but this keeps the seam testable and avoids a kotlinx-datetime parse on the render path. A
 * value with no `T` (already a bare date, or empty) is returned unchanged.
 */
fun deletionDateLabel(scheduledHardDeleteAt: String): String = scheduledHardDeleteAt.substringBefore('T')
