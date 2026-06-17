package id.nearyou.app.username

import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [UsernameFlow] for the ViewModel + screen tests (mirrors `FakeSearchFlow`). Returns a
 * pre-programmed [UsernameChangeOutcome] for `change` and, for `check`, the outcome at the call index
 * from [checkOutcomes] (clamped to the last element — so `[Available(true), ProbeExhausted]` returns
 * `Available(true)` then `ProbeExhausted` for every subsequent probe, modeling the 3/day exhaustion).
 * Records the argument of every call so a test can assert the debounce / submit / probe wiring. With
 * [suspendForever] = true, both methods never return (the screen stays in its in-flight state).
 */
class FakeUsernameFlow(
    private val changeOutcome: UsernameChangeOutcome = UsernameChangeOutcome.Success("newhandle"),
    private val checkOutcomes: List<UsernameCheckOutcome> = listOf(UsernameCheckOutcome.Available(true)),
    private val suspendForever: Boolean = false,
) : UsernameFlow {
    val changeCalls: MutableList<String> = mutableListOf()
    val checkCalls: MutableList<String> = mutableListOf()

    override suspend fun change(newUsername: String): UsernameChangeOutcome {
        changeCalls += newUsername
        if (suspendForever) awaitCancellation()
        return changeOutcome
    }

    override suspend fun check(candidate: String): UsernameCheckOutcome {
        val index = checkCalls.size
        checkCalls += candidate
        if (suspendForever) awaitCancellation()
        return checkOutcomes[minOf(index, checkOutcomes.lastIndex)]
    }
}
