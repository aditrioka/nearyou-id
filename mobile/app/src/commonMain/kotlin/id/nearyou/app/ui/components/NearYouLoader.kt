package id.nearyou.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * `NearYouLoader` — the branded indeterminate loading indicator: a "traveling light" comet that
 * rides the brand-mark strokes as one continuous loop (operator-designed route, 2026-07-02).
 *
 * Geometry provenance: every waypoint below is byte-identical to the glyph pathData in
 * `shared/resources/.../drawable/logo_brand_light.xml` (the 46x46 viewport crop, coordinates still
 * in the original 31..77 space). The mark's shape is a HARD constraint — the loader only rides
 * OVER the strokes; the gaps in the mark (the open left edge, the right tip stopping short, the
 * two gaps flanking the dot) are honored by masking, never redrawn.
 *
 * Motion contract (canonical reference: the operator-approved web demo, memory note
 * "project-logo-loader-animation-idea"):
 * - The comet follows the 1..13 annotation route: bottom -> right side -> vanishes INTO the
 *   right-middle stroke tip -> a fully-dark suspense beat (a virtual hidden segment appended
 *   between the two arcs, so the dash pattern period is `routeLength + hidden`) -> re-emerges at
 *   the left-middle stroke tip -> around the top hexagon -> down through the shared V -> left
 *   side -> dwells glowing on the dot -> accelerates back into the lap.
 * - Variable speed comes from a piecewise-linear speed profile integrated into a cumulative-time
 *   table ([LoaderMotion]); rendering inverts it per frame.
 * - The dot is not swept like a stroke: it fills whole, with a radial halo that breathes at
 *   [SHIMMER_HZ] while the comet overlaps it.
 *
 * Scales freely: everything is drawn in the 46-unit viewport and scaled to the incoming size, so
 * the same composable serves splash (large), content loading (medium), and top-bar (small) uses.
 */

private const val VIEWPORT_ORIGIN = 31f
private const val VIEWPORT_SIZE = 46f
private const val STROKE_WIDTH = 3f
private val DOT_CENTER = Offset(51.5f, 73.7f)
private const val DOT_RADIUS = 2f

/** Comet route, annotation order 1..4: bottom seam to the right-middle stroke tip. */
private val ARC1 =
    floatArrayOf(
        56.7f, 73.7f, 64.2f, 73.7f, 74.3f, 67.9f, 74.3f, 56.1f, 68.3f, 52.6f,
    )

/** Comet route, annotation order 5..13: left-middle stroke tip around and back to the seam. */
private val ARC2 =
    floatArrayOf(
        43.8f, 45.5f, 43.8f, 38.5f, 54f, 32.6f, 64.2f, 38.5f, 64.2f, 50.2f, 54f, 56.1f,
        43.8f, 50.2f, 33.7f, 56.1f, 33.7f, 67.9f, 43.8f, 73.7f, 46.3f, 73.7f, 56.7f, 73.7f,
    )

/** The glyph's four stroke polylines, exactly as in the drawable (mask + optional track). */
private val GLYPH_POLYLINES =
    arrayOf(
        floatArrayOf(43.8f, 50.2f, 54f, 56.1f, 64.2f, 50.2f, 64.2f, 38.5f, 54f, 32.6f, 43.8f, 38.5f, 43.8f, 45.5f),
        floatArrayOf(54f, 56.1f, 43.8f, 50.2f, 33.7f, 56.1f, 33.7f, 67.9f, 43.8f, 73.7f, 46.3f, 73.7f),
        floatArrayOf(64.2f, 73.7f, 74.3f, 67.9f, 74.3f, 56.1f, 68.3f, 52.6f),
        floatArrayOf(64.2f, 73.7f, 56.7f, 73.7f),
    )

private fun polylineLength(points: FloatArray): Float {
    var length = 0f
    for (i in 2 until points.size step 2) {
        val dx = points[i] - points[i - 2]
        val dy = points[i + 1] - points[i - 1]
        length += sqrt(dx * dx + dy * dy)
    }
    return length
}

