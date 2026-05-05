package id.nearyou.app.infra.fcm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers `fcm-push-dispatch` spec § "PushCopy SHALL provide Indonesian copy".
 * Originally tasks 6.1 + 6.7; extended for `chat-message-notification` Section 3.
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

        // ---- chat-message-notification Section 3 (tasks 3.1 + 3.3) ----
        "chat_message body uses actor username when present" {
            PushCopy.bodyFor("chat_message", "bobby") shouldBe "bobby mengirim pesan"
        }

        "chat_message body falls back when actor username is null" {
            PushCopy.bodyFor("chat_message", null) shouldBe "Seseorang mengirim pesan"
        }

        "chat_message body falls back when actor username is empty string" {
            // Empty-string defends against ActorUsernameLookup returning "" instead of
            // null. Per spec § "chat_message body with empty-string actor username falls
            // back to null-fallback" — MUST NOT render " mengirim pesan" with leading space.
            PushCopy.bodyFor("chat_message", "") shouldBe "Seseorang mengirim pesan"
        }

        "chat_message body renders emoji in actor username verbatim" {
            // Defensive against future schema relaxations allowing Unicode usernames.
            PushCopy.bodyFor("chat_message", "bobby🎉") shouldBe "bobby🎉 mengirim pesan"
        }

        "chat_message body does NOT inline body_data.preview" {
            // Per spec § "chat_message body does NOT inline body_data.preview" — preview
            // rendering is owned by the mobile NSE / Android side via the data payload.
            // The body_data parameter is not part of bodyFor's signature; this is a
            // structural assertion that no preview text leaks into the body string.
            val body = PushCopy.bodyFor("chat_message", "bobby")
            (body.contains("halo")) shouldBe false
            (body.contains("Alice")) shouldBe false
            body shouldBe "bobby mengirim pesan"
        }

        "unknown / unwired type returns the fallback" {
            // Note: chat_message used to be the "unwired" example here; after
            // chat-message-notification it is wired. The example swaps to
            // subscription_billing_issue (still unwired), per spec § "iOS payload uses
            // fallback copy for unwired notification types" + tasks.md task 3.3.
            PushCopy.bodyFor("subscription_billing_issue", null) shouldBe "Notifikasi baru dari NearYou"
            PushCopy.bodyFor("subscription_billing_issue", "bobby") shouldBe "Notifikasi baru dari NearYou"
        }

        "fallback does NOT interpolate actor username" {
            // The fallback intentionally does not include {actor_username};
            // confirms PushCopy does not naively substitute and produce
            // "bobby Notifikasi baru dari NearYou" or similar.
            PushCopy.bodyFor("subscription_billing_issue", "bobby") shouldBe "Notifikasi baru dari NearYou"
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
