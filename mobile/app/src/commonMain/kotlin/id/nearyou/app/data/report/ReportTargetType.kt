package id.nearyou.app.data.report

/**
 * The reportable target types the shared report seam submits as the wire `target_type` (the SHIPPED
 * `reports` capability accepts `post`/`reply`/`user`/`chat_message`, and this app now surfaces all four).
 * [wire] is the exact `target_type` string the backend validates; the profile surface sends [USER], the
 * post-detail surface sends [POST] / [REPLY], and the chat thread sends [CHAT_MESSAGE]
 * (`mobile-chat-message-report`).
 */
enum class ReportTargetType(val wire: String) {
    /** A user profile (`mobile-profile`'s Laporkan kebab). */
    USER("user"),

    /** An individual post (`PostDetailScreen`'s post-header report affordance). */
    POST("post"),

    /** An individual reply (`PostDetailScreen`'s per-reply report affordance). */
    REPLY("reply"),

    /** An individual 1:1 chat message (`ChatThreadScreen`'s long-press "Laporkan"). */
    CHAT_MESSAGE("chat_message"),
}