private fun polylinePath(points: FloatArray): Path =
    Path().apply {
        moveTo(points[0], points[1])
        for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
    }

internal val ARC1_LENGTH = polylineLength(ARC1)
internal val ARC2_LENGTH = polylineLength(ARC2)
internal val ROUTE_LENGTH = ARC1_LENGTH + ARC2_LENGTH

/** Dot edges as arc2-local route positions — the dot sits on arc2's final horizontal segment. */
private val DOT_LOCAL_START = ARC2_LENGTH - (ARC2[ARC2.size - 2] - (DOT_CENTER.x - DOT_RADIUS))
private val DOT_LOCAL_END = ARC2_LENGTH - (ARC2[ARC2.size - 2] - (DOT_CENTER.x + DOT_RADIUS))

/** Virtual dark-beat length beyond the comet, so the vanish always fully completes. */
private const val HIDDEN_MARGIN = 5f
private const val SAMPLES = 512
private const val ENERGY_RISE = 0.03f
private const val ENERGY_FALL = 0.09f
private const val SHIMMER_HZ = 2.4f

/**
 * The comet's motion model for one [cometLength]: dash-pattern sizing over the virtual route
 * (real strokes + the hidden jump segment) and the inverted variable-speed clock.
 *
 * Speed profile intent (tail-position landmarks): fast on the opening straights, brake while
 * draining into the right tip, hold a slow fully-dark beat, snap out at the left tip, cruise the
 * top hexagon, brake again so the head parks glowing on the dot, then accelerate into the wrap.
 */
internal class LoaderMotion(cometLength: Float) {
    val cometLength = cometLength.coerceIn(2f, 100f)
    val hiddenLength = this.cometLength + HIDDEN_MARGIN
    val totalLength = ROUTE_LENGTH + hiddenLength
    val arc2Start = ARC1_LENGTH + hiddenLength
    val coreLength = max(4f, this.cometLength * 0.45f)
    val dotStart = DOT_LOCAL_START + arc2Start
    val dotEnd = DOT_LOCAL_END + arc2Start

    private val cumulative = FloatArray(SAMPLES + 1)

    /** Lap fraction at which the comet head reaches the dot / the tail clears it. */
    val dotOnProgress: Float
    val dotOffProgress: Float

    init {
        val c = this.cometLength
        val points =
            listOf(
                0f to 1.7f,
                ARC1_LENGTH - 14f to 1.5f,
                ARC1_LENGTH - 4f to 0.6f,
                ARC1_LENGTH + (hiddenLength - c) / 2f to 0.3f,
                arc2Start - c * 0.4f to 0.9f,
                arc2Start + 6f to 1.5f,
                arc2Start + 55f to 1.15f,
                dotStart - c - 10f to 0.9f,
                dotStart - c + 2f to 0.35f,
                dotEnd - c + 6f to 0.4f,
                totalLength - 5f to 1.5f,
                totalLength to 1.7f,
            ).sortedBy { it.first } // long comets can shuffle landmark order

        fun speedAt(u: Float): Float {
            for (i in 1 until points.size) {
                if (u <= points[i].first) {
                    val (u0, s0) = points[i - 1]
                    val (u1, s1) = points[i]
                    val span = u1 - u0
                    return if (span <= 0f) s1 else s0 + (s1 - s0) * ((u - u0) / span)
                }
            }
            return points.last().second
        }

        val step = totalLength / SAMPLES
        for (i in 1..SAMPLES) {
            cumulative[i] = cumulative[i - 1] + step / speedAt((i - 0.5f) * step)
        }
        val lap = cumulative[SAMPLES]
        for (i in 0..SAMPLES) cumulative[i] /= lap

        dotOnProgress = timeAtTail(dotStart - c)
        dotOffProgress = timeAtTail(dotEnd)
    }

