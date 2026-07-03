package id.nearyou.app.ui.components

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the [LoaderMotion] math the loader's rendering inverts per frame: the variable-speed clock
 * stays monotonic and spans the whole virtual route, the fully-dark beat between the two arcs
 * actually exists (that beat IS the "passes behind the mark" illusion), and the dot-glow window
 * sits inside the lap in the right order. Pure commonMain math — no composition needed.
 */
class LoaderMotionTest {
    private val motion = LoaderMotion(50f)

    @Test
    fun routeConstantsMatchTheGlyphGeometry() {
        // Analytic lengths of the drawable's polylines; a drifting waypoint edit should fail here.
        assertTrue(abs(ARC1_LENGTH - 37.893f) < 0.01f, "arc1 length drifted: $ARC1_LENGTH")
        assertTrue(abs(ROUTE_LENGTH - 151.771f) < 0.01f, "route length drifted: $ROUTE_LENGTH")
    }

    @Test
    fun tailIsMonotonicAndCoversTheFullVirtualRoute() {
        var previous = motion.tailAt(0f)
        assertTrue(previous < 0.001f, "lap must start at the route origin, was $previous")
        for (i in 1..1000) {
            val tail = motion.tailAt(i / 1000f)
            assertTrue(tail >= previous - 0.001f, "clock went backwards at p=${i / 1000f}")
            previous = tail
        }
        assertTrue(
            abs(previous - motion.totalLength) < 0.01f,
            "lap must end at the virtual route end (${motion.totalLength}), was $previous",
        )
    }

    @Test
    fun fullyDarkBeatExistsBetweenTheArcs() {
        // Mid-point of the hidden segment's fully-dark stretch: the comet must fit entirely
        // inside [arc1 end, arc2 start] there — nothing rendered on either arc.
        val darkTail = (ARC1_LENGTH + (motion.arc2Start - motion.cometLength)) / 2f
        val progress = motion.timeAtTail(darkTail)
        val tail = motion.tailAt(progress)
        assertTrue(abs(tail - darkTail) < 0.5f, "timeAtTail/tailAt roundtrip drifted: $tail vs $darkTail")
        assertTrue(tail >= ARC1_LENGTH, "comet tail still on arc1 during the dark beat")
        assertTrue(
            tail + motion.cometLength <= motion.arc2Start,
            "comet head already on arc2 during the dark beat",
        )
    }

    @Test
    fun dotWindowIsOrderedAndEnergyEnvelopeTracksIt() {
        assertTrue(motion.dotOnProgress > 0f && motion.dotOnProgress < motion.dotOffProgress)
        assertTrue(motion.dotOffProgress <= 1f)
        val mid = (motion.dotOnProgress + motion.dotOffProgress) / 2f
        assertTrue(motion.energyAt(mid) == 1f, "dot must be fully lit mid-window")
        assertTrue(motion.energyAt(0.3f) == 0f, "dot must be dark while the comet rides the top hexagon")
    }
}
