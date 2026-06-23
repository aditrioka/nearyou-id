package id.nearyou.app.image

import kotlinx.coroutines.awaitCancellation

/**
 * commonTest double for [ImagePicker] — drives the composer's attach flow without an OS picker. Returns a
 * pre-programmed [PickedImage] (or `null` to simulate a cancelled selection) and records the invocation
 * count so a test can assert the picker WAS invoked (Premium viewer) or was NOT invoked (Free viewer).
 *
 * With [suspendForever] = true, [pick] never returns — the composer stays in the picking phase (useful to
 * assert the "Posting" CTA gating before the upload even begins). The default [picked] is a tiny non-null
 * image so the happy path proceeds to upload.
 */
class FakeImagePicker(
    private val picked: PickedImage? = PickedImage(bytes = byteArrayOf(1, 2, 3), mime = "image/jpeg"),
    private val suspendForever: Boolean = false,
) : ImagePicker {
    var pickInvocationCount: Int = 0
        private set

    override suspend fun pick(): PickedImage? {
        pickInvocationCount++
        if (suspendForever) awaitCancellation()
        return picked
    }
}
