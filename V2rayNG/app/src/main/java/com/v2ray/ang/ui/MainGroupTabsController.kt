package com.v2ray.ang.ui

import android.animation.TimeInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.ItemTabGroupBinding
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.viewmodel.MainViewModel

class MainGroupTabsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val mainViewModel: MainViewModel,
    private val motionInterpolator: TimeInterpolator
) {
    private data class GroupTabViewState(
        var group: GroupMapItem,
        val surfaceView: View,
        val labelView: TextView,
        val countView: TextView,
        var lastSelected: Boolean? = null,
        var lastRemarks: String? = null,
        var lastCount: Int? = null
    )

    private val groupPagerAdapter = GroupPagerAdapter(activity, emptyList())
    private var tabMediator: TabLayoutMediator? = null
    private var isGroupTabHidden = false
    private var lastRenderedGroupCount = -1

    private val groupTabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (binding.viewPager.currentItem != tab.position) {
                binding.viewPager.setCurrentItem(tab.position, false)
            }
            syncGroupTabSelection()
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = syncGroupTabSelection()

        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    fun initialize() {
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true
    }

    fun setupGroupTabs() {
        mainViewModel.loadSubscriptions(activity.applicationContext)
    }

    fun renderGroupTabs(groups: List<GroupMapItem>) {
        if (groups.isEmpty()) {
            groupPagerAdapter.update(emptyList())
            tabMediator?.detach()
            tabMediator = null
            lastRenderedGroupCount = 0
            binding.tabGroup.removeOnTabSelectedListener(groupTabSelectedListener)
            binding.tabGroup.isVisible = false
            binding.cardTabGroup.isVisible = false
            setGroupTabVisible(false, immediate = true)
            updateGroupTabContentInset()
            activity.refreshConnectionCard()
            return
        }

        val updateResult = groupPagerAdapter.update(groups)
        configureGroupTabLayout(groups.size)
        val targetOffscreenLimit = maxOf(1, minOf(2, groups.size))
        if (binding.viewPager.offscreenPageLimit != targetOffscreenLimit) {
            binding.viewPager.offscreenPageLimit = targetOffscreenLimit
        }

        if (updateResult.structureChanged || tabMediator == null) {
            tabMediator?.detach()
            tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
                groupPagerAdapter.groups.getOrNull(position)?.let {
                    tab.customView = createGroupTabView(it)
                    tab.tag = it.id
                }
            }.also { it.attach() }

            binding.tabGroup.removeOnTabSelectedListener(groupTabSelectedListener)
            binding.tabGroup.addOnTabSelectedListener(groupTabSelectedListener)
            binding.tabGroup.post { applyCompactGroupTabHeight() }
        } else if (updateResult.contentChanged) {
            updateExistingGroupTabs(groups)
        }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        if (binding.viewPager.currentItem != targetIndex) {
            binding.viewPager.setCurrentItem(targetIndex, false)
        }
        syncGroupTabSelection()

        binding.tabGroup.isVisible = groups.size > 1
        binding.cardTabGroup.isVisible = groups.size > 1
        setGroupTabVisible(groups.size > 1, immediate = true)
        binding.cardTabGroup.post { updateGroupTabContentInset() }
        activity.refreshConnectionCard()
        lastRenderedGroupCount = groups.size
    }

    fun onServerListScrolled(dy: Int, canScrollUp: Boolean) {
        if (!binding.cardTabGroup.isVisible) return
        when {
            !canScrollUp -> setGroupTabVisible(true)
            dy > 6 -> setGroupTabVisible(false)
            dy < -6 -> setGroupTabVisible(true)
        }
    }

    fun scrollCurrentServerListToTop(animate: Boolean = true) {
        val position = binding.viewPager.currentItem
        if (position !in 0 until groupPagerAdapter.itemCount) return
        val itemId = groupPagerAdapter.getItemId(position)
        val tag = "f$itemId"
        val fragment = activity.supportFragmentManager.findFragmentByTag(tag) as? GroupServerFragment
        fragment?.scrollToTop(animate = animate)
    }

    fun onDestroy() {
        tabMediator?.detach()
    }

    private fun configureGroupTabLayout(groupCount: Int) {
        val useAdaptiveWidth = groupCount in 1..3
        val targetTabMode = TabLayout.MODE_SCROLLABLE
        val targetTabGravity = TabLayout.GRAVITY_CENTER
        if (binding.tabGroup.tabMode != targetTabMode) {
            binding.tabGroup.tabMode = targetTabMode
        }
        if (binding.tabGroup.tabGravity != targetTabGravity) {
            binding.tabGroup.tabGravity = targetTabGravity
        }

        val cardHeight = activity.resources.getDimensionPixelSize(R.dimen.view_height_dp36) +
            activity.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp2)
        val cardTopMargin = activity.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp2)
        val cardLayoutParams = binding.cardTabGroup.layoutParams as CoordinatorLayout.LayoutParams
        val sideMargin = activity.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16)
        val targetWidth = if (useAdaptiveWidth) CoordinatorLayout.LayoutParams.WRAP_CONTENT else CoordinatorLayout.LayoutParams.MATCH_PARENT
        val targetHorizontalMargin = if (useAdaptiveWidth) 0 else sideMargin
        if (cardLayoutParams.width != targetWidth
            || cardLayoutParams.height != cardHeight
            || cardLayoutParams.topMargin != cardTopMargin
            || cardLayoutParams.marginStart != targetHorizontalMargin
            || cardLayoutParams.marginEnd != targetHorizontalMargin
        ) {
            cardLayoutParams.width = targetWidth
            cardLayoutParams.height = cardHeight
            cardLayoutParams.topMargin = cardTopMargin
            cardLayoutParams.marginStart = targetHorizontalMargin
            cardLayoutParams.marginEnd = targetHorizontalMargin
            binding.cardTabGroup.layoutParams = cardLayoutParams
        }

        binding.tabGroup.layoutParams = binding.tabGroup.layoutParams.apply {
            width = if (useAdaptiveWidth) CoordinatorLayout.LayoutParams.WRAP_CONTENT else CoordinatorLayout.LayoutParams.MATCH_PARENT
            height = cardHeight
        }
        binding.tabGroup.minimumHeight = 0
        if (lastRenderedGroupCount != groupCount) {
            binding.tabGroup.requestLayout()
        }
    }

    private fun createGroupTabView(group: GroupMapItem): View {
        val tabBinding = ItemTabGroupBinding.inflate(LayoutInflater.from(activity), binding.tabGroup, false)
        val tabState = GroupTabViewState(
            group = group,
            surfaceView = tabBinding.layoutTabSurface,
            labelView = tabBinding.tvTabLabel,
            countView = tabBinding.tvTabCount
        )
        bindGroupTabViewState(tabState, selected = false)
        tabBinding.root.tag = tabState
        return tabBinding.root
    }

    private fun syncGroupTabSelection() {
        for (index in 0 until binding.tabGroup.tabCount) {
            val tab = binding.tabGroup.getTabAt(index) ?: continue
            val customView = tab.customView ?: continue
            val isSelected = tab.isSelected
            customView.isSelected = isSelected
            customView.animate().cancel()
            customView.scaleX = 1f
            customView.scaleY = 1f

            val tabState = customView.tag as? GroupTabViewState ?: continue
            bindGroupTabViewState(tabState, selected = isSelected)
        }
    }

    private fun updateExistingGroupTabs(groups: List<GroupMapItem>) {
        groups.forEachIndexed { index, group ->
            val tab = binding.tabGroup.getTabAt(index) ?: return@forEachIndexed
            val customView = tab.customView ?: return@forEachIndexed
            val tabState = customView.tag as? GroupTabViewState ?: return@forEachIndexed
            tabState.group = group
            bindGroupTabViewState(tabState, selected = tab.isSelected)
        }
    }

    private fun applyCompactGroupTabHeight() {
        val compactHeight = activity.resources.getDimensionPixelSize(R.dimen.view_height_dp32)
        binding.tabGroup.minimumHeight = compactHeight

        val slidingTabIndicator = binding.tabGroup.getChildAt(0) as? ViewGroup ?: return
        slidingTabIndicator.clipToPadding = false
        slidingTabIndicator.clipChildren = false
        for (index in 0 until slidingTabIndicator.childCount) {
            val tabView = slidingTabIndicator.getChildAt(index)
            tabView.minimumHeight = compactHeight
            tabView.layoutParams = tabView.layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = compactHeight
            }
            tabView.setPadding(0, 0, 0, 0)
            tabView.requestLayout()
        }
    }

    private fun bindGroupTabViewState(tabState: GroupTabViewState, selected: Boolean) {
        tabState.surfaceView.isSelected = selected
        if (tabState.lastRemarks != tabState.group.remarks) {
            tabState.labelView.text = tabState.group.remarks
            tabState.lastRemarks = tabState.group.remarks
        }
        tabState.labelView.setTextColor(
            ContextCompat.getColor(
                activity,
                if (selected) R.color.md_theme_onSurface else R.color.md_theme_onSurfaceVariant
            )
        )
        tabState.labelView.alpha = if (selected) 1f else 0.88f

        val hasCount = tabState.group.count > 0
        tabState.countView.isVisible = hasCount
        if (hasCount) {
            if (tabState.lastCount != tabState.group.count) {
                tabState.countView.text = tabState.group.count.toString()
                tabState.lastCount = tabState.group.count
            }
            tabState.countView.setBackgroundResource(
                if (selected) R.drawable.bg_group_count_badge_selected else R.drawable.bg_group_count_badge
            )
            tabState.countView.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (selected) R.color.md_theme_onPrimary else R.color.md_theme_onSurfaceVariant
                )
            )
            tabState.countView.alpha = if (selected) 1f else 0.92f
        }

        if (tabState.lastSelected != selected) {
            tabState.lastSelected = selected
            tabState.surfaceView.animate().cancel()
            if (selected) {
                tabState.surfaceView.scaleX = 0.985f
                tabState.surfaceView.scaleY = 0.985f
                tabState.surfaceView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(motionInterpolator)
                    .start()
            } else {
                tabState.surfaceView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .setInterpolator(motionInterpolator)
                    .start()
            }
        }
    }

    private fun setGroupTabVisible(visible: Boolean, immediate: Boolean = false) {
        if (visible == !isGroupTabHidden && !immediate) return
        isGroupTabHidden = !visible

        val targetAlpha = if (visible) 1f else 0f
        val targetTranslationY = if (visible) 0f else -binding.cardTabGroup.height.coerceAtLeast(1) * 0.45f
        if (immediate) {
            binding.cardTabGroup.alpha = targetAlpha
            binding.cardTabGroup.translationY = targetTranslationY
            return
        }

        binding.cardTabGroup.animate()
            .cancel()
        binding.cardTabGroup.animate()
            .alpha(targetAlpha)
            .translationY(targetTranslationY)
            .setDuration(MEDIUM_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun updateGroupTabContentInset() {
        val listGap = activity.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp4)
        val cardTopMargin = (binding.cardTabGroup.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val topPadding = if (binding.cardTabGroup.isVisible) {
            cardTopMargin + binding.cardTabGroup.height + listGap
        } else {
            listGap
        }
        binding.viewPager.updatePadding(top = topPadding)
    }

    companion object {
        private const val MEDIUM_ANIMATION_DURATION = 180L
    }
}
