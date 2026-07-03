package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Render coverage for [NearYouLoader]: the loop actually puts ink on screen at a riding phase,
 * puts NO ink on screen during the fully-dark beat (`trackAlpha = 0` — the "passes behind"
 * illusion), and fills the dot whole during the dwell window. Frames render straight into an
 * `ImageBitmap`-backed [CanvasDrawScope] (no window, no PixelCopy — Robolectric's window capture
 * can't service Compose `captureToImage`); phases come from [LoaderMotion] itself so the pixels
 * and the math can't drift apart silently.
 *
 * `@Suppress("DEPRECATION")`: keeps the v1 `runComposeUiTest` API the sibling component tests use
 * — migrating to v2 is the tracked follow-up per docs/11 § 2.7, not drive-by churn.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NearYouLoaderTest {
    private companion object {
        const val TAG = "nearYouLoader"
        const val SIZE_PX = 184 // 4px per viewport unit
        val BRAND_BLUE = Color(0xFF1E4FD6)
    }

    private val motion = LoaderMotion(50f)

    private fun renderFrame(progress: Float): PixelMap {
        val bitmap = ImageBitmap(SIZE_PX, SIZE_PX)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(SIZE_PX.toFloat(), SIZE_PX.toFloat()),
        ) {
            drawNearYouLoader(
                motion = motion,
                geometry = LoaderGeometry(),
                color = BRAND_BLUE,
                trackAlpha = 0f,
                lapMillis = 2000,
                progress = progress,
            )
        }
        return bitmap.toPixelMap()
    }

    private fun PixelMap.visiblePixelCount(): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (this[x, y].alpha > 0.1f) count++
            }
        }
        return count
    }

    @Test
    fun animatedLoaderComposesAndExposesTheTag() {
        runComposeUiTest {
            setContent {
                NearYouTheme { NearYouLoader(modifier = Modifier.size(92.dp).testTag(TAG)) }
            }
            onNodeWithTag(TAG).assertExists()
        }
    }

    @Test
    fun ridingPhaseDrawsTheComet() {
        val visible = renderFrame(progress = 0.02f).visiblePixelCount()
        assertTrue(visible > 100, "expected comet ink at the launch phase, got $visible pixels")
    }

    @Test
    fun darkBeatDrawsNothing() {
        val darkTail = (ARC1_LENGTH + (motion.arc2Start - motion.cometLength)) / 2f
        val visible = renderFrame(progress = motion.timeAtTail(darkTail)).visiblePixelCount()
        assertTrue(visible == 0, "dark beat must draw nothing (trackAlpha 0), got $visible pixels")
    }

    @Test
    fun dotDwellFillsTheDotWhole() {
        val pixels = renderFrame(progress = (motion.dotOnProgress + motion.dotOffProgress) / 2f)
        // Dot center in bitmap pixels: viewport (51.5, 73.7) inside the 31..77 crop, 4x scale.
        val dotX = ((51.5f - 31f) / 46f * SIZE_PX).toInt()
        val dotY = ((73.7f - 31f) / 46f * SIZE_PX).toInt()
        assertTrue(
            pixels[dotX, dotY].alpha > 0.5f,
            "dot center must be filled during the dwell, alpha=${pixels[dotX, dotY].alpha}",
        )
    }
}
