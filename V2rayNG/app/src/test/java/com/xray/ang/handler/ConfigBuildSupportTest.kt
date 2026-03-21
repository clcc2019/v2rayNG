package com.xray.ang.handler

import com.xray.ang.AppConfig
import com.xray.ang.dto.RulesetItem
import org.junit.Assert.assertEquals
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
}
