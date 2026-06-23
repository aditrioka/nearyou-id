package id.nearyou.app.image

import kotlinx.coroutines.awaitCancellation

/**
 * commonTest double for the [ImageUploader] seam — drives the composer's two-step submit with a
 * pre-programmed [ImageUploadOutcome] and records the call so a test can assert the upload WAS attempted
 * (and with which bytes/mime). Runs on the caller's dispatcher (no real HTTP), so a `StandardTestDispatcher`
 * test can `advanceUntilIdle()` past the upload deterministically (the real repository's MockEngine round-trip
 * resumes off the test scheduler, which would race an `advanceUntilIdle`).
 *
 * With [suspendForever] = true, [upload] never returns — the composer stays in the Uploading phase so the
 * "Posting" CTA-disabled-during-upload behavior can be asserted.
 */
class FakeImageUploadRepository(
    private val outcome: ImageUploadOutcome = ImageUploadOutcome.Success(imageId = "img-abc", deliveryUrl = "https://img/abc/public"),
    private val suspendForever: Boolean = false,
) : ImageUploader {
    var uploadInvocationCount: Int = 0
        private set

    var lastMime: String? = null
        private set

    override suspend fun upload(
        bytes: ByteArray,
        mime: String,
    ): ImageUploadOutcome {
        uploadInvocationCount++
        lastMime = mime
        if (suspendForever) awaitCancellation()
        return outcome
    }
}
