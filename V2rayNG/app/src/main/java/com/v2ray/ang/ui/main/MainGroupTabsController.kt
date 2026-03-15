package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
        val indicatorView: View,
        var lastSelected: Boolean? = null,
        var lastRemarks: String? = null,
        var lastCount: Int? = null
    )

    private val groupPagerAdapter = GroupPagerAdapter(activity, emptyList())
    private var tabMediator: TabLayoutMediator? = null
    private var isGroupTabHidden = false
    private var lastRenderedGroupCount = -1
    private var accumulatedScrollDy = 0
    private var groupTabSlotAnimator: ValueAnimator? = null
    private val hideThresholdPx by lazy {
        (activity.resources.displayMetrics.density * 24f).toInt()
    }
    private val showThresholdPx by lazy {
        (activity.resources.displayMetrics.density * 12f).toInt()
    }
    private val expandedSlotHeightPx by lazy {
        activity.resources.getDimensionPixelSize(R.dimen.view_height_dp40)
    }

    private val groupTabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (binding.viewPager.currentItem != tab.position) {
                binding.viewPager.setCurrentItem(tab.position, true)
            }
            syncGroupTabSelection()
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = syncGroupTabSelection()

        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    fun initialize() {
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = false
    }

    fun setupGroupTabs() {
        mainViewModel.loadSubscriptions(activity.applicationContext)
    }

    fun renderGroupTabs(groups: List<GroupMapItem>) {
        if (groups.isEmpty()) {
            accumulatedScrollDy = 0
            groupPagerAdapter.update(emptyList())
            tabMediator?.detach()
            tabMediator = null
            lastRenderedGroupCount = 0
            binding.tabGroup.removeOnTabSelectedListener(groupTabSelectedListener)
            binding.tabGroup.isVisible = false
            binding.cardTabGroup.isVisible = false
            binding.layoutGroupTabSlot.isVisible = false
            setGroupTabVisible(false, immediate = true)
            activity.refreshConnectionCard()
            return
        }

        val updateResult = groupPagerAdapter.update(groups)
        accumulatedScrollDy = 0
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

        binding.layoutGroupTabSlot.isVisible = groups.size > 1
        binding.tabGroup.isVisible = groups.size > 1
        binding.cardTabGroup.isVisible = groups.size > 1
        setGroupTabVisible(groups.size > 1, immediate = true)
        activity.refreshConnectionCard()
        lastRenderedGroupCount = groups.size
    }

    fun onServerListScrolled(dy: Int, canScrollUp: Boolean) {
        if (!binding.cardTabGroup.isVisible) return
        accumulatedScrollDy = 0
        setGroupTabVisible(true)
    }

    fun scrollCurrentServerListToTop(animate: Boolean = true) {
        findCurrentFragment()?.scrollToTop(animate = animate)
    }

    fun notifyCurrentFragmentSearchUiChanged() {
        findCurrentFragment()?.onSearchUiChanged()
    }

    private fun findCurrentFragment(): GroupServerFragment? {
        val position = binding.viewPager.currentItem
        if (position !in 0 until groupPagerAdapter.itemCount) return null
        val itemId = groupPagerAdapter.getItemId(position)
        val tag = "f$itemId"
        return activity.supportFragmentManager.findFragmentByTag(tag) as? GroupServerFragment
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

        val cardHeight = activity.resources.getDimensionPixelSize(R.dimen.view_height_dp40)
        val cardLayoutParams = binding.cardTabGroup.layoutParams as ViewGroup.MarginLayoutParams
        val targetWidth = if (useAdaptiveWidth) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        if (cardLayoutParams.width != targetWidth
            || cardLayoutParams.height != cardHeight
        ) {
            cardLayoutParams.width = targetWidth
            cardLayoutParams.height = cardHeight
            binding.cardTabGroup.layoutParams = cardLayoutParams
        }

        binding.tabGroup.layoutParams = binding.tabGroup.layoutParams.apply {
            width = if (useAdaptiveWidth) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
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
            countView = tabBinding.tvTabCount,
            indicatorView = tabBinding.viewTabIndicator
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
        val compactHeight = activity.resources.getDimensionPixelSize(R.dimen.view_height_dp40)
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
        updateTabLabel(tabState, selected)
        updateTabCount(tabState, selected)
        tabState.indicatorView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        updateTabSelectionAnimation(tabState, selected)
    }

    private fun updateTabLabel(tabState: GroupTabViewState, selected: Boolean) {
        if (tabState.lastRemarks != tabState.group.remarks) {
            tabState.labelView.text = tabState.group.remarks
            tabState.lastRemarks = tabState.group.remarks
        }
        tabState.labelView.setTextColor(
            ContextCompat.getColor(
                activity,
                if (selected) R.color.color_home_on_surface else R.color.color_home_on_surface_muted
            )
        )
        tabState.labelView.alpha = if (selected) 1f else 0.9f
    }

    private fun updateTabCount(tabState: GroupTabViewState, selected: Boolean) {
        val hasCount = tabState.group.count > 0
        tabState.countView.isVisible = hasCount
        if (!hasCount) return
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
                if (selected) R.color.color_home_on_primary else R.color.color_home_on_surface_muted
            )
        )
        tabState.countView.alpha = if (selected) 1f else 0.94f
    }

    private fun updateTabSelectionAnimation(tabState: GroupTabViewState, selected: Boolean) {
        if (tabState.lastSelected == selected) return
        tabState.lastSelected = selected
        tabState.surfaceView.animate().cancel()
        if (selected) {
            tabState.surfaceView.alpha = 0.94f
            tabState.surfaceView.scaleX = 0.988f
            tabState.surfaceView.scaleY = 0.988f
            tabState.surfaceView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
            UiMotion.animateFocusShift(tabState.labelView, tabState.countView, translationOffsetDp = 4f, duration = MotionTokens.SHORT_ANIMATION_DURATION)
            tabState.indicatorView.alpha = 0.72f
            tabState.indicatorView.animate()
                .alpha(1f)
                .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        } else {
            tabState.indicatorView.animate().cancel()
            tabState.indicatorView.alpha = 0f
            tabState.surfaceView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        }
    }

    private fun setGroupTabVisible(visible: Boolean, immediate: Boolean = false) {
        if (visible == !isGroupTabHidden && !immediate) return
        isGroupTabHidden = !visible

        val targetAlpha = if (visible) 1f else 0f
        val targetTranslationY = if (visible) 0f else -binding.cardTabGroup.height.coerceAtLeast(1) * 0.45f
        val targetScale = if (visible) 1f else 0.985f
        binding.cardTabGroup.isClickable = visible
        binding.cardTabGroup.isFocusable = visible
        binding.tabGroup.isEnabled = visible
        binding.cardTabGroup.importantForAccessibility =
            if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (immediate) {
            groupTabSlotAnimator?.cancel()
            binding.layoutGroupTabSlot.isVisible = visible
            updateGroupTabSlotHeight(if (visible) expandedSlotHeightPx else 0)
            binding.cardTabGroup.alpha = targetAlpha
            binding.cardTabGroup.translationY = targetTranslationY
            binding.cardTabGroup.scaleX = targetScale
            binding.cardTabGroup.scaleY = targetScale
            return
        }

        if (visible) {
            binding.layoutGroupTabSlot.isVisible = true
        }
        groupTabSlotAnimator?.cancel()
        val startHeight = binding.layoutGroupTabSlot.layoutParams.height
        val endHeight = if (visible) expandedSlotHeightPx else 0
        groupTabSlotAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = MotionTokens.REVEAL_DURATION
            interpolator = motionInterpolator
            addUpdateListener { animator ->
                updateGroupTabSlotHeight(animator.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!visible) {
                        binding.layoutGroupTabSlot.isVisible = false
                    }
                    groupTabSlotAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    groupTabSlotAnimator = null
                }
            })
        }
        binding.cardTabGroup.animate()
            .cancel()
        binding.cardTabGroup.animate()
            .alpha(targetAlpha)
            .translationY(targetTranslationY)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(MotionTokens.REVEAL_DURATION)
            .setInterpolator(motionInterpolator)
            .start()
        groupTabSlotAnimator?.start()
    }

    private fun updateGroupTabSlotHeight(height: Int) {
        val layoutParams = binding.layoutGroupTabSlot.layoutParams
        if (layoutParams.height == height) return
        layoutParams.height = height
        binding.layoutGroupTabSlot.layoutParams = layoutParams
    }
}
