package id.nearyou.app.screens.post

import id.nearyou.app.image.ImageUploadOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure coverage of the [ImageUploadOutcome] → [ImageAttachUiState] projection (task 7.1: "each
 * `ImageUploadOutcome` → its UI state"). EACH of the nine outcomes maps to a distinct terminal sub-state:
 * `Success` → an [ImageAttachUiState.Attached] preview carrying the id+URL; every other outcome → an
 * [ImageAttachUiState.Failed] with its distinct [ImageUploadError] key. No HTTP / device needed.
 */
class PostCreationImageStateTest {
    @Test
    fun `Success maps to Attached carrying the image id and delivery url`() {
        val attached =
            assertIs<ImageAttachUiState.Attached>(
                ImageUploadOutcome.Success(imageId = "abc", deliveryUrl = "https://img/abc/public").toAttachState(),
            )
        assertEquals("abc", attached.imageId)
        assertEquals("https://img/abc/public", attached.deliveryUrl)
    }

    @Test
    fun `each non-success outcome maps to its distinct Failed error key`() {
        // The full table — every member has a distinct UI message key (the spec's distinct-copy requirement).
        val table: List<Pair<ImageUploadOutcome, ImageUploadError>> =
            listOf(
                ImageUploadOutcome.PremiumRequired to ImageUploadError.PREMIUM_REQUIRED,
                ImageUploadOutcome.FeatureDisabled to ImageUploadError.FEATURE_DISABLED,
                ImageUploadOutcome.QuotaExceeded to ImageUploadError.QUOTA_EXCEEDED,
                ImageUploadOutcome.Throttled to ImageUploadError.THROTTLED,
                ImageUploadOutcome.ModerationRejected to ImageUploadError.MODERATION_REJECTED,
                ImageUploadOutcome.TooLarge to ImageUploadError.TOO_LARGE,
                ImageUploadOutcome.Unavailable to ImageUploadError.UNAVAILABLE,
                ImageUploadOutcome.Network to ImageUploadError.NETWORK,
            )
        for ((outcome, expected) in table) {
            val failed = assertIs<ImageAttachUiState.Failed>(outcome.toAttachState(), "outcome $outcome must map to Failed")
            assertEquals(expected, failed.error, "outcome $outcome must map to the $expected message key")
        }
        // All eight non-success keys are distinct (no two outcomes collapse to the same message).
        assertEquals(8, table.map { it.second }.toSet().size, "each non-success outcome must have a DISTINCT message key")
    }
}
