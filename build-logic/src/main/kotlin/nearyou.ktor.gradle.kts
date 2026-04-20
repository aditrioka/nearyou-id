plugins {
    id("nearyou.kotlin.jvm")
    id("io.ktor.plugin")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.flywaydb.flyway")
    application
}

// TODO(flyway-config-cache): Flyway 11.x Gradle plugin uses Task.project at execution
// time, which Gradle's configuration cache rejects. flywayMigrate currently must be
// invoked with `--no-configuration-cache` (documented in dev/README.md). Revisit when
// the Flyway plugin adds config-cache support, or migrate to invoking the Flyway Java
// API via a custom task that holds only services + inputs (no Project reference).
// Tracking: https://github.com/flyway/flyway/issues  (no specific issue filed yet)
flyway {
    url = providers.environmentVariable("DB_URL").orNull
    user = providers.environmentVariable("DB_USER").orNull
    password = providers.environmentVariable("DB_PASSWORD").orNull
    locations = arrayOf("classpath:db/migration")
}
