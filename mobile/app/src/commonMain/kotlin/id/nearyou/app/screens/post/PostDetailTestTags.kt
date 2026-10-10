package id.nearyou.app.screens.post

// The post-detail surface's stable test tags (Robolectric + iOS flow tests + the on-device harness). Split out
// of PostDetailScreen.kt by post-detail-vm-reply-delete-restyle (docs/11 § 4 UI soft cap).

/** Test tag on the clickable like control (the toggle target). */
const val POST_DETAIL_LIKE_TOGGLE_TAG: String = "postDetailLikeToggle"

/** Test tag on the like-state indicator when LIKED (asserts the optimistic flip). */
const val POST_DETAIL_LIKE_LIKED_TAG: String = "postDetailLikeLiked"

/** Test tag on the like-state indicator when NOT liked (asserts the revert). */
const val POST_DETAIL_LIKE_NOT_LIKED_TAG: String = "postDetailLikeNotLiked"

/** Test tag on the bare like-count node in the action row (present only when the count is available). */
const val POST_DETAIL_LIKE_COUNT_TAG: String = "postDetailLikeCount"

/** Test tag on the displayed reply-count node (asserts the local +1 on a 201 / −1 on an own-reply delete). */
const val POST_DETAIL_REPLY_COUNT_TAG: String = "postDetailReplyCount"

/** Test tag on the action row's share-to-chat icon (frame 7's `send` action). */
const val POST_DETAIL_SHARE_ACTION_TAG: String = "postDetailShareAction"

/** Test tag on the multiline reply field — lets the screen test target it for text input. */
const val POST_DETAIL_REPLY_FIELD_TAG: String = "postDetailReplyField"

/** Test tag on the composer's send action (the "Balas" CTA). */
const val POST_DETAIL_REPLY_SEND_TAG: String = "postDetailReplySend"

/** Test tag on the back affordance (the top app bar's back arrow). */
const val POST_DETAIL_BACK_TAG: String = "postDetailBack"

/** Test tag on the replies-error retry control. */
const val POST_DETAIL_REPLIES_RETRY_TAG: String = "postDetailRepliesRetry"

/** Test tag on the Edit affordance (shown for the viewer's own post within the 30-min window). */
const val POST_DETAIL_EDIT_TAG: String = "postDetailEdit"

/** Test tag on the "Diedit" label (opens the "Riwayat edit" history overlay). */
const val POST_DETAIL_EDITED_LABEL_TAG: String = "postDetailEditedLabel"

/** Test tag on the attached-image node (`image-attached-posts`) — present only when the route carries a
 *  non-null `imageUrl`, so a test can assert the image renders when supplied and is absent when null. */
const val POST_DETAIL_IMAGE_TAG: String = "postDetailImage"

/** Test tag on the post kebab (the top app bar's overflow — share / block / report). */
const val POST_DETAIL_REPORT_POST_TAG: String = "postDetailReportPost"

/** Test tag on the "Bagikan ke chat" overflow item (chat-embedded-posts). */
const val POST_DETAIL_SHARE_TO_CHAT_TAG: String = "postDetailShareToChat"

/** Test tag on a reply row's kebab (report on every reply; block / delete per authorship). */
const val POST_DETAIL_REPORT_REPLY_TAG: String = "postDetailReportReply"

/** Test tag on the shared report dialog when opened from the post-detail surface. */
const val POST_DETAIL_REPORT_DIALOG_TAG: String = "postDetailReportDialog"

/** Test tag on the post kebab's block item (a non-authored post whose freshness read resolved an
 *  `authorUserId` — mobile-block-from-content). */
const val POST_DETAIL_BLOCK_POST_TAG: String = "postDetailBlockPost"

/** Test tag on a reply row's block menu item (another user's reply with a wire identity only). */
const val POST_DETAIL_BLOCK_REPLY_TAG: String = "postDetailBlockReply"

/** Test tag on the shared block confirmation dialog when opened from the post-detail surface. */
const val POST_DETAIL_BLOCK_DIALOG_TAG: String = "postDetailBlockDialog"

/** Test tag on a reply row's "Hapus balasan" menu item (the viewer's own reply only). */
const val POST_DETAIL_DELETE_REPLY_TAG: String = "postDetailDeleteReply"

/** Test tag on the own-reply delete confirmation dialog. */
const val POST_DETAIL_DELETE_REPLY_DIALOG_TAG: String = "postDetailDeleteReplyDialog"

/** Test tag on the post-header identity tap target — present iff the freshness read resolved an
 *  `authorUserId` (post-detail-tap-to-profile; absent = display-only identity, graceful degradation). */
const val POST_DETAIL_HEADER_PROFILE_TAG: String = "postDetailHeaderProfile"

/** Test tag on a reply row's identity tap target (present iff the reply carries a wire identity). */
const val POST_DETAIL_REPLY_PROFILE_TAG: String = "postDetailReplyProfile"
