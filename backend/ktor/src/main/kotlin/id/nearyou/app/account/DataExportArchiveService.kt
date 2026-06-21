package id.nearyou.app.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The serialized archive: the ZIP bytes + its content type (always `application/zip`). */
data class DataExportArchive(
    val bytes: ByteArray,
    val contentType: String = "application/zip",
) {
    // Value-based equals/hashCode (a ByteArray field defaults to identity) so tests can assert.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataExportArchive) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
}

/**
 * Serializes a [DataExportBundle] to a single ZIP (design D4): singleton/state categories
 * (profile, DOB, hashed auth id, analytics consent) as **JSON**, tabular categories as **CSV**
 * (the docs/06 § Data Export Scope Matrix Format column), plus a `manifest.json` (schema
 * version, `generated_at`, file list) and a Bahasa-Indonesia `README.txt`.
 *
 * [generatedAt] is passed IN (the worker stamps it) so this layer never calls `Instant.now()`
 * — keeping serialization deterministic and testable. The archive is built in-memory but is
 * bounded by the per-user gather (a fixed set of bounded queries).
 */
class DataExportArchiveService {
    /** Serializes [bundle] into a ZIP, stamping [generatedAt] into the manifest. */
    fun serialize(
        bundle: DataExportBundle,
        generatedAt: Instant,
    ): DataExportArchive {
        val files = LinkedHashMap<String, ByteArray>()

        // --- JSON (singleton / state) ---------------------------------------
        files["profile.json"] = profileJson(bundle).toByteArray(Charsets.UTF_8)

        // --- CSV (tabular) --------------------------------------------------
        files["username_history.csv"] =
            csv(
                listOf("old_username", "new_username", "changed_at"),
                bundle.usernameHistory.map { listOf(it.oldUsername, it.newUsername, it.changedAt.toString()) },
            )
        files["posts.csv"] =
            csv(
                listOf("id", "content", "actual_lat", "actual_lng", "city_name", "created_at", "deleted_at"),
                bundle.posts.map {
                    listOf(
                        it.id.toString(),
                        it.content,
                        it.actualLat.toString(),
                        it.actualLng.toString(),
                        it.cityName.orEmpty(),
                        it.createdAt.toString(),
                        it.deletedAt?.toString().orEmpty(),
                    )
                },
            )
        files["post_edits.csv"] =
            csv(
                listOf("post_id", "content_snapshot", "edited_at"),
                bundle.postEdits.map { listOf(it.postId.toString(), it.contentSnapshot, it.editedAt.toString()) },
            )
        files["likes.csv"] =
            csv(
                listOf("post_id", "created_at"),
                bundle.likes.map { listOf(it.postId.toString(), it.createdAt.toString()) },
            )
        files["replies.csv"] =
            csv(
                listOf("id", "parent_post_id", "content", "created_at", "deleted_at"),
                bundle.replies.map {
                    listOf(
                        it.id.toString(),
                        it.parentPostId.toString(),
                        it.content,
                        it.createdAt.toString(),
                        it.deletedAt?.toString().orEmpty(),
                    )
                },
            )
        files["follows.csv"] =
            csv(
                listOf("direction", "peer_id_hash", "created_at"),
                bundle.follows.map { listOf(it.direction, it.peerIdHash, it.createdAt.toString()) },
            )
        files["blocks.csv"] =
            csv(
                listOf("blocked_id_hash", "created_at"),
                bundle.blocks.map { listOf(it.blockedIdHash, it.createdAt.toString()) },
            )
        files["chat_messages.csv"] =
            csv(
                listOf("conversation_id", "peer_id_hash", "direction", "content", "created_at"),
                bundle.chatMessages.map {
                    listOf(
                        it.conversationId.toString(),
                        it.peerIdHash,
                        it.direction,
                        it.content.orEmpty(),
                        it.createdAt.toString(),
                    )
                },
            )
        files["reports.csv"] =
            csv(
                listOf("target_type", "target_id_hash", "reason_category", "reason_note", "created_at"),
                bundle.reports.map {
                    listOf(
                        it.targetType,
                        it.targetIdHash,
                        it.reasonCategory,
                        it.reasonNote.orEmpty(),
                        it.createdAt.toString(),
                    )
                },
            )
        files["notifications.csv"] =
            csv(
                listOf("type", "target_type", "target_id", "created_at", "read"),
                bundle.notifications.map {
                    listOf(
                        it.type,
                        it.targetType.orEmpty(),
                        it.targetId.orEmpty(),
                        it.createdAt.toString(),
                        (it.readAt != null).toString(),
                    )
                },
            )
        files["moderation_actions.csv"] =
            csv(
                listOf("action_type", "created_at"),
                bundle.moderationActions.map { listOf(it.actionType, it.createdAt.toString()) },
            )
        files["session_history.csv"] =
            csv(
                listOf("device_fingerprint_hash", "created_at", "last_used_at"),
                bundle.sessions.map {
                    listOf(
                        it.deviceFingerprintHash.orEmpty(),
                        it.createdAt.toString(),
                        it.lastUsedAt?.toString().orEmpty(),
                    )
                },
            )
        files["subscription_history.csv"] =
            csv(
                listOf("event_type", "source", "entitlement_start", "entitlement_end", "created_at"),
                bundle.subscriptions.map {
                    listOf(
                        it.eventType,
                        it.source,
                        it.entitlementStart?.toString().orEmpty(),
                        it.entitlementEnd?.toString().orEmpty(),
                        it.createdAt.toString(),
                    )
                },
            )

        // --- manifest + README (added last so the file list is complete) ----
        files["manifest.json"] = manifestJson(bundle, generatedAt, files.keys).toByteArray(Charsets.UTF_8)
        files["README.txt"] = readmeText(generatedAt).toByteArray(Charsets.UTF_8)

        return DataExportArchive(bytes = zip(files))
    }

