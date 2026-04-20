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
    override fun resolve(name: String): String? {
        val envName = name.uppercase().replace('-', '_')
        val fromEnv = getenv(envName)?.takeIf { it.isNotBlank() }
        if (fromEnv != null) return fromEnv
        // Tests cannot portably mutate the process env, so fall back to a JVM
        // system property under the same normalized name. In production no
        // process sets these, so the chain reduces to env-var-only behavior.
        return getSystemProperty(envName)?.takeIf { it.isNotBlank() }
    }
}
