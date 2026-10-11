package id.nearyou.app.internal

/**
 * The scheduler service account every OIDC route test allowlists and puts in its minted
 * token's `email` claim (internal-endpoint-auth property 4, #544).
 */
internal const val TEST_OIDC_PRINCIPAL = "scheduler@nearyou-staging.iam.gserviceaccount.com"

/** A Google-valid service account from another project — never allowlisted. */
internal const val TEST_FOREIGN_OIDC_PRINCIPAL = "attacker@other-project.iam.gserviceaccount.com"
