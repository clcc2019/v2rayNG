package com.v2ray.ang.ui

import android.annotation.SuppressLint
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.v2ray.ang.dto.GroupMapItem

/**
 * Pager adapter for subscription groups.
 */
class GroupPagerAdapter(activity: FragmentActivity, var groups: List<GroupMapItem>) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = groups.size
    override fun createFragment(position: Int) = GroupServerFragment.newInstance(groups[position].id)
    override fun getItemId(position: Int): Long = groups[position].id.hashCode().toLong()
    override fun containsItem(itemId: Long): Boolean = groups.any { it.id.hashCode().toLong() == itemId }

    fun hasSameStructure(groups: List<GroupMapItem>): Boolean {
        return this.groups.size == groups.size && this.groups.zip(groups).all { (old, new) -> old.id == new.id }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun update(groups: List<GroupMapItem>): Boolean {
        val sameStructure = hasSameStructure(groups)
        this.groups = groups
        if (!sameStructure) {
            notifyDataSetChanged()
        }
        return !sameStructure
    }
}
