plugins {
    id("nearyou.kotlin.jvm")
    id("io.ktor.plugin")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.flywaydb.flyway")
    application
}

flyway {
    url = providers.environmentVariable("DB_URL").orNull
    user = providers.environmentVariable("DB_USER").orNull
    password = providers.environmentVariable("DB_PASSWORD").orNull
    locations = arrayOf("classpath:db/migration")
}
