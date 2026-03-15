package com.v2ray.ang.data.repository

import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.handler.SettingsManager

interface RoutingRulesRepository {
    fun getAll(): List<RulesetItem>
    fun save(position: Int, item: RulesetItem)
    fun swap(fromPosition: Int, toPosition: Int)
}

object DefaultRoutingRulesRepository : RoutingRulesRepository {
    override fun getAll(): List<RulesetItem> = SettingsManager.getRoutingRulesets()

    override fun save(position: Int, item: RulesetItem) {
        SettingsManager.saveRoutingRuleset(position, item)
    }

    override fun swap(fromPosition: Int, toPosition: Int) {
        SettingsManager.swapRoutingRuleset(fromPosition, toPosition)
    }
}
