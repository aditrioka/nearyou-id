package id.nearyou.app.config

fun secretKey(
    env: String,
    name: String,
): String = if (env == "staging") "staging-$name" else name

interface SecretResolver {
    fun resolve(name: String): String?
}

class EnvVarSecretResolver(
    private val getenv: (String) -> String? = System::getenv,
    private val getSystemProperty: (String) -> String? = System::getProperty,
) : SecretResolver {
    // Trim surrounding whitespace on resolve. Secret Manager stores raw bytes,
    // and values uploaded via `echo ... | gcloud secrets versions add` or pasted
    // through the console UI often carry a trailing `\n` the user didn't intend.
    // Downstream decoders (Base64.getDecoder, HMAC key material) reject that
    // whitespace, producing cryptic startup failures. Intentional leading or
    // trailing whitespace in a secret value is not a use case we support.
    override fun resolve(name: String): String? {
        val envName = name.uppercase().replace('-', '_')
        val fromEnv = getenv(envName)?.trim()?.takeIf { it.isNotBlank() }
        if (fromEnv != null) return fromEnv
        // Tests cannot portably mutate the process env, so fall back to a JVM
        // system property under the same normalized name. In production no
        // process sets these, so the chain reduces to env-var-only behavior.
        return getSystemProperty(envName)?.trim()?.takeIf { it.isNotBlank() }
    }
}
