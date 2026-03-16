package com.xray.ang.data.repository

import com.xray.ang.dto.RulesetItem
import com.xray.ang.handler.SettingsManager

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
