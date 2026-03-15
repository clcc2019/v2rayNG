package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.data.repository.DefaultRoutingRulesRepository
import com.v2ray.ang.data.repository.RoutingRulesRepository
import com.v2ray.ang.dto.RulesetItem
import java.util.Collections

class RoutingSettingsViewModel(
    private val routingRulesRepository: RoutingRulesRepository
) : ViewModel() {
    constructor() : this(DefaultRoutingRulesRepository)

    private val rulesets: MutableList<RulesetItem> = mutableListOf()

    fun getAll(): List<RulesetItem> = rulesets.toList()

    fun reload() {
        rulesets.clear()
        rulesets.addAll(routingRulesRepository.getAll())
    }

    fun update(position: Int, item: RulesetItem) {
        if (position in rulesets.indices) {
            rulesets[position] = item
            routingRulesRepository.save(position, item)
        }
    }

    fun swap(fromPosition: Int, toPosition: Int) {
        if (fromPosition in rulesets.indices && toPosition in rulesets.indices) {
            Collections.swap(rulesets, fromPosition, toPosition)
            routingRulesRepository.swap(fromPosition, toPosition)
        }
    }
}
