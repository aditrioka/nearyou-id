package id.nearyou.app.infra.fcm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers `fcm-push-dispatch` spec § "PushCopy SHALL provide Indonesian copy".
 * Tasks 6.1 + 6.7.
 */
class PushCopyTest : StringSpec(
    {
        "post_liked body uses actor username when present" {
            PushCopy.bodyFor("post_liked", "bobby") shouldBe "bobby menyukai post-mu"
        }

        "post_liked body falls back when actor username is null" {
            PushCopy.bodyFor("post_liked", null) shouldBe "Seseorang menyukai post-mu"
        }

        "post_replied body uses actor username when present" {
            PushCopy.bodyFor("post_replied", "alice") shouldBe "alice membalas post-mu"
        }

        "followed body uses actor username when present" {
            PushCopy.bodyFor("followed", "carla") shouldBe "carla mulai mengikuti kamu"
        }

        "post_auto_hidden body is the system constant string" {
            PushCopy.bodyFor("post_auto_hidden", null) shouldBe
                "Postinganmu disembunyikan otomatis karena beberapa laporan"
        }

        "unknown / unwired type returns the fallback (chat_message present)" {
            PushCopy.bodyFor("chat_message", "bobby") shouldBe "Notifikasi baru dari NearYou"
            PushCopy.bodyFor("subscription_billing_issue", null) shouldBe "Notifikasi baru dari NearYou"
        }

        "fallback does NOT interpolate actor username (task 6.7)" {
            // The fallback intentionally does not include {actor_username};
            // confirms PushCopy does not naively substitute and produce
            // "bobby Notifikasi baru dari NearYou" or similar.
            PushCopy.bodyFor("chat_message", "bobby") shouldBe "Notifikasi baru dari NearYou"
        }

        "titleFor returns NearYou for every known type and unknown" {
            listOf(
                "post_liked",
                "post_replied",
                "followed",
                "post_auto_hidden",
                "chat_message",
                "no_such_type",
            ).forEach { type ->
                PushCopy.titleFor(type) shouldBe "NearYou"
            }
        }
    },
)
