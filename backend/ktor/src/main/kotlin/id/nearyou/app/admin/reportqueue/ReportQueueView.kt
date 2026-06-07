package id.nearyou.app.admin.reportqueue

import java.time.format.DateTimeFormatter

/**
 * Shared row → template-model shaping for the report-queue table, used by BOTH
 * the read-only `GET /admin/reports` viewer (`adminReportQueue`) and the
 * post-resolution table re-render (`adminReportResolution`) so the two surfaces
 * render rows identically — including the in-row resolution controls.
 *
 * Pebble autoescapes every value on output, so no value is escaped here. NULL
 * columns become an em-dash placeholder; the resolved offending-user id (deep-
 * link) + the representative queue-row id stay null when absent so the template
 * degrades gracefully (no link / no queue form).
 */
internal fun ReportQueueRow.toViewMap(): Map<String, Any?> =
    mapOf(
        // The report id + the representative moderation_queue row id back the two
        // in-row resolution forms (`/admin/reports/{id}/resolve` +
        // `/admin/moderation-queue/{queueId}/resolve`). queueId is null when the
        // target has no queue row → the template renders no queue form.
        "id" to id.toString(),
        "queueId" to queueId?.toString(),
        "createdAt" to REPORT_CREATED_AT_FORMAT.format(createdAt),
        "targetType" to targetType,
        "targetId" to targetId.toString(),
        "reasonCategory" to reasonCategory,
        "reasonNote" to (reasonNote ?: REPORT_EM_DASH),
        "status" to status,
        "reporter" to (reporterUsername ?: reporterId.toString()),
        "hasQueue" to (queueTrigger != null),
        "queueTrigger" to (queueTrigger ?: REPORT_EM_DASH),
        "queuePriority" to (queuePriority?.toString() ?: REPORT_EM_DASH),
        "queueStatus" to (queueStatus ?: REPORT_EM_DASH),
        // null → template renders the bare target_id with no action link.
        "actionUserId" to actionUserId?.toString(),
    )

private const val REPORT_EM_DASH = "—"
private val REPORT_CREATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