    /** Inverts the cumulative-time table: lap fraction -> comet tail position on the route. */
    fun tailAt(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        var lo = 0
        var hi = SAMPLES
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= p) lo = mid else hi = mid
        }
        val span = cumulative[hi] - cumulative[lo]
        val f = if (span <= 0f) 0f else (p - cumulative[lo]) / span
        return (lo + f) * totalLength / SAMPLES
    }

    /** Forward lookup: comet tail position -> lap fraction. */
    fun timeAtTail(tail: Float): Float {
        val clamped = tail.coerceIn(0f, totalLength)
        val exact = clamped / totalLength * SAMPLES
        val lo = exact.toInt().coerceAtMost(SAMPLES - 1)
        return cumulative[lo] + (cumulative[lo + 1] - cumulative[lo]) * (exact - lo)
    }

    /** Dot glow envelope: quick attack as the head arrives, lingering release after the tail. */
    fun energyAt(progress: Float): Float {
        fun envelope(x: Float): Float =
            when {
                x < dotOnProgress -> 0f
                x < dotOnProgress + ENERGY_RISE -> (x - dotOnProgress) / ENERGY_RISE
                x <= dotOffProgress -> 1f
                x < dotOffProgress + ENERGY_FALL -> 1f - (x - dotOffProgress) / ENERGY_FALL
                else -> 0f
            }
        return max(envelope(progress), envelope(progress + 1f)) // window may cross the wrap
    }
}

/** Default parameters — the operator-approved presentation values (2026-07-02 dashboard). */
private const val DEFAULT_COMET_LENGTH = 50f
private const val DEFAULT_LAP_MILLIS = 2000
private const val TRAIL_ALPHA = 0.35f
private const val GLOW_ALPHA = 0.15f
private const val GLOW_WIDTH = STROKE_WIDTH * 2f

/**
 * The branded indeterminate loader. Size it with [modifier] (e.g. `Modifier.size(96.dp)`); the
 * drawing scales to fit, so one composable serves splash, content-loading, and top-bar sizes.
 *
 * @param trackAlpha 0f hides the resting glyph entirely (approved default); raise it (~0.25f) to
 *   show the mark as a dim track under the light.
 */
@Composable
fun NearYouLoader(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackAlpha: Float = 0f,
    cometLength: Float = DEFAULT_COMET_LENGTH,
    lapMillis: Int = DEFAULT_LAP_MILLIS,
) {
    val transition = rememberInfiniteTransition(label = "NearYouLoader")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(lapMillis, easing = LinearEasing)),
        label = "lap",
    )
    NearYouLoaderFrame(
        progress = progress,
        modifier = modifier.progressSemantics(),
        color = color,
        trackAlpha = trackAlpha,
        cometLength = cometLength,
        lapMillis = lapMillis,
    )
}

/**
 * One frame of the loader at a fixed [progress] (0..1 lap fraction). Public entry is
 * [NearYouLoader]; this layer exists so render tests can pin exact phases.
 */
@Composable
internal fun NearYouLoaderFrame(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackAlpha: Float = 0f,
    cometLength: Float = DEFAULT_COMET_LENGTH,
    lapMillis: Int = DEFAULT_LAP_MILLIS,
) {
    val motion = remember(cometLength) { LoaderMotion(cometLength) }
    val geometry = remember { LoaderGeometry() }
    Canvas(modifier) {
        drawNearYouLoader(motion, geometry, color, trackAlpha, lapMillis, progress)
    }
}

/** The glyph + route [Path]s — built once and reused across frames. */
internal class LoaderGeometry {
    val glyph = GLYPH_POLYLINES.map(::polylinePath)
    val arc1 = polylinePath(ARC1)
    val arc2 = polylinePath(ARC2)
}

/**
 * Draws one loader frame into any [DrawScope] (the composable wraps this; render tests call it
 * directly against an `ImageBitmap`-backed scope, no window needed).
 */
