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
) : SecretResolver {
    override fun resolve(name: String): String? {
        val envName = name.uppercase().replace('-', '_')
        return getenv(envName)?.takeIf { it.isNotBlank() }
    }
}
