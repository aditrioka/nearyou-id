package id.nearyou.app.infra.r2

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Cloudflare R2 implementation of [ObjectStore] via the AWS SDK for Kotlin S3 client
 * (design D1) pointed at the R2 S3 endpoint. R2 has no R2-specific JVM SDK but speaks the
 * S3 API; Cloudflare officially documents this `aws-sdk-kotlin` path, and the SDK is
 * coroutine-native (fits the Ktor backend better than the blocking Java SDK v2).
 *
 * Verified 2026-06-20 against the AWS SDK for Kotlin developer guide (Presign requests,
 * Get started with S3) and the Cloudflare R2 `aws-sdk-kotlin` example:
 * - Client: `S3Client { region = "auto"; endpointUrl = Url.parse(...); credentialsProvider = ... }`
 *   — `endpointUrl` takes `aws.smithy.kotlin.runtime.net.url.Url`; region MUST be `"auto"`
 *   for R2 even though it is ignored. (The CF example uses `S3Client.fromEnvironment { }`;
 *   we pass all config — including static credentials — explicitly, so the plain `S3Client { }`
 *   builder is used, removing any dependency on ambient AWS env vars / credential files.)
 * - `put`: `s3.putObject { bucket; key; body = ByteStream.fromBytes(bytes); contentType }`.
 *   `ByteStream` is the smithy-runtime type `aws.smithy.kotlin.runtime.content.ByteStream`
 *   (one doc page mislabeled it under `…services.s3.model`; the smithy-runtime path is the
 *   correct, current location — confirmed against the smithy-kotlin content API).
 * - `presignedGetUrl`: the `aws.sdk.kotlin.services.s3.presigners.presignGetObject`
 *   extension — `s3.presignGetObject(GetObjectRequest { … }, ttl)` (ttl is a
 *   `kotlin.time.Duration`) returns an `HttpRequest`; `.url.toString()` is the signed URL.
 * - `delete`: `s3.deleteObject { bucket; key }`.
 *
 * The client owns Netty/HTTP-engine resources; it is built once per instance and shared.
 * Logs are non-PII — `event=` + `status=` only (never the key, bytes, or signed URL).
 */
class R2ObjectStore(
    private val config: R2Config,
) : ObjectStore {
    private val log = LoggerFactory.getLogger(R2ObjectStore::class.java)

    private val client: S3Client by lazy {
        S3Client {
            region = "auto"
            endpointUrl = Url.parse("https://${config.accountId}.r2.cloudflarestorage.com")
            // Cloudflare's official R2 aws-sdk-kotlin example uses this exact
            // `StaticCredentialsProvider(Credentials(...))` constructor form (verified
            // 2026-06-20). A `StaticCredentialsProvider { accessKeyId = …; secretAccessKey = … }`
            // builder-block overload also exists, but the explicit-Credentials form is the
            // one the CF docs publish, so it's used here to track the documented path.
            credentialsProvider =
                StaticCredentialsProvider(
                    Credentials(
                        accessKeyId = config.accessKeyId,
                        secretAccessKey = config.secretAccessKey,
                    ),
                )
        }
    }

    override fun isConfigured(): Boolean = true

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        client.putObject(
            PutObjectRequest {
                this.bucket = config.bucket
                this.key = key
                this.body = ByteStream.fromBytes(bytes)
                this.contentType = contentType
            },
        )
        log.info("event=r2_put status=ok bytes={}", bytes.size)
    }

    override suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): String {
        val request =
            GetObjectRequest {
                this.bucket = config.bucket
                this.key = key
            }
        val presigned = client.presignGetObject(request, ttl)
        log.info("event=r2_presign status=ok ttl_ms={}", ttl.inWholeMilliseconds)
        return presigned.url.toString()
    }

    override suspend fun delete(key: String) {
        client.deleteObject(
            DeleteObjectRequest {
                this.bucket = config.bucket
                this.key = key
            },
        )
        log.info("event=r2_delete status=ok")
    }

    /**
     * Releases the underlying HTTP-engine resources. `S3Client` implements
     * `java.io.Closeable` with a synchronous `close()` (verified 2026-06-20), so the
     * shutdown hook can call this directly. Safe at JVM shutdown.
     */
    fun close() {
        client.close()
    }
}
