package id.nearyou.app.screens.post

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-projection coverage of the edit editor (no Compose, no dispatcher): [isWithinEditWindow] (the
 * 30-minute affordance hint, incl. the clock-skew before-creation guard) and the [editPostUiState] submit
 * gate (empty / blank / over-limit / in-flight), reusing the same 280-code-point cap as post creation.
 */
class EditPostUiStateTest {
    @Test
    fun isWithinEditWindow_trueInsideTheWindow_falseOutsideOrBeforeCreation() {
        val created = 1_000_000L
        assertTrue(isWithinEditWindow(created, created), "exactly at creation is within")
        assertTrue(isWithinEditWindow(created, created + 5L * 60 * 1000), "5 min later is within")
        assertTrue(isWithinEditWindow(created, created + EDIT_WINDOW_MILLIS), "the 30-min boundary is within")
        assertFalse(isWithinEditWindow(created, created + EDIT_WINDOW_MILLIS + 1), "1 ms past 30 min is outside")
        assertFalse(isWithinEditWindow(created, created - 1), "a 'now' before creation (clock skew) is outside")
    }

    @Test
    fun submitGate_disablesForEmptyBlankOverLimitOrInFlight() {
        assertFalse(editPostUiState("", isSubmitting = false).submitEnabled, "empty is not submittable")
        assertFalse(editPostUiState("   ", isSubmitting = false).submitEnabled, "blank is not submittable")
        assertTrue(editPostUiState("ok", isSubmitting = false).submitEnabled, "valid content is submittable")
        assertFalse(editPostUiState("ok", isSubmitting = true).submitEnabled, "in-flight blocks submit")
    }

    @Test
    fun charCount_isCodePoints_andOverLimitGatesAt280() {
        val over = editPostUiState("x".repeat(281), isSubmitting = false)
        assertEquals(281, over.charCount)
        assertTrue(over.overLimit)
        assertFalse(over.submitEnabled, "over-limit blocks submit")

        val exactly280 = editPostUiState("x".repeat(280), isSubmitting = false)
        assertEquals(280, exactly280.charCount)
        assertFalse(exactly280.overLimit)
        assertTrue(exactly280.submitEnabled, "exactly 280 is submittable")
    }
}
