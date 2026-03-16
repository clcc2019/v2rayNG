package com.xray.ang.contracts

import com.xray.ang.dto.ProfileItem

interface MainAdapterListener :BaseAdapterListener {

    fun onEdit(guid: String, position: Int, profile: ProfileItem)

    fun onSelectServer(guid: String)

    fun onTestDelay(guid: String, position: Int)

    fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean)

}
