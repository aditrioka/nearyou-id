package id.nearyou.app.dev

import id.nearyou.app.admin.auth.AesGcmCipher
import id.nearyou.app.admin.auth.PasswordHasher
import org.apache.commons.codec.binary.Base32
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Dev/operator-only helper. Generates the SQL `INSERT` for the FIRST admin
 * (and any subsequent manually-provisioned admin) per `admin-login-argon2-totp`
 * design.md D11 — there is no admin self-signup endpoint by design, so the
 * bootstrap admin row is created out-of-band via this CLI.
 *
 * It produces:
 *  - an Argon2id `password_hash` for an interactively-entered password,
 *  - a SecureRandom 160-bit (20-byte) TOTP secret, base32-encoded for
 *    authenticator-app provisioning, AND AES-256-GCM-encrypted (with the
 *    admin UUID bound as AAD, per design.md D1) for `totp_secret_encrypted`,
 *  - a ready-to-paste `INSERT INTO admin_users (...)` statement.
 *
 * Security posture (verified by AdminBootstrapMainTest):
 *  - The plaintext password is NEVER printed and NEVER appears in the SQL
 *    (only its Argon2id hash).
 *  - The AES key is NEVER printed.
 *  - The base32 TOTP secret IS printed ONCE (the operator must enter it into
 *    their authenticator app), with a clear DO-NOT-SAVE warning.
 *
 * Invoke via `dev/scripts/admin-bootstrap/admin-bootstrap.sh` which sets the
 * AES key env var (with shell-history mitigation — see that README) and
 * shells out to the `adminBootstrap` Gradle task. Do NOT run in production
 * without the production AES key slot provisioned.
 */
fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    if (parsed == null) {
        System.err.println(
            "usage: admin-bootstrap --email <email> --display-name <name> --role <owner|admin|moderator|read_only>",
        )
        kotlin.system.exitProcess(2)
    }

    val aesKeyBase64 =
        System.getenv(AES_KEY_ENV)?.takeIf { it.isNotBlank() }
            ?: run {
                System.err.println(
                    "$AES_KEY_ENV is not set. Resolve it (with a leading space to keep it out of shell history):\n" +
                        "   export $AES_KEY_ENV=\"\$(gcloud secrets versions access latest --secret=<slot>)\"",
                )
                kotlin.system.exitProcess(1)
            }
    val aesKey =
        try {
            Base64.getDecoder().decode(aesKeyBase64.trim())
        } catch (_: IllegalArgumentException) {
            System.err.println("$AES_KEY_ENV is not valid base64")
            kotlin.system.exitProcess(1)
        }
    if (aesKey.size != AES_256_KEY_BYTES) {
        System.err.println("$AES_KEY_ENV must decode to $AES_256_KEY_BYTES bytes (AES-256); got ${aesKey.size}")
        kotlin.system.exitProcess(1)
    }

    // Read the password interactively. Prefer the console (no echo); fall
    // back to a stdin line for piped input (CI / scripted provisioning).
    val console = System.console()
    val password =
        if (console != null) {
            String(console.readPassword("New admin password (input hidden): "))
        } else {
            System.err.print("New admin password (WARNING: stdin not a TTY, input may echo): ")
            readlnOrNull().orEmpty()
        }
    if (password.isBlank()) {
        System.err.println("password must not be blank")
        kotlin.system.exitProcess(1)
    }

    val result = buildAdminBootstrap(parsed.email, parsed.displayName, parsed.role, password, aesKey)

    // Print the operator instructions + SQL. The base32 secret is surfaced
    // exactly ONCE here for authenticator-app entry.
    println(operatorOutput(result))
}

private const val AES_KEY_ENV = "ADMIN_TOTP_AES_KEY_BASE64"
private const val AES_256_KEY_BYTES = 32
private const val TOTP_SECRET_BYTES = 20 // 160 bits

