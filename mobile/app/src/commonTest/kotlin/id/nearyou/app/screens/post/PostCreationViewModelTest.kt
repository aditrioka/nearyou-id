package id.nearyou.app.screens.post

import id.nearyou.app.image.FakeImagePicker
import id.nearyou.app.image.FakeImageUploadRepository
import id.nearyou.app.image.ImageUploadOutcome
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * commonTest coverage of [PostCreationViewModel] (task 7.1) — the composer's gating + two-step submit +
 * outcome→state logic, drivable without a device/emulator via fakes for every seam:
 *  - the Premium gate (Premium tap opens the picker; Free tap routes to the paywall and does NOT open the
 *    picker; a self-read failure degrades to Free);
 *  - the upload lifecycle (a Success → Attached preview; a cancelled pick → no upload; a failure → Failed;
 *    remove clears the attachment);
 *  - the create submit attaches the uploaded id ONLY when an image is attached (a text-only post / a failed
 *    upload carry no id);
 *  - the "Posting" CTA gating while an upload is in flight (asserted via the pure projection the screen uses).
 *
 * A `StandardTestDispatcher` is set as Dispatchers.Main so `viewModelScope` (Main.immediate) runs on the
 * test scheduler — `advanceUntilIdle()` then drains the on-entry resolution + the launched pick/upload/submit
 * jobs deterministically. The [FakeImageUploadRepository] runs the upload on the test scheduler (no real HTTP
 * that would resume off-scheduler and race the assertions).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostCreationViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        createFlow: FakeCreatePostFlow = FakeCreatePostFlow(),
        imagePicker: FakeImagePicker = FakeImagePicker(),
        uploader: FakeImageUploadRepository = FakeImageUploadRepository(),
        profileOutcome: ProfileOutcome = profileOutcome(isPremium = true),
        selfUserId: String? = "self-id",
    ): PostCreationViewModel =
        PostCreationViewModel(
            flow = createFlow,
            imagePicker = imagePicker,
            imageUploader = uploader,
            profileFlow = FakeProfileFlow(profileOutcome = profileOutcome),
            selfUserIdProvider = FakeSelfUserIdProvider(selfUserId),
        )

    private fun profileOutcome(isPremium: Boolean): ProfileOutcome =
        ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(userId = "self-id", isSelf = true).copy(isPremium = isPremium))

    // ---- The Premium gate ----

    @Test
    fun `premium viewer attach opens the picker`() =
        runTest {
            val picker = FakeImagePicker()
            val vm = viewModel(imagePicker = picker)
            advanceUntilIdle() // resolve isPremium = true
            assertEquals(true, vm.isPremium.value)

            vm.onAttachClick()
            advanceUntilIdle()
            assertEquals(1, picker.pickInvocationCount, "a Premium attach invokes the OS picker")
            assertFalse(vm.routeToPaywall.value, "a Premium attach does NOT route to the paywall")
        }

    @Test
    fun `free viewer attach routes to the paywall and does NOT open the picker`() =
        runTest {
            val picker = FakeImagePicker()
            val vm = viewModel(imagePicker = picker, profileOutcome = profileOutcome(isPremium = false))
            advanceUntilIdle() // resolve isPremium = false
            assertEquals(false, vm.isPremium.value)

            vm.onAttachClick()
            advanceUntilIdle()
            assertEquals(0, picker.pickInvocationCount, "a Free attach must NOT invoke the picker")
            assertTrue(vm.routeToPaywall.value, "a Free attach raises the paywall one-shot")

            vm.onPaywallRouted()
            assertFalse(vm.routeToPaywall.value, "the one-shot clears after routing")
        }

    @Test
    fun `a self-read failure degrades to Free - no picker and upsell instead`() =
        runTest {
            val picker = FakeImagePicker()
            val vm = viewModel(imagePicker = picker, profileOutcome = ProfileOutcome.NetworkError)
            advanceUntilIdle()
            assertEquals(false, vm.isPremium.value, "a failed self-read degrades to Free")
            vm.onAttachClick()
            advanceUntilIdle()
            assertEquals(0, picker.pickInvocationCount)
            assertTrue(vm.routeToPaywall.value)
        }

    // ---- The two-step upload lifecycle ----

    @Test
    fun `a successful upload yields an Attached preview carrying the image id and delivery url`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onAttachClick()
            advanceUntilIdle()
            val attached = assertIs<ImageAttachUiState.Attached>(vm.imageAttach.value)
            assertEquals("img-abc", attached.imageId)
            assertEquals("https://img/abc/public", attached.deliveryUrl)
        }

    @Test
    fun `a cancelled selection attempts no upload and leaves the attachment None`() =
        runTest {
            val uploader = FakeImageUploadRepository()
            // picked = null simulates the user dismissing the OS picker.
            val vm = viewModel(imagePicker = FakeImagePicker(picked = null), uploader = uploader)
            advanceUntilIdle()
            vm.onAttachClick()
            advanceUntilIdle()
            assertEquals(ImageAttachUiState.None, vm.imageAttach.value, "a cancelled pick leaves None")
            assertEquals(0, uploader.uploadInvocationCount, "a cancelled pick attempts no upload")
        }

    @Test
    fun `a moderation-rejected upload yields a Failed state`() =
        runTest {
            val vm = viewModel(uploader = FakeImageUploadRepository(outcome = ImageUploadOutcome.ModerationRejected))
            advanceUntilIdle()
            vm.onAttachClick()
            advanceUntilIdle()
            val failed = assertIs<ImageAttachUiState.Failed>(vm.imageAttach.value)
            assertEquals(ImageUploadError.MODERATION_REJECTED, failed.error)
        }

    @Test
    fun `remove clears an attached image so the next post is text-only`() =
        runTest {
            val createFlow = FakeCreatePostFlow()
            val vm = viewModel(createFlow = createFlow)
            advanceUntilIdle()
            vm.onContentChange("halo")
            vm.onAttachClick()
            advanceUntilIdle()
            assertIs<ImageAttachUiState.Attached>(vm.imageAttach.value)

            vm.onRemoveImage()
            assertEquals(ImageAttachUiState.None, vm.imageAttach.value)

            vm.onSubmit()
            advanceUntilIdle()
            assertNull(createFlow.lastSubmittedImageId, "after remove, the create carries no image id (text-only)")
        }

    // ---- The two-step submit: image_id present ONLY when attached ----

    @Test
    fun `posting with an attached image threads the uploaded id into the create request`() =
        runTest {
            val createFlow = FakeCreatePostFlow()
            val vm = viewModel(createFlow = createFlow)
            advanceUntilIdle()
            vm.onContentChange("halo")
            vm.onAttachClick()
            advanceUntilIdle()

            vm.onSubmit()
            advanceUntilIdle()
            assertEquals("img-abc", createFlow.lastSubmittedImageId, "the create body carries the uploaded image id")
            assertEquals("halo", createFlow.lastSubmittedContent)
        }

    @Test
    fun `posting a text-only post carries no image id`() =
        runTest {
            val createFlow = FakeCreatePostFlow()
            val vm = viewModel(createFlow = createFlow)
            advanceUntilIdle()
            vm.onContentChange("halo")

            vm.onSubmit()
            advanceUntilIdle()
            assertNull(createFlow.lastSubmittedImageId, "a text-only post carries no image id")
        }

    @Test
    fun `a failed upload never lets a post reference the image`() =
        runTest {
            val createFlow = FakeCreatePostFlow()
            val vm =
                viewModel(
                    createFlow = createFlow,
                    uploader = FakeImageUploadRepository(outcome = ImageUploadOutcome.ModerationRejected),
                )
            advanceUntilIdle()
            vm.onContentChange("halo")
            vm.onAttachClick()
            advanceUntilIdle()
            assertIs<ImageAttachUiState.Failed>(vm.imageAttach.value)

            vm.onSubmit()
            advanceUntilIdle()
            assertNull(createFlow.lastSubmittedImageId, "a failed upload must never submit a post referencing the image")
        }

    // ---- The "Posting" CTA gating while an upload is in flight ----

    @Test
    fun `the attach state is Uploading while the upload is in flight`() =
        runTest {
            // suspendForever uploader → the upload job parks in the Uploading phase; assert the VM reflects it
            // (the screen then disables the CTA via the imageUploading projection input).
            val vm = viewModel(uploader = FakeImageUploadRepository(suspendForever = true))
            advanceUntilIdle()
            vm.onAttachClick()
            advanceUntilIdle()
            assertEquals(ImageAttachUiState.Uploading, vm.imageAttach.value, "the upload parks in the Uploading phase")
        }

    @Test
    fun `the CTA projection is disabled while an image upload is in flight`() {
        // The pure projection the screen feeds into the CTA-enable gate (no coroutines needed).
        val uploading =
            postCreationUiState(content = "halo", outcome = null, inFlight = false, imageUploading = true)
        assertFalse(uploading.submitEnabled, "the Posting CTA must be disabled while an image upload is in flight")
        val resolved =
            postCreationUiState(content = "halo", outcome = null, inFlight = false, imageUploading = false)
        assertTrue(resolved.submitEnabled, "a valid post with no in-flight upload is submittable")
    }
}
