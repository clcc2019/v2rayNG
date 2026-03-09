package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.ItemTabGroupBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val SHORT_ANIMATION_DURATION = 120L
        private const val MEDIUM_ANIMATION_DURATION = 180L
        private const val SPLASH_EXIT_DURATION = 260L
    }

    private enum class ServiceUiState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    private data class ChipUiModel(
        val backgroundColorRes: Int,
        val textColorRes: Int,
        val iconRes: Int? = null
    )

    private data class StatusBadgeUiModel(
        val text: CharSequence,
        val chip: ChipUiModel
    )

    private data class ActionButtonUiModel(
        @StringRes val textResId: Int,
        val iconRes: Int,
        val backgroundColorRes: Int,
        val contentColorRes: Int,
        val strokeWidth: Int,
        val strokeColorRes: Int? = null
    )

    private data class GroupTabViewState(
        var group: GroupMapItem,
        val surfaceView: View,
        val labelView: TextView,
        val countView: TextView
    )

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var serviceUiState = ServiceUiState.STOPPED
    private var isGroupTabHidden = false
    private var lastRenderedGroupCount = -1
    private var defaultViewPagerBottomPadding = 0
    private var defaultConnectionCardBottomMargin = 0
    private var isImeVisible = false
    private var isSearchUiActive = false
    private var homeSearchView: SearchView? = null
    private val motionInterpolator = FastOutSlowInInterpolator()
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

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            renderServiceUiState(ServiceUiState.STARTING)
            startV2Ray()
        } else {
            renderServiceUiState(if (mainViewModel.isRunning.value == true) ServiceUiState.RUNNING else ServiceUiState.STOPPED)
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val shouldRestart = SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true
        if (shouldRestart) {
            mainViewModel.prewarmSelectedConfig()
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
        if (!shouldRestart) {
            mainViewModel.prewarmSelectedConfig()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        configureLaunchSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.title = null
        binding.toolbar.subtitle = null
        binding.toolbar.logo = AppCompatResources.getDrawable(this, R.drawable.ic_toolbar_brand)
        binding.toolbar.logoDescription = getString(R.string.app_name)
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.md_theme_primary))
        binding.toolbar.setSubtitleTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
        defaultViewPagerBottomPadding = binding.viewPager.paddingBottom
        defaultConnectionCardBottomMargin = (binding.cardConnection.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        setupDrawerMotion(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        setupNavigationDrawerInsets()
        setupMainContentInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        UiMotion.attachPressFeedback(binding.fab)
        UiMotion.attachPressFeedback(binding.layoutTest)
        setupHomeMotion(runInitialEntrance = savedInstanceState == null)

        setupViewModel()
        setupGroupTab()
        mainViewModel.reloadServerList()
        mainViewModel.prewarmSelectedConfig()
        refreshConnectionCard()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun configureLaunchSplashScreen() {
        val splashScreen = installSplashScreen()
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val iconView = splashScreenView.iconView

            iconView.pivotX = iconView.width / 2f
            iconView.pivotY = iconView.height / 2f
            iconView.animate()
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .translationY(-iconView.height * 0.08f)
                .setDuration(SPLASH_EXIT_DURATION)
                .setInterpolator(motionInterpolator)
                .start()

            splashScreenView.view.animate()
                .alpha(0f)
                .setDuration(SPLASH_EXIT_DURATION)
                .setInterpolator(motionInterpolator)
                .withEndAction {
                    splashScreenView.remove()
                }
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        isSearchUiActive = homeSearchView?.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)?.hasFocus() == true
        updateConnectionCardVisibility()
    }

    private fun setupNavigationDrawerInsets() {
        val headerView = binding.navView.getHeaderView(0)
        val headerTopPadding = headerView.paddingTop
        val navBottomPadding = binding.navView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerView.updatePadding(top = headerTopPadding + systemBars.top)
            binding.navView.updatePadding(bottom = navBottomPadding + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.navView)
    }

    private fun setupDrawerMotion(toggle: ActionBarDrawerToggle) {
        binding.drawerLayout.addDrawerListener(toggle)
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (drawerView !== binding.navView) return
                val progress = slideOffset.coerceIn(0f, 1f)
                val shiftPx = resources.displayMetrics.density * 12f * progress
                val contentScale = 1f - (0.018f * progress)
                val toolbarScale = 1f - (0.01f * progress)

                binding.toolbar.translationX = shiftPx * 0.45f
                binding.toolbar.scaleX = toolbarScale
                binding.toolbar.scaleY = toolbarScale
                binding.mainContent.translationX = shiftPx
                binding.mainContent.scaleX = contentScale
                binding.mainContent.scaleY = contentScale
                binding.mainContent.alpha = 1f - (0.05f * progress)
                applyNavigationDrawerProgress(progress)
            }

            override fun onDrawerClosed(drawerView: View) {
                if (drawerView !== binding.navView) return
                resetDrawerDrivenTransforms()
                applyNavigationDrawerProgress(0f)
            }
        })
    }

    private fun setupHomeMotion(runInitialEntrance: Boolean) {
        binding.viewPager.setPageTransformer(null)

        if (!runInitialEntrance) {
            return
        }

        binding.mainContent.post {
            UiMotion.animateEntrance(binding.viewPager, translationOffsetDp = 18f)
            if (binding.cardTabGroup.isVisible) {
                UiMotion.animateEntrance(binding.cardTabGroup, translationOffsetDp = 14f, startDelay = 20L)
            }
            if (shouldShowConnectionCard()) {
                UiMotion.animateEntrance(binding.cardConnection, translationOffsetDp = 22f, startDelay = 40L)
            }
        }
    }

    private fun setupMainContentInsets() {
        val imeSpacing = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && imeInsets.bottom > systemBars.bottom
            isImeVisible = imeVisible

            val cardLayoutParams = binding.cardConnection.layoutParams as CoordinatorLayout.LayoutParams
            val targetCardBottomMargin = defaultConnectionCardBottomMargin + systemBars.bottom
            if (cardLayoutParams.bottomMargin != targetCardBottomMargin) {
                cardLayoutParams.bottomMargin = targetCardBottomMargin
                binding.cardConnection.layoutParams = cardLayoutParams
            }

            val targetListBottomPadding = if (imeVisible) {
                imeInsets.bottom + imeSpacing
            } else {
                defaultViewPagerBottomPadding + systemBars.bottom
            }
            binding.viewPager.updatePadding(bottom = targetListBottomPadding)
            updateConnectionCardVisibility()
            insets
        }
        ViewCompat.requestApplyInsets(binding.mainContent)
    }

    private fun updateConnectionCardVisibility() {
        UiMotion.animateVisibility(binding.cardConnection, shouldShowConnectionCard(), translationOffsetDp = 18f)
    }

    private fun shouldShowConnectionCard(): Boolean {
        return !isImeVisible && !isSearchUiActive
    }

    private fun setupViewModel() {
        mainViewModel.updateGroupsAction.observe(this) { groups ->
            renderGroupTabs(groups)
        }
        mainViewModel.updateTestResultAction.observe(this) { result ->
            val message = compactTestResult(result)
            if (message.isNotBlank()) {
                toast(message)
            }
        }
        mainViewModel.updateConnectionCardAction.observe(this) {
            refreshConnectionCard()
        }
        mainViewModel.serviceFeedbackAction.observe(this) { feedback ->
            toast(getString(feedback.messageResId))
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            renderServiceUiState(if (isRunning) ServiceUiState.RUNNING else ServiceUiState.STOPPED)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        mainViewModel.loadSubscriptions(applicationContext)
    }

    private fun renderGroupTabs(groups: List<GroupMapItem>) {
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
            refreshConnectionCard()
            return
        }

        val structureChanged = groupPagerAdapter.update(groups)
        configureGroupTabLayout(groups.size)

        if (structureChanged || tabMediator == null) {
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
        } else {
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
        refreshConnectionCard()
        lastRenderedGroupCount = groups.size
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

        val cardHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp36) +
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp2)
        val cardTopMargin = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp2)
        val cardLayoutParams = binding.cardTabGroup.layoutParams as CoordinatorLayout.LayoutParams
        val sideMargin = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16)
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
        val tabBinding = ItemTabGroupBinding.inflate(LayoutInflater.from(this), binding.tabGroup, false)
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
        val compactHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp32)
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
        tabState.labelView.text = tabState.group.remarks
        tabState.labelView.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.md_theme_onSurface else R.color.md_theme_onSurfaceVariant
            )
        )
        tabState.labelView.alpha = if (selected) 1f else 0.88f

        val hasCount = tabState.group.count > 0
        tabState.countView.isVisible = hasCount
        if (hasCount) {
            tabState.countView.text = tabState.group.count.toString()
            tabState.countView.setBackgroundResource(
                if (selected) R.drawable.bg_group_count_badge_selected else R.drawable.bg_group_count_badge
            )
            tabState.countView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.md_theme_onPrimary else R.color.md_theme_onSurfaceVariant
                )
            )
            tabState.countView.alpha = if (selected) 1f else 0.92f
        }
    }

    fun onServerListScrolled(dy: Int, canScrollUp: Boolean) {
        if (!binding.cardTabGroup.isVisible) return
        when {
            !canScrollUp -> setGroupTabVisible(true)
            dy > 6 -> setGroupTabVisible(false)
            dy < -6 -> setGroupTabVisible(true)
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
        val listGap = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp4)
        val cardTopMargin = (binding.cardTabGroup.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val topPadding = if (binding.cardTabGroup.isVisible) {
            cardTopMargin + binding.cardTabGroup.height + listGap
        } else {
            listGap
        }
        binding.viewPager.updatePadding(top = topPadding)
    }

    private fun handleFabAction() {
        if (serviceUiState == ServiceUiState.STARTING || serviceUiState == ServiceUiState.STOPPING) {
            return
        }

        if (mainViewModel.isRunning.value == true) {
            renderServiceUiState(ServiceUiState.STOPPING)
            V2RayServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                renderServiceUiState(ServiceUiState.STARTING)
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            renderServiceUiState(ServiceUiState.STARTING)
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            renderServiceUiState(ServiceUiState.STOPPED)
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            toast(R.string.connection_test_testing)
            mainViewModel.testCurrentServerRealPing()
        } else {
            toast(R.string.connection_test_unavailable)
        }
    }

    fun restartV2Ray() {
        V2RayServiceManager.restartVService(this)
    }

    private fun compactTestResult(content: String?): String {
        val raw = content.orEmpty().trim()
        if (raw.isEmpty()) return raw

        Regex("(\\d+)\\s*ms", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.let {
            return "${it}ms"
        }
        Regex("(\\d+)\\s*毫秒").find(raw)?.groupValues?.getOrNull(1)?.let {
            return "${it}ms"
        }
        if (raw.contains("success", ignoreCase = true) || raw.contains("连接成功")) {
            return getString(R.string.connection_connected)
        }
        if (raw.contains("error", ignoreCase = true) || raw.contains("fail", ignoreCase = true) || raw.contains("失败") || raw.contains("无互联网")) {
            return getString(R.string.connection_test_fail)
        }
        return raw
    }

    private fun renderServiceUiState(state: ServiceUiState) {
        val previousState = serviceUiState
        serviceUiState = state
        val isTransitioning = state == ServiceUiState.STARTING || state == ServiceUiState.STOPPING

        hideProgressBar()
        binding.fab.isEnabled = !isTransitioning
        binding.fab.isClickable = !isTransitioning
        binding.fab.alpha = if (isTransitioning) 0.92f else 1f
        binding.layoutTest.isEnabled = state == ServiceUiState.RUNNING
        binding.layoutTest.isClickable = state == ServiceUiState.RUNNING
        binding.layoutTest.isFocusable = state == ServiceUiState.RUNNING
        binding.layoutTest.alpha = if (state == ServiceUiState.RUNNING) 1f else 0.72f
        updateConnectionCard(state)
        updateToolbarSubtitle()
        animateServiceStateChange(previousState, state)
        performServiceStateHaptic(previousState, state)
        applyActionButtonUiModel(buildActionButtonUiModel(state))
    }

    private fun performServiceStateHaptic(previousState: ServiceUiState, newState: ServiceUiState) {
        if (previousState == newState) {
            return
        }
        val effect = when (newState) {
            ServiceUiState.STARTING -> buildServiceHapticEffect(isStarting = true)
            ServiceUiState.STOPPING -> buildServiceHapticEffect(isStarting = false)
            else -> null
        } ?: return

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) {
            return
        }
        vibrator.vibrate(effect)
    }

    private fun buildServiceHapticEffect(isStarting: Boolean): VibrationEffect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                if (isStarting) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
            )
        } else {
            VibrationEffect.createOneShot(if (isStarting) 18L else 24L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private fun buildConnectionBadgeUiModel(state: ServiceUiState): StatusBadgeUiModel {
        val latencyText = buildConnectionLatencyText(getSelectedProfile())
        if (state == ServiceUiState.RUNNING && !latencyText.isNullOrBlank()) {
            return StatusBadgeUiModel(
                text = latencyText,
                chip = ChipUiModel(
                    backgroundColorRes = R.color.md_theme_tertiaryContainer,
                    textColorRes = R.color.md_theme_onTertiaryContainer
                )
            )
        }

        return when (state) {
            ServiceUiState.STARTING -> StatusBadgeUiModel(
                text = getString(R.string.connection_starting_short),
                chip = ChipUiModel(
                    backgroundColorRes = R.color.md_theme_secondaryContainer,
                    textColorRes = R.color.md_theme_onSecondaryContainer
                )
            )

            ServiceUiState.STOPPING -> StatusBadgeUiModel(
                text = getString(R.string.connection_stopping_short),
                chip = ChipUiModel(
                    backgroundColorRes = R.color.md_theme_surfaceVariant,
                    textColorRes = R.color.md_theme_onSurfaceVariant
                )
            )

            ServiceUiState.RUNNING -> StatusBadgeUiModel(
                text = getString(R.string.connection_connected_short),
                chip = ChipUiModel(
                    backgroundColorRes = R.color.md_theme_tertiaryContainer,
                    textColorRes = R.color.md_theme_onTertiaryContainer
                )
            )

            ServiceUiState.STOPPED -> StatusBadgeUiModel(
                text = getString(R.string.connection_not_connected_short),
                chip = ChipUiModel(
                    backgroundColorRes = R.color.md_theme_surfaceVariant,
                    textColorRes = R.color.md_theme_onSurfaceVariant
                )
            )
        }
    }

    private fun buildActionButtonUiModel(state: ServiceUiState): ActionButtonUiModel {
        return when (state) {
            ServiceUiState.STARTING -> ActionButtonUiModel(
                textResId = R.string.connection_starting,
                iconRes = R.drawable.ic_play_24dp,
                backgroundColorRes = R.color.md_theme_primaryContainer,
                contentColorRes = R.color.md_theme_onPrimaryContainer,
                strokeWidth = 0
            )

            ServiceUiState.STOPPING -> ActionButtonUiModel(
                textResId = R.string.connection_stopping,
                iconRes = R.drawable.ic_stop_24dp,
                backgroundColorRes = R.color.md_theme_primaryContainer,
                contentColorRes = R.color.md_theme_onPrimaryContainer,
                strokeWidth = 0
            )

            ServiceUiState.RUNNING -> ActionButtonUiModel(
                textResId = R.string.action_stop_service,
                iconRes = R.drawable.ic_stop_24dp,
                backgroundColorRes = R.color.md_theme_primaryContainer,
                contentColorRes = R.color.md_theme_onPrimaryContainer,
                strokeWidth = 0
            )

            ServiceUiState.STOPPED -> ActionButtonUiModel(
                textResId = R.string.tasker_start_service,
                iconRes = R.drawable.ic_play_24dp,
                backgroundColorRes = R.color.md_theme_surface,
                contentColorRes = R.color.md_theme_onSurface,
                strokeWidth = 1,
                strokeColorRes = R.color.md_theme_outlineVariant
            )
        }
    }

    private fun applyActionButtonUiModel(model: ActionButtonUiModel) {
        binding.fab.setIconResource(model.iconRes)
        binding.fab.text = getString(model.textResId)
        binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, model.backgroundColorRes))
        binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, model.contentColorRes))
        binding.fab.setTextColor(ContextCompat.getColor(this, model.contentColorRes))
        binding.fab.strokeWidth = model.strokeWidth
        binding.fab.strokeColor = model.strokeColorRes?.let {
            ColorStateList.valueOf(ContextCompat.getColor(this, it))
        }
        binding.fab.contentDescription = getString(model.textResId)
    }

    private fun applyChipUiModel(target: TextView, model: ChipUiModel) {
        DrawableCompat.setTint(target.background.mutate(), ContextCompat.getColor(this, model.backgroundColorRes))
        target.setTextColor(ContextCompat.getColor(this, model.textColorRes))
        target.setCompoundDrawablesRelativeWithIntrinsicBounds(
            model.iconRes?.let { createFeedbackIconForRes(it, model.textColorRes) },
            null,
            null,
            null
        )
    }

    private fun createFeedbackIconForRes(iconRes: Int, tintColorRes: Int) =
        AppCompatResources.getDrawable(this, iconRes)?.mutate()?.apply {
            DrawableCompat.setTint(this, ContextCompat.getColor(this@MainActivity, tintColorRes))
        }

    private fun hideProgressBar() {
        binding.progressBar.animate().cancel()
        binding.progressBar.alpha = 0f
        binding.progressBar.visibility = View.INVISIBLE
    }

    private fun animateServiceStateChange(previousState: ServiceUiState, newState: ServiceUiState) {
        if (previousState == newState) return

        binding.cardConnection.animate().cancel()
        binding.fab.animate().cancel()
        binding.layoutTest.animate().cancel()

        binding.cardConnection.scaleX = 1f
        binding.cardConnection.scaleY = 1f
        binding.cardConnection.alpha = 1f

        val controlScale = if (newState == ServiceUiState.RUNNING) 1f else 0.985f
        binding.fab.animate()
            .scaleX(controlScale)
            .scaleY(controlScale)
            .setDuration(SHORT_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                binding.fab.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(SHORT_ANIMATION_DURATION)
                    .setInterpolator(motionInterpolator)
                    .start()
            }
            .start()

        binding.layoutTest.animate()
            .alpha(if (newState == ServiceUiState.RUNNING) 1f else 0.72f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun updateToolbarSubtitle() {
        binding.toolbar.subtitle = null
    }

    fun refreshConnectionCard() {
        updateConnectionCard(serviceUiState)
        updateToolbarSubtitle()
    }

    private fun updateConnectionCard(state: ServiceUiState) {
        val profile = getSelectedProfile()
        binding.tvActiveServer.text = buildConnectionTitle(profile) ?: getString(R.string.connection_not_connected)
        binding.tvConnectionLabel.text = getString(R.string.current_config)
        binding.tvConfigType.text = profile?.configType?.name.orEmpty()
        binding.tvConfigType.isVisible = profile != null

        val configBadgeText = buildConfigBadgeText(profile)
        binding.tvConfigMeta.text = configBadgeText
        binding.tvConfigMeta.isVisible = !configBadgeText.isNullOrBlank()

        val serverAddress = buildConnectionAddress(profile)
        binding.tvConnectionAddress.text = serverAddress ?: getString(R.string.connection_not_connected)

        val metaLine = buildConnectionMetaLine(profile, state)
        binding.tvConnectionMetaLine.text = metaLine
        binding.tvConnectionMetaLine.isVisible = !metaLine.isNullOrBlank()

        val badgeUiModel = buildConnectionBadgeUiModel(state)
        binding.tvConnectionBadge.text = badgeUiModel.text
        applyChipUiModel(binding.tvConnectionBadge, badgeUiModel.chip)
        binding.layoutTest.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (state == ServiceUiState.RUNNING) R.color.md_theme_surface else R.color.md_theme_surfaceVariant)
        )
        binding.layoutTest.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (state == ServiceUiState.RUNNING) R.color.md_theme_primaryContainer else R.color.md_theme_outlineVariant)
        )
        binding.layoutTest.setTextColor(
            ContextCompat.getColor(this, if (state == ServiceUiState.RUNNING) R.color.md_theme_onSurface else R.color.md_theme_onSurfaceVariant)
        )
        binding.layoutTest.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (state == ServiceUiState.RUNNING) R.color.md_theme_onSurface else R.color.md_theme_onSurfaceVariant)
        )

    }

    private fun getSelectedProfile(): ProfileItem? {
        val guid = MmkvManager.getSelectServer() ?: return null
        return MmkvManager.decodeServerConfig(guid)
    }

    private fun buildConnectionAddress(profile: ProfileItem?): String? {
        if (profile == null) return null
        val server = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.trim().orEmpty()
        return when {
            server.isNotEmpty() && port.isNotEmpty() -> Utils.getIpv6Address(server) + ":" + port
            server.isNotEmpty() -> Utils.getIpv6Address(server)
            port.isNotEmpty() -> port
            profile.configType == EConfigType.CUSTOM -> profile.getServerAddressAndPort()
            else -> null
        }
    }

    private fun buildConnectionTitle(profile: ProfileItem?): CharSequence? {
        if (profile == null) return null
        return profile.remarks.trim().ifEmpty {
            profile.configType.name
        }
    }

    private fun buildConfigBadgeText(profile: ProfileItem?): String? {
        val network = profile?.network?.trim().orEmpty()
        return network.takeIf { it.isNotEmpty() }?.uppercase()
    }

    private fun buildConnectionLatencyText(profile: ProfileItem?): String? {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (profile == null || selectedGuid.isBlank()) {
            return null
        }

        val delayMillis = MmkvManager.getServerTestDelayMillis(selectedGuid) ?: 0L
        return when {
            delayMillis > 0L -> "$delayMillis ms"
            else -> null
        }
    }

    private fun buildConnectionMetaLine(profile: ProfileItem?, state: ServiceUiState): String? {
        if (profile == null) return null
        val details = linkedSetOf<String>()
        profile.security?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }?.let {
            details += it.uppercase()
        }
        profile.flow?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        profile.method?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        profile.host?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) &&
            (state == ServiceUiState.STARTING || state == ServiceUiState.RUNNING)
        ) {
            details += getString(R.string.toast_warning_pref_proxysharing_short)
        }
        return details.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun getSelectedServerName(): String? {
        return getSelectedProfile()?.remarks
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.search_view)
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                isSearchUiActive = false
                updateConnectionCardVisibility()
                return true
            }
        })
        setupSearchView(
            menuItem = searchItem,
            onQueryChanged = { mainViewModel.filterConfig(it) },
            onClosed = {
                isSearchUiActive = false
                updateConnectionCardVisibility()
                mainViewModel.filterConfig("")
            }
        )?.let { searchView ->
            homeSearchView = searchView
            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                isSearchUiActive = hasFocus
                updateConnectionCardVisibility()
            }
            searchView.setOnSearchClickListener {
                isSearchUiActive = true
                updateConnectionCardVisibility()
            }
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                ?.setOnEditorActionListener { v, _, _ ->
                    isSearchUiActive = v.hasFocus()
                    updateConnectionCardVisibility()
                    false
                }
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                ?.setOnFocusChangeListener { _, hasFocus ->
                    isSearchUiActive = hasFocus
                    updateConnectionCardVisibility()
                }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_add_sheet -> {
            showActionBottomSheet(
                title = getString(R.string.menu_item_add_config),
                subtitle = getString(R.string.current_config),
                actions = buildHomeAddActions()
            )
            true
        }

        R.id.action_more_sheet -> {
            showActionBottomSheet(
                title = getString(R.string.notification_action_more),
                subtitle = getSelectedServerName().orEmpty().ifBlank { null },
                actions = buildHomeMoreActions()
            )
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun applyNavigationDrawerProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val offsetPx = resources.displayMetrics.density * 16f * (1f - clamped)
        val headerView = binding.navView.getHeaderView(0)
        headerView.alpha = clamped
        headerView.translationY = offsetPx
        val menuView = binding.navView.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until menuView.childCount) {
            val child = menuView.getChildAt(index)
            val itemProgress = ((clamped - (index * 0.04f)) / 0.92f).coerceIn(0f, 1f)
            child.alpha = itemProgress
            child.translationY = resources.displayMetrics.density * 10f * (1f - itemProgress)
        }
    }

    private fun resetDrawerDrivenTransforms() {
        binding.toolbar.animate().cancel()
        binding.mainContent.animate().cancel()
        binding.toolbar.translationX = 0f
        binding.toolbar.scaleX = 1f
        binding.toolbar.scaleY = 1f
        binding.mainContent.translationX = 0f
        binding.mainContent.scaleX = 1f
        binding.mainContent.scaleY = 1f
        binding.mainContent.alpha = 1f
    }

    private fun buildHomeAddActions(): List<ActionBottomSheetItem> {
        return listOf(
            ActionBottomSheetItem(R.string.menu_item_import_config_qrcode, R.drawable.ic_qu_scan_24dp) { importQRcode() },
            ActionBottomSheetItem(R.string.menu_item_import_config_clipboard, R.drawable.ic_copy) { importClipboard() },
            ActionBottomSheetItem(R.string.menu_item_import_config_local, R.drawable.ic_file_24dp) { importConfigLocal() },
            ActionBottomSheetItem(R.string.menu_item_import_config_policy_group, R.drawable.ic_subscriptions_24dp) {
                importManually(EConfigType.POLICYGROUP.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_vmess, R.drawable.ic_add_24dp) {
                importManually(EConfigType.VMESS.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_vless, R.drawable.ic_add_24dp) {
                importManually(EConfigType.VLESS.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_ss, R.drawable.ic_add_24dp) {
                importManually(EConfigType.SHADOWSOCKS.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_socks, R.drawable.ic_add_24dp) {
                importManually(EConfigType.SOCKS.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_http, R.drawable.ic_add_24dp) {
                importManually(EConfigType.HTTP.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_trojan, R.drawable.ic_add_24dp) {
                importManually(EConfigType.TROJAN.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_wireguard, R.drawable.ic_add_24dp) {
                importManually(EConfigType.WIREGUARD.value)
            },
            ActionBottomSheetItem(R.string.menu_item_import_config_manually_hysteria2, R.drawable.ic_add_24dp) {
                importManually(EConfigType.HYSTERIA2.value)
            }
        )
    }

    private fun buildHomeMoreActions(): List<ActionBottomSheetItem> {
        return listOf(
            ActionBottomSheetItem(R.string.title_service_restart, R.drawable.ic_restore_24dp) { restartV2Ray() },
            ActionBottomSheetItem(R.string.title_sub_update, R.drawable.ic_cloud_download_24dp) { importConfigViaSub() },
            ActionBottomSheetItem(R.string.title_export_all, R.drawable.ic_description_24dp) { exportAll() },
            ActionBottomSheetItem(R.string.title_ping_all_server, R.drawable.ic_logcat_24dp) {
                toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllTcping()
            },
            ActionBottomSheetItem(R.string.title_real_ping_all_server, R.drawable.ic_logcat_24dp) {
                toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllRealPing()
            },
            ActionBottomSheetItem(R.string.title_sort_by_test_results, R.drawable.ic_feedback_24dp) { sortByTestResults() },
            ActionBottomSheetItem(R.string.title_del_duplicate_config, R.drawable.ic_delete_24dp, destructive = true) {
                delDuplicateConfig()
            },
            ActionBottomSheetItem(R.string.title_del_invalid_config, R.drawable.ic_delete_24dp, destructive = true) {
                delInvalidConfig()
            },
            ActionBottomSheetItem(R.string.title_del_all_config, R.drawable.ic_delete_24dp, destructive = true) {
                delAllConfig()
            }
        )
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        launchLoadingTask(
            delayMillis = 500L,
            task = { AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true) },
            onSuccess = { (count, countSub) ->
                when {
                    count > 0 -> {
                        toast(getString(R.string.title_import_config_count, count))
                        mainViewModel.reloadServerList()
                    }

                    countSub > 0 -> setupGroupTab()
                    else -> toastError(R.string.toast_failure)
                }
            },
            onFailure = { e ->
                toastError(R.string.toast_failure)
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        )
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        launchLoadingTask(
            delayMillis = 500L,
            task = { mainViewModel.updateConfigViaSubAll() },
            onSuccess = { result ->
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
            }
        )
        return true
    }

    private fun exportAll() {
        launchLoadingTask(
            task = { mainViewModel.exportAllServer() },
            onSuccess = { count ->
                if (count > 0) {
                    toast(getString(R.string.title_export_config_count, count))
                } else {
                    toastError(R.string.toast_failure)
                }
            }
        )
    }

    private fun delAllConfig() {
        showConfirmDialog(R.string.del_config_comfirm) {
            launchLoadingTask(
                task = { mainViewModel.removeAllServer() },
                onSuccess = { count ->
                    mainViewModel.reloadServerList()
                    toast(getString(R.string.title_del_config_count, count))
                }
            )
        }
    }

    private fun delDuplicateConfig() {
        showConfirmDialog(R.string.del_config_comfirm) {
            launchLoadingTask(
                task = { mainViewModel.removeDuplicateServer() },
                onSuccess = { count ->
                    mainViewModel.reloadServerList()
                    toast(getString(R.string.title_del_duplicate_config_count, count))
                }
            )
        }
    }

    private fun delInvalidConfig() {
        showConfirmDialog(R.string.del_invalid_config_comfirm) {
            launchLoadingTask(
                task = { mainViewModel.removeInvalidServer() },
                onSuccess = { count ->
                    mainViewModel.reloadServerList()
                    toast(getString(R.string.title_del_config_count, count))
                }
            )
        }
    }

    private fun sortByTestResults() {
        launchLoadingTask(
            task = {
                mainViewModel.sortByTestResults()
            },
            onSuccess = {
                mainViewModel.reloadServerList()
            }
        )
    }

    private fun showConfirmDialog(
        @StringRes messageResId: Int,
        onConfirmed: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setMessage(messageResId)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirmed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun <T> launchLoadingTask(
        delayMillis: Long = 0L,
        task: suspend () -> T,
        onSuccess: (T) -> Unit,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = task()
                if (delayMillis > 0) {
                    delay(delayMillis)
                }
                withContext(Dispatchers.Main) {
                    onSuccess(result)
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onFailure?.invoke(e)
                    hideLoading()
                }
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}
