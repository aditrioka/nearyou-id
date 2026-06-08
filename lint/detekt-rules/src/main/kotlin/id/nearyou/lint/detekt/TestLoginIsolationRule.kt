package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Forbids "test-login" auth-bypass symbols — any class, object, or named-function declaration whose
 * name contains the CamelCase `TestLogin` token — outside the dev / staging-debug Android source sets.
 *
 * The test-login deep-link activities (`DevTestLoginActivity`, `StagingTestLoginActivity`) inject
 * a pre-minted, server-signed session straight into the `SecureTokenStore`, bypassing interactive
 * Google/Apple sign-in. The PRIMARY guarantee they never reach a user-facing build is **Android
 * flavor source-set isolation**: `src/dev/` compiles only into the dev variant and
 * `src/stagingDebug/` only into the staging (debug) variant — both are physically absent from
 * every production APK (and from `stagingRelease`). This rule is defense-in-depth: it fails the
 * build if such a symbol is ever DECLARED in a shipping source set (`commonMain` / `androidMain`
 * / `iosMain` / `production` / `staging` flavor-wide / `stagingRelease`), where it could leak into
 * a distributable.
 *
 * Allowed locations (the only two):
 *  - any file under `.../src/dev/...`          (dev flavor — backend is localhost-only).
 *  - any file under `.../src/stagingDebug/...`  (staging flavor, DEBUG build type only).
 *
 * `src/staging/` (flavor-wide) is deliberately NOT allowlisted: a test-login symbol there would
 * also land in `stagingRelease`, defeating the staging-debug-only decision (operator sign-off in
 * `mobile/app/maestro/PHASE-3-staging-test-login.md`). Unlike [RawFromPostsRule] there is
 * intentionally NO annotation or package escape hatch — there is no legitimate reason to declare
 * a test-login symbol in a shipping source set, so an escape hatch would only erode the guard.
 */
class TestLoginIsolationRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Test-login auth-bypass symbols (name contains `TestLogin`) may only be declared " +
                    "under `src/dev/` or `src/stagingDebug/` — they MUST NOT ship in a production " +
                    "or staging-release build. Move the declaration into one of those Android " +
                    "source sets. See mobile/app/maestro/PHASE-3-staging-test-login.md.",
            debt = Debt.TWENTY_MINS,
        )

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        reportIfForbidden(classOrObject)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        reportIfForbidden(function)
    }

    private fun reportIfForbidden(declaration: KtNamedDeclaration) {
        val name = declaration.name ?: return
        if (!TEST_LOGIN_PATTERN.containsMatchIn(name)) return
        if (isAllowedPath(declaration.containingKtFile)) return
        report(
            CodeSmell(
                issue,
                Entity.from(declaration.nameIdentifier ?: declaration),
                issue.description,
            ),
        )
    }

    private fun isAllowedPath(file: KtFile): Boolean {
        val normalized = file.virtualFilePath.replace('\\', '/')
        return ALLOWED_PATH_SEGMENTS.any { it in normalized }
    }

    companion object {
        const val RULE_ID: String = "TestLoginIsolationRule"

        // Matches the CamelCase `TestLogin` token (capital `T`): catches `DevTestLoginActivity`,
        // `StagingTestLoginActivity`, `TestLoginHelper`, and a camelCase `bootstrapTestLoginSession()`.
        // It deliberately does NOT match mid-word lowercase like `latestLogin` / `greatestLogin`
        // (no capital-`T` `Test` segment), which would otherwise be a false positive. `[Ll]` tolerates
        // `Testlogin` as well as `TestLogin`.
        private val TEST_LOGIN_PATTERN: Regex = Regex("Test[Ll]ogin")

        // The ONLY source sets that may host a test-login symbol. NB: `/src/staging/` (flavor-wide)
        // is intentionally absent so the rule also enforces the staging-debug-only decision.
        private val ALLOWED_PATH_SEGMENTS: List<String> =
            listOf("/src/dev/", "/src/stagingDebug/")
    }
}
