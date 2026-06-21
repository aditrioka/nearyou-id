package id.nearyou.app.account

import id.nearyou.app.infra.resend.EmailTemplate

/**
 * Renders the "data export ready" transactional email (capability `account-data-export`,
 * task 4.3). The HTML + text bodies are versioned in-repo under `/backend/email-templates/`
 * and shipped on the classpath at `email-templates/data-export-ready.{html,txt}`; this object
 * loads them once and substitutes the two placeholders.
 *
 * Copy per docs/06 § Data Export Scope Matrix Delivery ("Data export kamu siap diunduh").
 * The rendered body carries the signed download URL — callers MUST NOT log the result.
 */
object DataExportEmailTemplate {
    private const val SUBJECT = "Data export kamu siap diunduh"
    private const val HTML_RESOURCE = "/email-templates/data-export-ready.html"
    private const val TEXT_RESOURCE = "/email-templates/data-export-ready.txt"

    private val htmlBody: String by lazy { loadResource(HTML_RESOURCE) }
    private val textBody: String by lazy { loadResource(TEXT_RESOURCE) }

    /**
     * Builds the rendered [EmailTemplate] for a ready export.
     *
     * @param downloadUrl the 24h-TTL R2 signed GET URL (a bearer capability)
     * @param expiresAt   ISO-8601 deadline string after which the link stops working
     */
    fun render(
        downloadUrl: String,
        expiresAt: String,
    ): EmailTemplate =
        EmailTemplate(
            subject = SUBJECT,
            htmlBody = substitute(htmlBody, downloadUrl, expiresAt),
            textBody = substitute(textBody, downloadUrl, expiresAt),
        )

    private fun substitute(
        body: String,
        downloadUrl: String,
        expiresAt: String,
    ): String =
        body
            .replace("{{download_url}}", downloadUrl)
            .replace("{{expires_at}}", expiresAt)

    private fun loadResource(path: String): String =
        DataExportEmailTemplate::class.java.getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("data-export email template resource not found on classpath: $path")
}