    private fun profileJson(bundle: DataExportBundle): String {
        val p = bundle.profile
        val obj =
            buildJsonObject {
                put("user_id", JsonPrimitive(p.userId.toString()))
                put("username", JsonPrimitive(p.username))
                put("display_name", JsonPrimitive(p.displayName))
                put("bio", p.bio?.let { JsonPrimitive(it) } ?: JsonNull)
                put("date_of_birth", JsonPrimitive(p.dateOfBirth))
                put("google_id_hash", p.googleIdHash?.let { JsonPrimitive(it) } ?: JsonNull)
                put("apple_id_hash", p.appleIdHash?.let { JsonPrimitive(it) } ?: JsonNull)
                // Raw analytics_consent JSONB text, re-parsed so it nests as an object (not a string).
                put(
                    "analytics_consent",
                    p.analyticsConsentJson?.let {
                        runCatching { JSON.parseToJsonElement(it) }.getOrDefault(JsonPrimitive(it))
                    } ?: JsonNull,
                )
                put("created_at", JsonPrimitive(p.createdAt.toString()))
            }
        return JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    private fun manifestJson(
        bundle: DataExportBundle,
        generatedAt: Instant,
        fileNames: Set<String>,
    ): String {
        val obj =
            buildJsonObject {
                put("user_id", JsonPrimitive(bundle.profile.userId.toString()))
                put("generated_at", JsonPrimitive(generatedAt.toString()))
                put("schema_version", JsonPrimitive(SCHEMA_VERSION))
                put(
                    "files",
                    buildJsonArray {
                        // Manifest lists every other file (itself excluded — it's added after this).
                        fileNames.filter { it != "manifest.json" }.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        return JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    private fun readmeText(generatedAt: Instant): String =
        """
        |Ekspor Data NearYou
        |===================
        |
        |Dibuat pada: $generatedAt
        |Versi skema: $SCHEMA_VERSION
        |
        |Arsip ini berisi salinan data pribadi kamu di NearYou, sesuai hak portabilitas
        |data berdasarkan UU Pelindungan Data Pribadi (UU PDP). Semua data di sini adalah
        |milik kamu sendiri.
        |
        |Isi arsip:
        |  - profile.json             : profil kamu (nama, bio, username, tanggal lahir,
        |                               ID Google/Apple ter-hash, preferensi analitik).
        |  - username_history.csv     : riwayat perubahan username.
        |  - posts.csv                : postingan kamu (termasuk yang masih dalam masa
        |                               tenggang hapus), beserta lokasi asli + kota.
        |  - post_edits.csv           : riwayat suntingan postingan kamu.
        |  - likes.csv                : postingan yang kamu sukai.
        |  - replies.csv              : balasan yang kamu kirim.
        |  - follows.csv              : daftar mengikuti / pengikut (ID lawan di-hash).
        |  - blocks.csv               : daftar blokir (ID yang diblokir di-hash).
        |  - chat_messages.csv        : pesan yang kamu kirim & terima (ID lawan di-hash).
        |  - reports.csv              : laporan yang kamu ajukan (ID target di-hash).
        |  - notifications.csv        : notifikasi yang kamu terima.
        |  - moderation_actions.csv   : tindakan moderasi yang diterapkan ke akun kamu.
        |  - session_history.csv      : riwayat sesi (90 hari terakhir).
        |  - subscription_history.csv : riwayat langganan Premium.
        |
        |Catatan privasi: ID pengguna lain (lawan chat, yang kamu ikuti/blokir, target
        |laporan) sengaja di-hash satu arah untuk melindungi privasi mereka. Tautan unduh
        |arsip ini hanya berlaku 24 jam.
        """.trimMargin()

    /** RFC-4180-ish CSV: CRLF rows, fields quoted + `"`→`""` when they contain `, " CR LF`. */
    private fun csv(
        header: List<String>,
        rows: List<List<String>>,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(header.joinToString(",") { escapeCsv(it) }).append("\r\n")
        for (row in rows) {
            sb.append(row.joinToString(",") { escapeCsv(it) }).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeCsv(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val JSON = Json { prettyPrint = true }
    }
}