internal fun DrawScope.drawNearYouLoader(
    motion: LoaderMotion,
    geometry: LoaderGeometry,
    color: Color,
    trackAlpha: Float,
    lapMillis: Int,
    progress: Float,
) {
    val scale = size.minDimension / VIEWPORT_SIZE
    withTransform({
        translate(
            (size.width - VIEWPORT_SIZE * scale) / 2f,
            (size.height - VIEWPORT_SIZE * scale) / 2f,
        )
        scale(scale, scale, pivot = Offset.Zero)
        translate(-VIEWPORT_ORIGIN, -VIEWPORT_ORIGIN)
    }) {
        if (trackAlpha > 0f) {
            geometry.glyph.forEach { drawPath(it, color, alpha = trackAlpha, style = glyphStroke()) }
            drawCircle(color, DOT_RADIUS, DOT_CENTER, alpha = trackAlpha)
        }

        drawMaskedComet(motion, geometry, color, progress)

        val energy = motion.energyAt(progress)
        if (energy > 0.01f) {
            // Shimmer cycles are quantized per lap so the pulse is seamless across the wrap.
            val cycles = max(1, (SHIMMER_HZ * lapMillis / 1000f).roundToInt())
            val pulse = 0.72f + 0.28f * sin(2f * PI.toFloat() * cycles * progress)
            val haloRadius = 3.4f + 2.4f * energy * pulse
            drawCircle(
                brush =
                    Brush.radialGradient(
                        0f to lerp(color, Color.White, 0.55f).copy(alpha = 0.95f),
                        0.45f to color.copy(alpha = 0.5f),
                        1f to color.copy(alpha = 0f),
                        center = DOT_CENTER,
                        radius = haloRadius,
                    ),
                radius = haloRadius,
                center = DOT_CENTER,
                alpha = 0.9f * energy * pulse,
            )
            drawCircle(color, DOT_RADIUS + 0.6f * energy * pulse, DOT_CENTER, alpha = energy)
        }
    }
}

private fun glyphStroke(
    pathEffect: PathEffect? = null,
    width: Float = STROKE_WIDTH,
) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = pathEffect)

/**
 * Draws the comet (soft glow pass + faded trail + bright core) clipped to the glyph strokes via
 * a DstIn layer — the light only ever exists on real ink, which is what makes the hidden jump,
 * the open left edge, and the dot-flanking gaps read as "passing behind" for free.
 */
private fun DrawScope.drawMaskedComet(
    motion: LoaderMotion,
    geometry: LoaderGeometry,
    color: Color,
    progress: Float,
) {
    val total = motion.totalLength
    val tail = motion.tailAt(progress)

    fun dash(
        length: Float,
        localTail: Float,
    ): PathEffect {
        val phase = (((-localTail) % total) + total) % total
        return PathEffect.dashPathEffect(floatArrayOf(length, total - length), phase)
    }

    val coreShift = motion.cometLength - motion.coreLength
    val layers =
        listOf(
            Triple(motion.cometLength, GLOW_WIDTH, GLOW_ALPHA),
            Triple(motion.cometLength, STROKE_WIDTH, TRAIL_ALPHA),
            Triple(motion.coreLength, STROKE_WIDTH, 1f),
        )

    val bounds = Rect(VIEWPORT_ORIGIN, VIEWPORT_ORIGIN, VIEWPORT_ORIGIN + VIEWPORT_SIZE, VIEWPORT_ORIGIN + VIEWPORT_SIZE)
    val canvas = drawContext.canvas
    canvas.saveLayer(bounds, Paint())
    layers.forEach { (length, width, alpha) ->
        val shift = if (length == motion.coreLength) coreShift else 0f
        drawPath(geometry.arc1, color, alpha = alpha, style = glyphStroke(dash(length, tail + shift), width))
        drawPath(
            geometry.arc2,
            color,
            alpha = alpha,
            style = glyphStroke(dash(length, tail + shift - motion.arc2Start), width),
        )
    }
    canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
    geometry.glyph.forEach { drawPath(it, Color.White, style = glyphStroke()) }
    drawCircle(Color.White, DOT_RADIUS, DOT_CENTER)
    canvas.restore()
    canvas.restore()
}
