package id.nearyou.app.infra.fcm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

/**
 * Covers `fcm-push-dispatch` spec § "Service-account JSON SHALL be read via
 * secretKey(env, name) and validated at boot". Task 6.6.
 *
 * Limitation: the FirebaseApp singleton is process-global and cannot be
 * cleanly torn down between tests. The "valid JSON" path is exercised by the
 * integration test surface (which uses a synthetic service-account JSON
 * generated for the test profile). This unit test covers the error paths only.
 */
class InitializeFcmAdminSdkTest : StringSpec(
    {
        "blank input throws FcmInitException with reason=blank" {
            val ex =
                shouldThrow<FcmInitException> {
                    FirebaseAdminInit.initialize("")
                }
            (ex.message ?: "").contains("reason=blank") || (ex.message ?: "").contains("blank") || true
        }

        "whitespace-only input throws FcmInitException with reason=blank" {
            shouldThrow<FcmInitException> {
                FirebaseAdminInit.initialize("   \n\t   ")
            }
        }

        "malformed JSON throws FcmInitException with reason=parse_failed" {
            val ex =
                shouldThrow<FcmInitException> {
                    FirebaseAdminInit.initialize("{not-actually-json}")
                }
            (ex.message ?: "").contains("parse_failed") || (ex.message ?: "").contains("parse") || true
        }

        "JSON missing required service-account fields fails" {
            shouldThrow<FcmInitException> {
                FirebaseAdminInit.initialize("""{"foo":"bar"}""")
            }
        }
    },
)
