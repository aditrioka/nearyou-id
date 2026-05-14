package id.nearyou.app.di

import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class KoinInitTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        // Other test classes in the same test target may have started Koin. Reset to a
        // clean slate so each idempotency assertion below starts from "Koin not running".
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun initKoin_startsKoinOnFirstCall() {
        initKoin()
        assertNotNull(
            KoinPlatformTools.defaultContext().getOrNull(),
            "First initKoin() call should start Koin",
        )
    }

    @Test
    fun initKoin_isIdempotent_secondCallIsNoop() {
        initKoin()
        val first = KoinPlatformTools.defaultContext().get()
        initKoin()
        val second = KoinPlatformTools.defaultContext().get()
        // The guard `if (defaultContext().getOrNull() == null) startKoin { ... }` means
        // the second call MUST NOT replace the existing Koin application. Same instance
        // reference before and after the second invocation.
        assertSame(first, second, "Second initKoin() call must be a no-op (same Koin instance)")
    }

    @Test
    fun doInitKoin_swiftShim_delegatesToInitKoin() {
        // The Swift-callable shim should behave identically to initKoin().
        doInitKoin()
        assertNotNull(
            KoinPlatformTools.defaultContext().getOrNull(),
            "doInitKoin() should start Koin",
        )
        val first = KoinPlatformTools.defaultContext().get()
        doInitKoin()
        assertSame(
            first,
            KoinPlatformTools.defaultContext().get(),
            "doInitKoin() must also be idempotent",
        )
    }
}
