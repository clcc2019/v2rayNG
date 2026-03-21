package com.xray.ang.handler

import com.xray.ang.AppConfig
import com.xray.ang.dto.RulesetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigBuildSupportTest {

    @Test
    fun buildRoutingDomainBuckets_acceptsPlainDomainsAndPreservesMatchers() {
        val rulesets = listOf(
            RulesetItem(
                outboundTag = AppConfig.TAG_PROXY,
                domain = listOf(
                    "openai.com",
                    "domain:chatgpt.com",
                    "full:claude.ai",
                    AppConfig.GEOSITE_PRIVATE
                )
            ),
            RulesetItem(
                outboundTag = AppConfig.TAG_DIRECT,
                domain = listOf("geosite:cn")
            ),
            RulesetItem(
                outboundTag = AppConfig.TAG_BLOCKED,
                domain = listOf("x.ai")
            )
        )

        val buckets = buildRoutingDomainBuckets(rulesets)

        assertEquals(
            arrayListOf("domain:openai.com", "domain:chatgpt.com", "full:claude.ai"),
            buckets.proxy
        )
        assertEquals(arrayListOf("geosite:cn"), buckets.direct)
        assertEquals(arrayListOf("domain:x.ai"), buckets.blocked)
    }

    @Test
    fun buildLiteLearnedProxyRule_normalizesAndFiltersDomains() {
        val rule = buildLiteLearnedProxyRule(
            listOf(
                "OpenAI.com",
                "domain:chatgpt.com",
                "full:claude.ai",
                "keyword:openai",
                "geoip:cn",
                " "
            )
        )

        requireNotNull(rule)
        assertEquals(LITE_LEARNED_PROXY_RULE_REMARK, rule.remarks)
        assertEquals(
            listOf("openai.com", "chatgpt.com", "full:claude.ai"),
            rule.domain
        )
        assertEquals(AppConfig.TAG_PROXY, rule.outboundTag)
    }

    @Test
    fun buildLiteLearnedProxyRule_returnsNullWhenNothingIsUsable() {
        val rule = buildLiteLearnedProxyRule(listOf("geoip:cn", "keyword:ai", ""))

        assertNull(rule)
    }

    @Test
    fun buildEffectiveRoutingRulesets_appendsLearnedRuleOnlyForLiteMode() {
        val baseRules = listOf(
            RulesetItem(remarks = "Final direct", outboundTag = AppConfig.TAG_DIRECT, port = "0-65535")
        )

        val effective = buildEffectiveRoutingRulesets(
            baseRulesets = baseRules,
            learnedLiteProxyDomains = listOf("openai.com", "claude.ai"),
            liteDirectRoutingActive = true
        )
        val untouched = buildEffectiveRoutingRulesets(
            baseRulesets = baseRules,
            learnedLiteProxyDomains = listOf("openai.com"),
            liteDirectRoutingActive = false
        )

        assertEquals(2, effective.size)
        assertEquals(LITE_LEARNED_PROXY_RULE_REMARK, effective.last().remarks)
        assertEquals(baseRules, untouched)
    }
}
