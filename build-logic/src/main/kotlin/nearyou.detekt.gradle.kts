plugins {
    id("io.gitlab.arturbosch.detekt")
}

// Runs the nearyou-custom invariant ruleset (:lint:detekt-rules) over JVM library modules.
// Mirrors the `nearyou.ktor` detekt block: builtin rulesets disabled, custom rules only,
// main sources only (tests are validation scaffolding — they exercise raw-table SQL on
// purpose). Config lives at the repo root so all applying modules share one rule list;
// see config/detekt/invariants.yml for the activation contract.
detekt {
    buildUponDefaultConfig = false
    allRules = false
    disableDefaultRuleSets = true
    config.setFrom(files("$rootDir/config/detekt/invariants.yml"))
    source.setFrom(files("src/main/kotlin"))
}

dependencies {
    "detektPlugins"(project(":lint:detekt-rules"))
}

tasks.named("check") {
    dependsOn("detekt")
}
