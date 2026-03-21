package com.xray.ang.handler

import com.xray.ang.AppConfig
import com.xray.ang.dto.ConfigResult
import com.xray.ang.dto.RulesetItem
import java.util.LinkedHashMap

internal data class RoutingDomainBuckets(
    val proxy: ArrayList<String> = arrayListOf(),
    val direct: ArrayList<String> = arrayListOf(),
    val blocked: ArrayList<String> = arrayListOf(),
)

private const val DOMAIN_MATCHER_PREFIX = "domain:"
private val ROUTING_DOMAIN_MATCHER_PREFIXES = listOf(
    DOMAIN_MATCHER_PREFIX,
    "geosite:",
    "full:",
    "regexp:",
    "keyword:",
    "ext:"
)

private val routingDomainBucketsCacheLock = Any()
@Volatile
private var cachedRoutingDomainBucketsSignature: Int? = null
private var cachedRoutingDomainBuckets: RoutingDomainBuckets? = null

internal fun buildRoutingDomainBuckets(rulesetItems: List<RulesetItem>?): RoutingDomainBuckets {
    return buildRoutingDomainBuckets(rulesetItems, rulesetItems?.hashCode() ?: 0)
}

internal fun buildRoutingDomainBuckets(rulesetItems: List<RulesetItem>?, signature: Int): RoutingDomainBuckets {
    synchronized(routingDomainBucketsCacheLock) {
        if (cachedRoutingDomainBucketsSignature == signature) {
            cachedRoutingDomainBuckets?.let(::copyRoutingDomainBuckets)?.let { return it }
        }
    }

    val buckets = RoutingDomainBuckets()
    rulesetItems?.forEach { key ->
        if (key.enabled && !key.domain.isNullOrEmpty()) {
            key.domain?.forEach { domain ->
                normalizeRoutingDomainMatcher(domain)?.let {
                    when (key.outboundTag) {
                        AppConfig.TAG_PROXY -> buckets.proxy.add(it)
                        AppConfig.TAG_DIRECT -> buckets.direct.add(it)
                        AppConfig.TAG_BLOCKED -> buckets.blocked.add(it)
                    }
                }
            }
        }
    }
    synchronized(routingDomainBucketsCacheLock) {
        cachedRoutingDomainBucketsSignature = signature
        cachedRoutingDomainBuckets = copyRoutingDomainBuckets(buckets)
    }
    return buckets
}

private fun copyRoutingDomainBuckets(buckets: RoutingDomainBuckets): RoutingDomainBuckets {
    return RoutingDomainBuckets(
        proxy = ArrayList(buckets.proxy),
        direct = ArrayList(buckets.direct),
        blocked = ArrayList(buckets.blocked)
    )
}

private fun normalizeRoutingDomainMatcher(value: String): String? {
    val matcher = value.trim()
    if (matcher.isEmpty() || matcher == AppConfig.GEOSITE_PRIVATE) {
        return null
    }
    return if (ROUTING_DOMAIN_MATCHER_PREFIXES.any(matcher::startsWith)) {
        matcher
    } else {
        "$DOMAIN_MATCHER_PREFIX$matcher"
    }
}

internal fun getConfigRelevantSettingsSnapshot(): Map<String, Any?> {
    return linkedMapOf(
        AppConfig.PREF_DELAY_TEST_URL to MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL),
        AppConfig.PREF_DNS_HOSTS to MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS),
        AppConfig.PREF_FAKE_DNS_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED, false),
        AppConfig.PREF_FRAGMENT_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false),
        AppConfig.PREF_FRAGMENT_INTERVAL to MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_INTERVAL),
        AppConfig.PREF_FRAGMENT_LENGTH to MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_LENGTH),
        AppConfig.PREF_FRAGMENT_PACKETS to MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_PACKETS),
        AppConfig.PREF_LOCAL_DNS_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false),
        AppConfig.PREF_LOGLEVEL to MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL),
        AppConfig.PREF_MUX_CONCURRENCY to MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY),
        AppConfig.PREF_MUX_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false),
        AppConfig.PREF_MUX_XUDP_CONCURRENCY to MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY),
        AppConfig.PREF_MUX_XUDP_QUIC to MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_QUIC),
        AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD to MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "0"),
        AppConfig.PREF_PREFER_IPV6 to MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false),
        AppConfig.PREF_PROXY_SHARING to MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false),
        AppConfig.PREF_ROUTE_ONLY_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false),
        AppConfig.PREF_ROUTING_DOMAIN_STRATEGY to MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY),
        AppConfig.PREF_SNIFFING_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true),
        AppConfig.PREF_METRICS_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_METRICS_ENABLED, false),
        AppConfig.PREF_SPEED_ENABLED to MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED, false),
        AppConfig.PREF_VPN_BYPASS_LAN to MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN)
    )
}

internal fun getCachedConfigResult(
    generatedConfigCacheLock: Any,
    generatedConfigCache: LinkedHashMap<String, ConfigResult>,
    cacheKey: String
): ConfigResult? {
    return synchronized(generatedConfigCacheLock) {
        generatedConfigCache[cacheKey]?.copy()
    }
}

internal fun putCachedConfigResult(
    generatedConfigCacheLock: Any,
    generatedConfigCache: LinkedHashMap<String, ConfigResult>,
    cacheKey: String,
    result: ConfigResult
) {
    synchronized(generatedConfigCacheLock) {
        generatedConfigCache[cacheKey] = result.copy()
    }
}