const val DO_NOT_SAVE_WARNING =
    "# ⚠ DO NOT save this output to a file. The base32 TOTP secret below is " +
        "shown ONCE — enter it into the authenticator app now, then discard this terminal scrollback."

/** Allowed roles per the V16 `admin_users_role_check` constraint. */
private val VALID_ROLES = setOf("owner", "admin", "moderator", "read_only")

private val secureRandom = SecureRandom()
private val base32 = Base32()

data class AdminBootstrapArgs(
    val email: String,
    val displayName: String,
    val role: String,
)

data class AdminBootstrapResult(
    val adminId: UUID,
    /** base32-encoded TOTP secret for authenticator-app provisioning. */
    val base32Secret: String,
    /** ready-to-paste SQL INSERT statement. */
    val sqlInsert: String,
)

internal fun parseArgs(args: Array<String>): AdminBootstrapArgs? {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size - 1) {
        if (args[i].startsWith("--")) {
            map[args[i].removePrefix("--")] = args[i + 1]
            i += 2
        } else {
            i += 1
        }
    }
    val email = map["email"]?.takeIf { it.isNotBlank() } ?: return null
    val displayName = map["display-name"]?.takeIf { it.isNotBlank() } ?: return null
    val role = map["role"]?.takeIf { it in VALID_ROLES } ?: return null
    return AdminBootstrapArgs(email, displayName, role)
}

/**
 * Pure, testable core: produces the admin UUID + base32 TOTP secret +
 * SQL INSERT. [totpSecret] and [adminId] are injectable for deterministic
 * tests; production callers omit them to get SecureRandom values.
 *
 * The SQL INSERT encodes `totp_secret_encrypted` via `decode('<base64>',
 * 'base64')` so the BYTEA column gets the exact `nonce || ciphertext ||
 * auth_tag` bytes [AesGcmCipher.encrypt] produced.
 */
internal fun buildAdminBootstrap(
    email: String,
    displayName: String,
    role: String,
    password: String,
    aesKey: ByteArray,
    totpSecret: ByteArray = ByteArray(TOTP_SECRET_BYTES).also { secureRandom.nextBytes(it) },
    adminId: UUID = UUID.randomUUID(),
): AdminBootstrapResult {
    val passwordHash = PasswordHasher.hash(password)
    val encrypted = AesGcmCipher.encrypt(totpSecret, aesKey, AesGcmCipher.adminUuidAsBytes(adminId))
    val encryptedBase64 = Base64.getEncoder().encodeToString(encrypted)
    val base32Secret = base32.encodeAsString(totpSecret)

    val sql =
        buildString {
            append("INSERT INTO admin_users (id, email, display_name, password_hash, totp_secret_encrypted, role, is_active)\n")
            append("VALUES (\n")
            append("    '").append(adminId).append("',\n")
            append("    ").append(sqlQuote(email)).append(",\n")
            append("    ").append(sqlQuote(displayName)).append(",\n")
            append("    ").append(sqlQuote(passwordHash)).append(",\n")
            append("    decode('").append(encryptedBase64).append("', 'base64'),\n")
            append("    ").append(sqlQuote(role)).append(",\n")
            append("    TRUE\n")
            append(");")
        }

    return AdminBootstrapResult(adminId, base32Secret, sql)
}

/** The full operator-facing output (warning + base32 secret + SQL). */
internal fun operatorOutput(result: AdminBootstrapResult): String =
    buildString {
        append(DO_NOT_SAVE_WARNING).append("\n\n")
        append("# Admin UUID: ").append(result.adminId).append("\n")
        append("# TOTP secret (base32 — enter into authenticator app NOW): ")
            .append(result.base32Secret).append("\n\n")
        append(result.sqlInsert).append("\n")
    }

/** Minimal single-quote SQL string escaping for the generated INSERT. */
private fun sqlQuote(value: String): String = "'" + value.replace("'", "''") + "'"
