package id.nearyou.app.infra.admob

/**
 * Google's DOCUMENTED test ad-unit IDs for native-advanced ads (per the AdMob "Add test ads" guides).
 * Used for staging/debug verification so the build + Android Test-Lab render loop needs NO AdMob account
 * approval. The REAL operator-provisioned unit IDs (post-AdMob-approval) are injected by `:mobile:app`
 * behind the build flavor (production), never hardcoded here — these test constants are the staging/debug
 * default only. Verified against Google's upstream constants on 2026-06-27 (tasks.md 1.2).
 *
 * The matching test App IDs (`ca-app-pub-3940256099942544~3347511713` Android,
 * `ca-app-pub-3940256099942544~1458002511` iOS) live in the platform manifests / Info.plist, also operator
 * -overridden for production.
 */
object AdTestUnits {
    /** Android native-advanced test ad unit. */
    const val ANDROID_NATIVE: String = "ca-app-pub-3940256099942544/2247696110"

    /** iOS native test ad unit. */
    const val IOS_NATIVE: String = "ca-app-pub-3940256099942544/3986624511"
}
