package id.nearyou.app.infra.cloudvision

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.vision.v1.AnnotateImageRequest
import com.google.cloud.vision.v1.Feature
import com.google.cloud.vision.v1.Image
import com.google.cloud.vision.v1.ImageAnnotatorClient
import com.google.cloud.vision.v1.ImageAnnotatorSettings
import com.google.cloud.vision.v1.Likelihood
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Google Cloud Vision Safe Search implementation of [ImageModerator]. The entire
 * `com.google.cloud.vision.*` SDK surface is encapsulated here (invariant: no vendor
 * SDK import outside `:infra:*`).
 *
 * The [ImageAnnotatorClient] is heavyweight + thread-safe; it is built lazily on first
 * use (no eager connect at construction, so an environment with a malformed credential
 * still boots) and reused for the process lifetime.
 */
class GoogleCloudVisionImageModerator(
    serviceAccountJson: String,
) : ImageModerator {
    private val log = LoggerFactory.getLogger(GoogleCloudVisionImageModerator::class.java)

    private val client: ImageAnnotatorClient by lazy {
        val credentials = GoogleCredentials.fromStream(serviceAccountJson.byteInputStream())
        val settings =
            ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()
        ImageAnnotatorClient.create(settings)
    }

    override fun isConfigured(): Boolean = true

    override suspend fun safeSearch(bytes: ByteArray): SafeSearchVerdict =
        withContext(Dispatchers.IO) {
            val image = Image.newBuilder().setContent(ByteString.copyFrom(bytes)).build()
            val feature = Feature.newBuilder().setType(Feature.Type.SAFE_SEARCH_DETECTION).build()
            val request =
                AnnotateImageRequest.newBuilder().addFeatures(feature).setImage(image).build()

            val response = client.batchAnnotateImages(listOf(request)).getResponses(0)
            if (response.hasError()) {
                // Fail-closed: a Vision error must not let an un-moderated image through.
                log.warn("event=vision_safe_search_error code={} message={}", response.error.code, response.error.message)
                throw VisionModerationException("Vision Safe Search failed: ${response.error.message}")
            }
            val annotation = response.safeSearchAnnotation
            SafeSearchVerdict(
                adult = annotation.adult.toSafeSearchLikelihood(),
                violence = annotation.violence.toSafeSearchLikelihood(),
                racy = annotation.racy.toSafeSearchLikelihood(),
            )
        }

    private fun Likelihood.toSafeSearchLikelihood(): SafeSearchLikelihood =
        when (this) {
            Likelihood.VERY_UNLIKELY -> SafeSearchLikelihood.VERY_UNLIKELY
            Likelihood.UNLIKELY -> SafeSearchLikelihood.UNLIKELY
            Likelihood.POSSIBLE -> SafeSearchLikelihood.POSSIBLE
            Likelihood.LIKELY -> SafeSearchLikelihood.LIKELY
            Likelihood.VERY_LIKELY -> SafeSearchLikelihood.VERY_LIKELY
            Likelihood.UNKNOWN, Likelihood.UNRECOGNIZED -> SafeSearchLikelihood.UNKNOWN
        }
}

/** Thrown on a Vision API-level error so the caller can fail closed (reject the upload). */
class VisionModerationException(message: String) : RuntimeException(message)
