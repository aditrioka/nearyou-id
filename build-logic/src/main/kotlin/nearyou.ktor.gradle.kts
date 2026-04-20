plugins {
    id("nearyou.kotlin.jvm")
    id("io.ktor.plugin")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.flywaydb.flyway")
    id("io.gitlab.arturbosch.detekt")
    application
}

detekt {
    // Our rules are contributed via the RuleSetProvider shipped by :lint:detekt-rules.
    // We disable default rule sets because this change only introduces a single custom
    // rule; tuning the full Detekt baseline is a separate cleanup.
    buildUponDefaultConfig = false
    allRules = false
    disableDefaultRuleSets = true
    config.setFrom(files("config/detekt/detekt.yml"))
    // Tests are validation scaffolding — they SELECT from the raw `posts` table on purpose.
    // The rule protects business code; test sources are excluded.
    source.setFrom(files("src/main/kotlin"))
}

tasks.named("check") {
    dependsOn("detekt")
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
