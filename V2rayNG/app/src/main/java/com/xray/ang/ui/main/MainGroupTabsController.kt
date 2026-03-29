package com.xray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.xray.ang.R
import com.xray.ang.databinding.ActivityMainBinding
import com.xray.ang.databinding.ItemTabGroupBinding
import com.xray.ang.dto.GroupMapItem
import com.xray.ang.extension.toast
import com.xray.ang.viewmodel.MainViewModel

class MainGroupTabsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val mainViewModel: MainViewModel,
    private val motionInterpolator: TimeInterpolator
) {
    private data class GroupTabViewState(
        var group: GroupMapItem,
        val rootView: View,
        val surfaceView: View,
        val labelView: TextView,
        val editView: AppCompatEditText,
        val countView: TextView,
        var lastSelected: Boolean? = null,
        var lastRemarks: String? = null,
        var lastCount: Int? = null,
        var isEditing: Boolean = false,
        var draftRemarks: String? = null
    )

    private val groupPagerAdapter = GroupPagerAdapter(activity, emptyList())
    private var tabMediator: TabLayoutMediator? = null
    private var isGroupTabHidden = false
    private var lastRenderedGroupCount = -1
    private var groupTabSlotAnimator: ValueAnimator? = null
    private var hasAnimatedGroupTabReveal = false
    private var lastAnimatedPagePosition = -1
    private val groupHideScrollThresholdPx by lazy {
        (activity.resources.displayMetrics.density * 10f).toInt()
    }
    private val expandedSlotHeightPx by lazy {
        activity.resources.getDimensionPixelSize(R.dimen.view_height_dp32) +
            (activity.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp2) * 2)
    }
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (position == lastAnimatedPagePosition) return
            lastAnimatedPagePosition = position
            syncGroupTabSelection()
        }
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
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
    }

    fun setupGroupTabs(immediate: Boolean = false) {
        mainViewModel.loadSubscriptions(activity.applicationContext, immediate = immediate)
    }

    fun renderGroupTabs(groups: List<GroupMapItem>) {
        if (groups.isEmpty()) {
            groupPagerAdapter.update(emptyList())
            tabMediator?.detach()
            tabMediator = null
            lastRenderedGroupCount = 0
            hasAnimatedGroupTabReveal = false
            lastAnimatedPagePosition = -1
            binding.tabGroup.removeOnTabSelectedListener(groupTabSelectedListener)
            binding.tabGroup.isVisible = false
            binding.cardTabGroup.isVisible = false
            binding.layoutGroupTabSlot.isVisible = false
            setGroupTabVisible(false, immediate = true)
            activity.refreshConnectionCard()
            return
        }

        val updateResult = groupPagerAdapter.update(groups)
        configureGroupTabLayout(groups.size)
        val targetOffscreenLimit = 1
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
        lastAnimatedPagePosition = binding.viewPager.currentItem
        syncGroupTabSelection()

        binding.layoutGroupTabSlot.isVisible = groups.size > 1
        binding.tabGroup.isVisible = groups.size > 1
        binding.cardTabGroup.isVisible = groups.size > 1
        setGroupTabVisible(groups.size > 1, immediate = true)
        if (groups.size > 1) {
            animateGroupTabRevealIfNeeded()
        } else {
            hasAnimatedGroupTabReveal = false
        }
        activity.refreshConnectionCard()
        lastRenderedGroupCount = groups.size
    }

    fun scrollCurrentServerListToTop(animate: Boolean = true) {
        findCurrentFragment()?.scrollToTop(animate = animate)
    }

    fun notifyCurrentFragmentSearchUiChanged() {
        findCurrentFragment()?.onSearchUiChanged()
    }

    fun onServerListScrolled(dy: Int, canScrollUp: Boolean) {
        if (binding.tabGroup.tabCount <= 1) return
        when {
            !canScrollUp -> setGroupTabVisible(true)
            dy > groupHideScrollThresholdPx -> setGroupTabVisible(false)
            dy < -groupHideScrollThresholdPx -> setGroupTabVisible(true)
        }
    }

    private fun findCurrentFragment(): GroupServerFragment? {
        val position = binding.viewPager.currentItem
        if (position !in 0 until groupPagerAdapter.itemCount) return null
        val itemId = groupPagerAdapter.getItemId(position)
        val tag = "f$itemId"
        return activity.supportFragmentManager.findFragmentByTag(tag) as? GroupServerFragment
    }

    fun onDestroy() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        tabMediator?.detach()
    }

    fun resyncGroupTabVisibility() {
        val shouldShowTabs = binding.tabGroup.tabCount > 1 && !isGroupTabHidden
        groupTabSlotAnimator?.cancel()
        binding.cardTabGroup.animate().cancel()
        binding.layoutGroupTabSlot.isVisible = shouldShowTabs
        binding.tabGroup.isVisible = binding.tabGroup.tabCount > 1
        binding.cardTabGroup.isVisible = binding.tabGroup.tabCount > 1
        updateGroupTabSlotHeight(if (shouldShowTabs) expandedSlotHeightPx else 0)
        binding.cardTabGroup.alpha = if (shouldShowTabs) 1f else 0f
        binding.cardTabGroup.translationY =
            if (shouldShowTabs) 0f else -binding.cardTabGroup.height.coerceAtLeast(1) * 0.34f
        binding.cardTabGroup.scaleX = if (shouldShowTabs) 1f else 0.985f
        binding.cardTabGroup.scaleY = if (shouldShowTabs) 1f else 0.985f
        binding.cardTabGroup.isClickable = shouldShowTabs
        binding.cardTabGroup.isFocusable = shouldShowTabs
        binding.tabGroup.isEnabled = shouldShowTabs
        binding.cardTabGroup.importantForAccessibility =
            if (shouldShowTabs) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.requestApplyInsets(binding.mainContent)
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

        val cardHeight = activity.resources.getDimensionPixelSize(R.dimen.view_height_dp32)
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
            rootView = tabBinding.root,
            surfaceView = tabBinding.layoutTabSurface,
            labelView = tabBinding.tvTabLabel,
            editView = tabBinding.etTabLabel,
            countView = tabBinding.tvTabCount
        )
        bindGroupTabViewState(tabState, selected = false)
        tabBinding.root.tag = tabState
        if (group.id.isNotBlank()) {
            val renameListener = View.OnLongClickListener {
                startInlineRename(tabState)
                true
            }
            tabBinding.root.setOnLongClickListener(renameListener)
            tabBinding.tvTabLabel.setOnLongClickListener(renameListener)
        }
        val clickListener = View.OnClickListener {
            if (!tabState.isEditing) {
                selectGroupTab(tabState.group.id)
            }
        }
        tabBinding.root.setOnClickListener(clickListener)
        tabBinding.tvTabLabel.setOnClickListener(clickListener)
        tabState.editView.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && tabState.isEditing) {
                finishInlineRename(tabState, save = true)
            }
        }
        tabState.editView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishInlineRename(tabState, save = true)
                true
            } else {
                false
            }
        }
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
            val layoutParams = (tabView.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, compactHeight)
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams.height = compactHeight
            val balancedTabGap = activity.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp6)
            layoutParams.marginStart = balancedTabGap
            layoutParams.marginEnd = balancedTabGap
            tabView.layoutParams = layoutParams
            tabView.setPadding(0, 0, 0, 0)
            tabView.requestLayout()
        }
    }

    private fun bindGroupTabViewState(tabState: GroupTabViewState, selected: Boolean) {
        tabState.surfaceView.isSelected = selected
        updateTabLabel(tabState, selected)
        updateTabEditState(tabState)
        updateTabCount(tabState, selected)
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
        tabState.countView.isVisible = hasCount && !tabState.isEditing
        if (!hasCount) return
        val targetAlpha = if (selected) 1f else 0.94f
        val countChanged = tabState.lastCount != null && tabState.lastCount != tabState.group.count
        if (tabState.lastCount != tabState.group.count) {
            if (countChanged) {
                UiMotion.animateTextChange(
                    textView = tabState.countView,
                    newText = tabState.group.count.toString(),
                    settledAlpha = targetAlpha,
                    translationOffsetDp = 3f,
                    duration = MotionTokens.SHORT_ANIMATION_DURATION
                )
            } else {
                tabState.countView.text = tabState.group.count.toString()
                tabState.countView.alpha = targetAlpha
                tabState.countView.translationY = 0f
            }
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
        if (!countChanged) {
            tabState.countView.alpha = targetAlpha
        }
        if (countChanged) {
            UiMotion.animatePulse(tabState.countView, pulseScale = 1.05f, duration = MotionTokens.PULSE_QUICK)
        }
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
        } else {
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

        val transitionDuration = if (visible) {
            MotionTokens.MEDIUM_ANIMATION_DURATION
        } else {
            MotionTokens.SHORT_ANIMATION_DURATION
        }
        val targetAlpha = if (visible) 1f else 0f
        val targetTranslationY = if (visible) 0f else -binding.cardTabGroup.height.coerceAtLeast(1) * 0.34f
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
            ViewCompat.requestApplyInsets(binding.mainContent)
            return
        }

        if (visible) {
            binding.layoutGroupTabSlot.isVisible = true
        }
        groupTabSlotAnimator?.cancel()
        val startHeight = binding.layoutGroupTabSlot.layoutParams.height
        val endHeight = if (visible) expandedSlotHeightPx else 0
        groupTabSlotAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = transitionDuration
            interpolator = motionInterpolator
            addUpdateListener { animator ->
                updateGroupTabSlotHeight(animator.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!visible) {
                        binding.layoutGroupTabSlot.isVisible = false
                    }
                    ViewCompat.requestApplyInsets(binding.mainContent)
                    groupTabSlotAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    ViewCompat.requestApplyInsets(binding.mainContent)
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
            .setDuration(transitionDuration)
            .setInterpolator(motionInterpolator)
            .start()
        groupTabSlotAnimator?.start()
    }

    private fun animateGroupTabRevealIfNeeded() {
        if (hasAnimatedGroupTabReveal) return
        binding.layoutGroupTabSlot.post {
            if (hasAnimatedGroupTabReveal || !binding.cardTabGroup.isVisible) {
                return@post
            }
            UiMotion.animateEntrance(
                view = binding.cardTabGroup,
                translationOffsetDp = 8f,
                duration = MotionTokens.MEDIUM_ANIMATION_DURATION
            )
            hasAnimatedGroupTabReveal = true
        }
    }

    private fun updateGroupTabSlotHeight(height: Int) {
        val layoutParams = binding.layoutGroupTabSlot.layoutParams
        if (layoutParams.height == height) return
        layoutParams.height = height
        binding.layoutGroupTabSlot.layoutParams = layoutParams
        ViewCompat.requestApplyInsets(binding.mainContent)
    }

    private fun updateTabEditState(tabState: GroupTabViewState) {
        val editing = tabState.isEditing
        tabState.labelView.isVisible = !editing
        tabState.editView.isVisible = editing
        tabState.rootView.isClickable = !editing
        tabState.rootView.isLongClickable = !editing && tabState.group.id.isNotBlank()
        if (!editing) {
            tabState.editView.clearFocus()
        }
    }

    private fun selectGroupTab(groupId: String) {
        for (index in 0 until binding.tabGroup.tabCount) {
            val tab = binding.tabGroup.getTabAt(index) ?: continue
            if ((tab.tag as? String).orEmpty() == groupId) {
                tab.select()
                return
            }
        }
    }

    private fun startInlineRename(tabState: GroupTabViewState) {
        if (tabState.isEditing) return
        val isSelected = tabState.rootView.isSelected
        tabState.isEditing = true
        tabState.draftRemarks = tabState.group.remarks
        tabState.editView.apply {
            setText(tabState.group.remarks)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        bindGroupTabViewState(tabState, selected = isSelected)
        tabState.editView.post {
            tabState.editView.requestFocus()
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(tabState.editView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun finishInlineRename(tabState: GroupTabViewState, save: Boolean) {
        if (!tabState.isEditing) return
        val isSelected = tabState.rootView.isSelected
        tabState.isEditing = false
        val renamed = tabState.editView.text?.toString().orEmpty().trim()
        val finalRemarks = when {
            save && renamed.isBlank() -> {
                activity.toast(R.string.sub_setting_remarks)
                tabState.draftRemarks.orEmpty()
            }
            save && mainViewModel.renameSubscriptionGroup(tabState.group.id, renamed) -> {
                tabState.group = tabState.group.copy(remarks = renamed)
                activity.toast(R.string.toast_success)
                renamed
            }
            else -> tabState.draftRemarks.orEmpty()
        }
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(tabState.editView.windowToken, 0)
        tabState.labelView.text = finalRemarks
        tabState.lastRemarks = finalRemarks
        bindGroupTabViewState(tabState, selected = isSelected)
    }
}
