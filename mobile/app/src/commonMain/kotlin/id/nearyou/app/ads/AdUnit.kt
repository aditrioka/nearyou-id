package id.nearyou.app.ads

/**
 * The native-ad unit ID for this platform. Currently Google's documented TEST unit (staging/debug
 * verification, no AdMob account needed — task 1.2). The operator's REAL per-flavor unit ID (post-AdMob
 * approval) is wired here behind the build flavor before live serving (human-required task).
 */
expect val nativeAdUnitId: String
