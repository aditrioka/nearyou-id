package id.nearyou.app.infra.repo

/**
 * Provider of the (hashed) identity a user authenticates with.
 * The SQL value is the lowercase enum name and must match the
 * CHECK constraint on `rejected_identifiers.identifier_type`.
 */
enum class IdentifierType(val sql: String) {
    GOOGLE("google"),
    APPLE("apple"),
}

/**
 * Reason an identifier was added to the `rejected_identifiers` blocklist.
 * The signup flow writes only `AGE_UNDER_18`; `ATTESTATION_PERSISTENT_FAIL`
 * is reserved for a future attestation-integration change.
 */
enum class RejectedReason(val sql: String) {
    AGE_UNDER_18("age_under_18"),
    ATTESTATION_PERSISTENT_FAIL("attestation_persistent_fail"),
}
