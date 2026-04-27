package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Registers the project's custom ruleset. Wired via META-INF/services so Detekt picks
 * it up automatically when this JAR is on the `detektPlugins` classpath.
 */
class NearYouRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = RULE_SET_ID

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                RawFromPostsRule(config),
                BlockExclusionJoinRule(config),
                CoordinateJitterRule(config),
                RateLimitTtlRule(config),
                RedisHashTagRule(config),
                RawXForwardedForRule(config),
            ),
        )

    companion object {
        const val RULE_SET_ID: String = "nearyou"
    }
}
