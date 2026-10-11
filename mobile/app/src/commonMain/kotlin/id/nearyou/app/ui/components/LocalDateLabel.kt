package id.nearyou.app.ui.components

import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * ISO-8601 server instant → its calendar date ("2026-10-28") in [zone], the device's time zone by default.
 * The ONE date-label helper for every server timestamp the app renders as a date: post / reply / chat /
 * edit-history dates, the account-deletion restore-by and data-export download-by deadlines, and the
 * privacy-flip deadline. A bare `substringBefore('T')` cut reads the UTC date, which is a day early for an
 * instant between 00:00 and 07:00 WIB (#507). [zone] is the test seam. A value that is not a full instant
 * (a bare date, an empty string) falls back to its date portion, i.e. is returned unchanged.
 */
fun localDateLabel(
    instantIso: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String = Instant.parseOrNull(instantIso)?.toLocalDateTime(zone)?.date?.toString() ?: instantIso.substringBefore('T')

/**
 * ISO-8601 server instant → its local clock time in [zone] as "09.12" (24-hour `HH.mm`, the Indonesian
 * separator — the chat bubble / conversation-row time in mockup frames 2 + 5). Null when [instantIso] is not a
 * full instant.
 */
fun localTimeLabel(
    instantIso: String,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String? =
    Instant.parseOrNull(instantIso)?.toLocalDateTime(zone)?.let { t ->
        t.hour.toString().padStart(2, '0') + "." + t.minute.toString().padStart(2, '0')
    }

/**
 * How many calendar days in [zone] the instant's local date lies before [now]'s (0 = today, 1 = yesterday,
 * negative = a future date from a skewed clock). Null when [instantIso] is not a full instant. [now] and
 * [zone] are the test seams.
 */
fun localDaysAgo(
    instantIso: String,
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Int? {
    val date = Instant.parseOrNull(instantIso)?.toLocalDateTime(zone)?.date ?: return null
    return date.daysUntil(now.toLocalDateTime(zone).date)
}
