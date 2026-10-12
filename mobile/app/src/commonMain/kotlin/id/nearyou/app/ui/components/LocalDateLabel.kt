package id.nearyou.app.ui.components

import androidx.compose.runtime.Composable
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.date_today
import id.nearyou.resources.generated.resources.date_yesterday
import id.nearyou.resources.generated.resources.weekday_short_fri
import id.nearyou.resources.generated.resources.weekday_short_mon
import id.nearyou.resources.generated.resources.weekday_short_sat
import id.nearyou.resources.generated.resources.weekday_short_sun
import id.nearyou.resources.generated.resources.weekday_short_thu
import id.nearyou.resources.generated.resources.weekday_short_tue
import id.nearyou.resources.generated.resources.weekday_short_wed
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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

/**
 * The relative DAY of [instantIso] in the device time zone: today → "Hari ini", yesterday → "Kemarin", anything
 * else (or a value that is not a full instant) → the date. The chat thread's day chip (mockup frame 5).
 */
@Composable
fun relativeDayLabel(
    instantIso: String,
    now: Instant = Clock.System.now(),
): String =
    when (localDaysAgo(instantIso, now)) {
        null -> localDateLabel(instantIso)
        in Int.MIN_VALUE..0 -> stringResource(Res.string.date_today)
        1 -> stringResource(Res.string.date_yesterday)
        else -> localDateLabel(instantIso)
    }

/**
 * The relative day-or-time of [instantIso] in the device time zone: today → the clock time ("09.12"), yesterday →
 * "Kemarin", the rest of the past week → the short weekday ("Sel"), anything older → the date. A value that is not a
 * full instant renders its date portion. The conversation row's last-message time (mockup frame 2).
 */
@Composable
fun relativeDayTimeLabel(
    instantIso: String,
    now: Instant = Clock.System.now(),
): String {
    val daysAgo = localDaysAgo(instantIso, now) ?: return localDateLabel(instantIso)
    return when {
        daysAgo <= 0 -> localTimeLabel(instantIso) ?: localDateLabel(instantIso)
        daysAgo == 1 -> stringResource(Res.string.date_yesterday)
        daysAgo < 7 -> stringResource(weekdayShortLabel(instantIso))
        else -> localDateLabel(instantIso)
    }
}

private fun weekdayShortLabel(instantIso: String): StringResource =
    when (Instant.parse(instantIso).toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek) {
        DayOfWeek.MONDAY -> Res.string.weekday_short_mon
        DayOfWeek.TUESDAY -> Res.string.weekday_short_tue
        DayOfWeek.WEDNESDAY -> Res.string.weekday_short_wed
        DayOfWeek.THURSDAY -> Res.string.weekday_short_thu
        DayOfWeek.FRIDAY -> Res.string.weekday_short_fri
        DayOfWeek.SATURDAY -> Res.string.weekday_short_sat
        DayOfWeek.SUNDAY -> Res.string.weekday_short_sun
    }
