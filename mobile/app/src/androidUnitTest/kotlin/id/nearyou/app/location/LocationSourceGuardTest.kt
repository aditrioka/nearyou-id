package id.nearyou.app.location

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments. The scanned `.kt` files have no `//`
 *  inside string literals, so the naive line strip is safe here. */
private fun String.stripKotlinComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/** Strips XML `<!-- … -->` comments — so the manifest's explanatory comment (which names the
 *  deliberately-excluded permissions) does not trip the permission-posture scan. */
private fun String.stripXmlComments(): String = Regex("""<!--[\s\S]*?-->""").replace(this, " ")

/**
 * Static-source guards for `mobile-location-acquisition-tuning` invariants that are properties of the
 * source, not of a single render — the platform-native tuning (CI cannot run the GMS / CoreLocation
 * APIs) and the privacy/posture invariants (tasks 6.4 / 6.5 / 6.8). Mirrors the repo's
 * `PostCreationSourceGuardTest` / `VendorSdkLeakageScanTest` file-scan idiom (walk to the repo root,
 * read source text, assert). Not a `*ScreenTest`, so it runs in every variant with no Compose runner.
 *
 * Covers:
 * - Android: a duration-bounded, max-age-bounded, coarse `CurrentLocationRequest` (NOT the unbounded
 *   2-arg `getCurrentLocation(priority, token)` overload) with an age-checked `lastLocation` first.
 * - iOS: a coroutine-timeout-wrapped `requestLocation()` with a `CLLocationManager.location`
 *   `timestamp` reuse check and a nil/too-old fall-through.
 * - Coarse-only / no-background posture (re-asserted from the MODIFIED requirement): Android manifest
 *   declares `ACCESS_COARSE_LOCATION` only; iOS requests when-in-use, never "Always".
 * - No coordinate/timestamp ever reaches a logging/diagnostic call site in the decorator or either
 *   tuned actual (design D6 — makes the strongest privacy claim test-enforced, not review-enforced).
 * - `StubLocationProvider` is retained as the unqualified test double.
 */
class LocationSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    private fun rawSource(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText()
    }

    /** Kotlin source with comments stripped — the guards are about CODE behaviour, so an explanatory
     *  KDoc that mentions a forbidden token (e.g. "never logged") must not trip the scan. */
    private fun code(relativePath: String): String = rawSource(relativePath).stripKotlinComments()

    private val androidProvider by lazy { code("mobile/app/src/androidMain/kotlin/id/nearyou/app/location/AndroidLocationProvider.kt") }
    private val iosProvider by lazy { code("mobile/app/src/iosMain/kotlin/id/nearyou/app/location/IosLocationProvider.kt") }
    private val decorator by lazy { code("mobile/app/src/commonMain/kotlin/id/nearyou/app/location/CachingLocationProvider.kt") }

    // ---- 6.4: Android CurrentLocationRequest tuning ----

    @Test
    fun androidProvider_usesBoundedMaxAgeCoarseCurrentLocationRequest() {
        assertTrue(androidProvider.contains("CurrentLocationRequest"), "Android must build a CurrentLocationRequest")
        assertTrue(androidProvider.contains("setDurationMillis"), "the request must bound the wait via setDurationMillis")
        assertTrue(
            androidProvider.contains("setMaxUpdateAgeMillis"),
            "the request must accept a recent cached fix via setMaxUpdateAgeMillis",
        )
        assertTrue(androidProvider.contains("setGranularity(Granularity.GRANULARITY_COARSE)"), "the request must be coarse-granular")
        // The request-based overload — NOT the unbounded 2-arg getCurrentLocation(priority, token).
        assertTrue(androidProvider.contains("getCurrentLocation(request"), "getCurrentLocation must take the CurrentLocationRequest")
        assertFalse(
            androidProvider.contains("getCurrentLocation(Priority"),
            "the unbounded 2-arg getCurrentLocation(priority, token) overload must not be used",
        )
        // An age-acceptable lastLocation precedes the fresh acquisition.
        assertTrue(androidProvider.contains("lastLocation"), "the provider must attempt lastLocation")
        assertTrue(
            androidProvider.indexOf("lastLocation") < androidProvider.indexOf("getCurrentLocation("),
            "a lastLocation attempt must precede the fresh getCurrentLocation acquisition",
        )
        assertFalse(androidProvider.contains("ACCESS_FINE_LOCATION"), "the provider must not reference ACCESS_FINE_LOCATION")
    }

    // ---- 6.5: iOS timeout ceiling + cached-fix reuse ----

    @Test
    fun iosProvider_wrapsRequestLocationInTimeoutAndReusesCachedFix() {
        assertTrue(iosProvider.contains("requestLocation"), "iOS must call requestLocation()")
        assertTrue(iosProvider.contains("withTimeout"), "the one-shot requestLocation() acquisition must be wrapped in a coroutine timeout")
        assertTrue(iosProvider.contains("timestamp"), "the cached-fix reuse must compare CLLocationManager.location.timestamp")
        assertTrue(iosProvider.contains("timeIntervalSinceNow"), "staleness must be computed from the cached fix timestamp")
        assertTrue(iosProvider.contains("?: return null"), "a nil/absent cached fix must fall through to a fresh acquisition")
        assertTrue(
            iosProvider.contains("LocationUnavailableException"),
            "timeout/failure must resolve to LocationUnavailableException",
        )
        assertTrue(iosProvider.contains("kCLLocationAccuracyReduced"), "iOS must keep reduced (coarse) accuracy")
        assertFalse(iosProvider.contains("requestAlwaysAuthorization"), "iOS must not request Always/background authorization")
    }

    // ---- coarse-only / no-background posture (MODIFIED requirement, re-asserted) ----

    @Test
    fun androidManifest_declaresCoarseOnly_noFine_noBackground() {
        val manifest = rawSource("mobile/app/src/androidMain/AndroidManifest.xml").stripXmlComments()
        assertTrue(manifest.contains("ACCESS_COARSE_LOCATION"), "the manifest must declare ACCESS_COARSE_LOCATION")
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION"), "the manifest must NOT declare ACCESS_FINE_LOCATION")
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"), "the manifest must NOT declare background location")
    }

    @Test
    fun iosController_requestsWhenInUse_notAlways() {
        val controller = code("mobile/app/src/iosMain/kotlin/id/nearyou/app/location/IosLocationPermissionController.kt")
        assertTrue(controller.contains("requestWhenInUseAuthorization"), "iOS must request when-in-use authorization")
        assertFalse(controller.contains("requestAlwaysAuthorization"), "iOS must NOT request Always/background authorization")
    }

    // ---- 6.8: no coordinate/timestamp ever reaches a logging/diagnostic call ----

    @Test
    fun decoratorAndActuals_neverLogTheCoordinateOrTimestamp() {
        val logToken = Regex("""\bLog\.""") // android.util.Log.d/e/w/i/v
        // K/N stdout call distinct from println(...) (which `print` is NOT a prefix-match for: print\s*\(
        // does not match "println("); guards the iOS actual against a bare print(coordinate).
        val printToken = Regex("""\bprint\s*\(""")
        for ((name, src) in listOf(
            "CachingLocationProvider" to decorator,
            "AndroidLocationProvider" to androidProvider,
            "IosLocationProvider" to iosProvider,
        )) {
            assertFalse(src.contains("println"), "$name must not println (the coordinate must never be logged)")
            assertFalse(printToken.containsMatchIn(src), "$name must not print( the coordinate (Kotlin/Native stdout)")
            assertFalse(logToken.containsMatchIn(src), "$name must not use android.util.Log")
            assertFalse(src.contains("NSLog"), "$name must not NSLog")
            assertFalse(src.contains("os_log"), "$name must not os_log")
            assertFalse(src.contains("Napier"), "$name must not log via Napier")
            assertFalse(src.contains("Timber"), "$name must not log via Timber")
            assertFalse(src.contains("LogLevel.BODY"), "$name must not widen logging to BODY")
            assertFalse(src.contains("LogLevel.ALL"), "$name must not widen logging to ALL")
        }
        // With NO diagnostic call site present in any of the three, the held coordinate / cached-fix
        // timestamp cannot reach one — the no-coordinate-logging invariant is structurally guaranteed.
    }

    @Test
    fun decorator_usesInjectedClockSeam_notWallClock() {
        // Spec "Staleness uses an injected clock, not a wall-clock call": both THEN clauses —
        // (a) accepts a TimeSource seam, AND (b) makes no Clock.System / wall-clock call.
        assertTrue(decorator.contains("TimeSource"), "the decorator must accept an injected TimeSource seam")
        assertFalse(decorator.contains("Clock.System"), "staleness must not use a wall-clock Clock.System call")
    }

    // ---- stub retained for tests ----

    @Test
    fun stubLocationProvider_isRetainedAsTheTestDouble() {
        val seam = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/LocationProvider.kt")
        assertTrue(seam.contains("class StubLocationProvider"), "StubLocationProvider must be retained as the test double")
    }

    private companion object {
        /** Walks up from the test working directory to the repo root (the dir holding settings.gradle.kts),
         *  so the scan resolves whether `user.dir` is the module dir or the repo root. */
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
