package com.xray.ang.ui

import android.annotation.SuppressLint
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.xray.ang.dto.GroupMapItem

/**
 * Pager adapter for subscription groups.
 */
class GroupPagerAdapter(activity: FragmentActivity, var groups: List<GroupMapItem>) : FragmentStateAdapter(activity) {
    data class UpdateResult(
        val structureChanged: Boolean,
        val contentChanged: Boolean
    )

    override fun getItemCount(): Int = groups.size
    override fun createFragment(position: Int) = GroupServerFragment.newInstance(groups[position].id)
    override fun getItemId(position: Int): Long = groups[position].id.hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean = groups.any { it.id.hashCode().toLong() == itemId }

    fun hasSameStructure(groups: List<GroupMapItem>): Boolean {
        return this.groups.size == groups.size && this.groups.zip(groups).all { (old, new) -> old.id == new.id }
    }

    private fun hasSameContent(groups: List<GroupMapItem>): Boolean {
        return this.groups.size == groups.size && this.groups.zip(groups).all { (old, new) ->
            old.id == new.id && old.remarks == new.remarks && old.count == new.count
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun update(groups: List<GroupMapItem>): UpdateResult {
        val sameStructure = hasSameStructure(groups)
        val sameContent = sameStructure && hasSameContent(groups)
        this.groups = groups
        if (!sameStructure) {
            notifyDataSetChanged()
        }
        return UpdateResult(
            structureChanged = !sameStructure,
            contentChanged = !sameContent
        )
    }
}
