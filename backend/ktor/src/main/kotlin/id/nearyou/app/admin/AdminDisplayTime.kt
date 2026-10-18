package id.nearyou.app.admin

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The one display stamp every admin page renders a timestamp with: minute
 * precision, always UTC (`2026-06-11 09:42 UTC`, the shape every admin board
 * frame shows), never a host-local interpretation. Shared so the queue, log and
 * lookup surfaces cannot drift apart again (they once mixed this shape with
 * second-precision ISO instants, which also overflowed half-width cards).
 * Display only: audit-log JSON payloads keep their ISO-8601 instants.
 */
internal val ADMIN_TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
