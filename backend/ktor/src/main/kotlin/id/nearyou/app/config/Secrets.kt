package id.nearyou.app.config

fun secretKey(
    env: String,
    name: String,
): String = if (env == "staging") "staging-$name" else name
