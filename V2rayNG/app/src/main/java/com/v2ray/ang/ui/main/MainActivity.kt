package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.ui.common.hapticClick
import com.v2ray.ang.ui.common.hapticConfirm
import com.v2ray.ang.ui.common.hapticReject
import com.v2ray.ang.ui.common.hapticVirtualKey
import com.v2ray.ang.ui.common.launchActivityWithDefaultTransition
import com.v2ray.ang.util.StartupTracer
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel

class MainActivity : HelperBaseActivity() {
    private data class ActionButtonUiModel(
        @param:StringRes val textResId: Int,
        val iconRes: Int,
        val contentColorRes: Int
    )

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private val connectionCardController by lazy {
        MainConnectionCardController(this, binding, mainViewModel, motionInterpolator)
    }
    private val groupTabsController by lazy {
        MainGroupTabsController(this, binding, mainViewModel, motionInterpolator)
    }
    private val searchController by lazy {
        MainSearchController(this, groupTabsController, mainViewModel) { active ->
            renderChromeState(chromeStateReducer.onSearchStateChanged(active), event = "search_ui")
            groupTabsController.notifyCurrentFragmentSearchUiChanged()
        }
    }
    private val actionsController by lazy {
        MainActionsController(
            activity = this,
            mainViewModel = mainViewModel,
            onSetupGroupTabs = { groupTabsController.setupGroupTabs() },
            onOpenSearch = { openToolbarSearch() }
        )
    }
    private var serviceUiState = ServiceUiState.STOPPED
    private var defaultViewPagerTopPadding = 0
    private var defaultViewPagerBottomPadding = 0
    private var defaultConnectionCardBottomMargin = 0
    private var isImeVisible = false
    private var isCurrentPingTesting = false
    private var toolbarSearchMenuItem: MenuItem? = null
    private val toolbarActionViews = mutableListOf<FrameLayout>()
    private var currentChromeState: AppChromeState? = null
    private val toolbarController by lazy {
        MainToolbarController(
            activity = this,
            binding = binding,
            motionInterpolator = motionInterpolator,
            onOpenMorePage = { openMorePage() },
            statusProvider = { ToolbarStatusState(serviceUiState, isCurrentPingTesting) }
        )
    }
    private val chromeStateReducer by lazy {
        AppChromeStateReducer(AppChromePageKind.HOME)
    }
    private val topBarRenderer by lazy {
        MainTopBarRenderer(binding.appBarHome.background, motionInterpolator)
    }
    private val motionInterpolator = FastOutSlowInInterpolator()
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            renderServiceUiState(ServiceUiState.STARTING)
            startSelectedV2Ray()
        } else {
            renderServiceUiState(if (mainViewModel.isRunning.value == true) ServiceUiState.RUNNING else ServiceUiState.STOPPED)
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val shouldRestart = SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true
        val shouldRefreshGroups = SettingsChangeManager.consumeSetupGroupTab()
        if (shouldRestart) {
            mainViewModel.prewarmSelectedConfig()
            restartV2Ray()
        }
        if (shouldRefreshGroups) {
            setupGroupTab()
            mainViewModel.reloadServerList()
            refreshConnectionCard()
        }
        if (!shouldRestart && !shouldRefreshGroups) {
            mainViewModel.prewarmSelectedConfig()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTracer.beginSection("MainActivity.onCreate")
        try {
            configureLaunchSplashScreen()
            super.onCreate(savedInstanceState)
            setContentView(binding.root)
            configureHomeChrome()
            configureToolbar()
            defaultViewPagerTopPadding = binding.viewPager.paddingTop
            defaultViewPagerBottomPadding = binding.viewPager.paddingBottom
            defaultConnectionCardBottomMargin = (binding.cardConnection.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin

            groupTabsController.initialize()
            setupMainContentInsets()
            setupActionControls()
            setupHomeMotion(runInitialEntrance = savedInstanceState == null)
            renderChromeState(chromeStateReducer.currentState(), event = "initial", animate = false)

            setupViewModel()
            schedulePostLaunchWork()
            StartupTracer.mark("MainActivity.onCreate.end")
        } finally {
            StartupTracer.endSection()
        }
    }

    private fun configureToolbar() {
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setHomeButtonEnabled(false)
        binding.toolbar.setContentInsetsRelative(0, 0)
        binding.toolbar.contentInsetStartWithNavigation = 0
        binding.toolbar.contentInsetEndWithActions = 0
        binding.toolbar.titleMarginStart = 0
        binding.toolbar.titleMarginEnd = 0
        binding.toolbar.title = null
        binding.toolbar.subtitle = null
        binding.toolbar.logo = null
        binding.toolbar.logoDescription = getString(R.string.app_name)
        binding.toolbar.setBackgroundColor(Color.TRANSPARENT)
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.color_home_on_surface))
        binding.toolbar.setSubtitleTextColor(ContextCompat.getColor(this, R.color.color_home_on_surface_muted))
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.color_home_on_surface))
        binding.toolbar.overflowIcon?.setTint(ContextCompat.getColor(this, R.color.color_home_on_surface))
        toolbarController.attach()
        toolbarController.updateStatus(serviceUiState, isCurrentPingTesting)
    }

    private fun configureHomeChrome() {
        val isDarkMode = Utils.getDarkModeStatus(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    private fun setupActionControls() {
        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        UiMotion.attachPressFeedback(binding.fab)
        UiMotion.attachPressFeedback(binding.layoutTest)
    }

    private fun schedulePostLaunchWork() {
        runAfterFirstFrame {
            mainViewModel.startListenBroadcast()
            mainViewModel.initAssets(assets)
            setupGroupTab()
            mainViewModel.reloadServerList()
            mainViewModel.prewarmSelectedConfig()
            refreshConnectionCard()
            checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
            }
        }
    }

    private fun runAfterFirstFrame(action: () -> Unit) {
        binding.root.doOnPreDraw {
            StartupTracer.mark("MainActivity.firstFrame")
            binding.root.post { action() }
        }
    }

    private fun configureLaunchSplashScreen() {
        val splashScreen = installSplashScreen()
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            runCatching { splashScreenView.iconView }.getOrNull()?.let { iconView ->
                iconView.pivotX = iconView.width / 2f
                iconView.pivotY = iconView.height / 2f
                iconView.animate()
                    .alpha(0f)
                    .scaleX(0.86f)
                    .scaleY(0.86f)
                    .translationY(-iconView.height * 0.08f)
                    .setDuration(MotionTokens.SPLASH_EXIT_DURATION)
                    .setInterpolator(motionInterpolator)
                    .start()
            }

            splashScreenView.view.animate()
                .alpha(0f)
                .setDuration(MotionTokens.SPLASH_EXIT_DURATION)
                .setInterpolator(motionInterpolator)
                .withEndAction {
                    splashScreenView.remove()
                }
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionCardVisibility()
    }

    private fun setupHomeMotion(runInitialEntrance: Boolean) {
        val pageOffsetPx = resources.displayMetrics.density * 12f
        binding.viewPager.setPageTransformer { page, position ->
            val absPos = kotlin.math.abs(position).coerceAtMost(1f)
            val scale = 1f - (0.02f * absPos)
            page.alpha = 1f - (0.12f * absPos)
            page.translationX = -page.width * 0.04f * position
            page.translationY = pageOffsetPx * absPos
            page.scaleX = scale
            page.scaleY = scale
        }

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
        val fallbackTopChromeHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp56)
        binding.cardConnection.doOnLayout {
            syncConnectionDockUnderlay((it.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && imeInsets.bottom > systemBars.bottom
            val floatingBottomInset = (systemBars.bottom * 0.18f).toInt()
            isImeVisible = imeVisible
            searchController.onInsetsChanged(insets)
            binding.appBarHome.updatePadding(top = systemBars.top)
            val chromeContentHeight = binding.appBarHome.height.takeIf { it > 0 } ?: fallbackTopChromeHeight
            val targetListTopPadding = maxOf(defaultViewPagerTopPadding, chromeContentHeight)
            updateConnectionDockLayout(floatingBottomInset)

            val targetListBottomPadding = if (imeVisible) {
                imeInsets.bottom + imeSpacing
            } else {
                defaultViewPagerBottomPadding + floatingBottomInset
            }
            binding.viewPager.updatePadding(
                top = targetListTopPadding,
                bottom = targetListBottomPadding
            )
            renderChromeState(chromeStateReducer.onImeStateChanged(imeVisible), event = "ime_insets")
            insets
        }
        ViewCompat.requestApplyInsets(binding.mainContent)
    }

    private fun updateConnectionDockLayout(floatingBottomInset: Int) {
        val cardLayoutParams = binding.cardConnection.layoutParams as CoordinatorLayout.LayoutParams
        val targetCardBottomMargin = defaultConnectionCardBottomMargin + floatingBottomInset
        if (cardLayoutParams.bottomMargin != targetCardBottomMargin || cardLayoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            cardLayoutParams.bottomMargin = targetCardBottomMargin
            cardLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            binding.cardConnection.layoutParams = cardLayoutParams
        }
        syncConnectionDockUnderlay(targetCardBottomMargin)
    }

    private fun syncConnectionDockUnderlay(cardBottomMargin: Int) {
        val fallbackDockHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp72)
        val cardHeight = binding.cardConnection.height.takeIf { it > 0 } ?: fallbackDockHeight
        val underlayLayoutParams = binding.viewConnectionDockUnderlay.layoutParams as CoordinatorLayout.LayoutParams
        val targetUnderlayHeight = cardHeight + cardBottomMargin
        if (underlayLayoutParams.height != targetUnderlayHeight || underlayLayoutParams.bottomMargin != 0) {
            underlayLayoutParams.height = targetUnderlayHeight
            underlayLayoutParams.bottomMargin = 0
            binding.viewConnectionDockUnderlay.layoutParams = underlayLayoutParams
        }
    }

    private fun updateConnectionCardVisibility() {
        renderChromeState(chromeStateReducer.currentState(), event = "visibility_sync", animate = false, force = true)
    }

    private fun shouldShowConnectionCard(): Boolean {
        return currentChromeState?.showBottomBar ?: chromeStateReducer.currentState().showBottomBar
    }

    fun isSearchUiActive(): Boolean = searchController.isSearchActive()

    private fun setupViewModel() {
        mainViewModel.updateGroupsAction.observe(this) { groups ->
            groupTabsController.renderGroupTabs(groups)
        }
        mainViewModel.updateTestResultAction.observe(this) { result ->
            val message = compactTestResult(result)
            if (message.isNotBlank()) {
                toast(message)
            }
            if (isCurrentPingTesting) {
                setCurrentPingTesting(false)
            }
        }
        mainViewModel.updateConnectionCardAction.observe(this) {
            refreshConnectionCard()
        }
        mainViewModel.serviceFeedbackAction.observe(this) { feedback ->
            toolbarController.showTransientMessage(getString(feedback.messageResId))
            performServiceFeedbackHaptic(feedback.style)
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            renderServiceUiState(if (isRunning) ServiceUiState.RUNNING else ServiceUiState.STOPPED)
        }
    }

    private fun setupGroupTab() {
        groupTabsController.setupGroupTabs()
    }

    fun onServerListContextChanged(isEmpty: Boolean, canScrollUp: Boolean, resetContext: Boolean) {
        val state = if (resetContext) {
            chromeStateReducer.onGroupContextChanged(isEmpty = isEmpty, canScrollUp = canScrollUp)
        } else {
            chromeStateReducer.onContentStateChanged(isEmpty = isEmpty, canScrollUp = canScrollUp)
        }
        renderChromeState(state, event = if (resetContext) "group_context" else "content_state")
    }

    fun onServerListScrollStateChanged(scrollState: Int, canScrollUp: Boolean) {
        val phase = when (scrollState) {
            androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> AppChromeScrollPhase.DRAGGING
            androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING -> AppChromeScrollPhase.SETTLING
            androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> AppChromeScrollPhase.IDLE
            else -> {
                AppChromeDebugTracer.recordInvalidTransition("scroll_state", "unknown=$scrollState")
                AppChromeScrollPhase.IDLE
            }
        }
        renderChromeState(chromeStateReducer.onScrollPhaseChanged(phase, canScrollUp), event = "scroll_phase")
    }

    fun onServerListScrolled(dy: Int, canScrollUp: Boolean) {
        groupTabsController.onServerListScrolled(dy, canScrollUp)
        renderChromeState(chromeStateReducer.onScrollPositionChanged(canScrollUp), event = "scroll_position")
    }

    private fun renderChromeState(state: AppChromeState, event: String, animate: Boolean = true, force: Boolean = false) {
        val previous = currentChromeState
        if (!force && previous == state) {
            AppChromeDebugTracer.recordRenderSkip("render_state:$event")
            return
        }
        topBarRenderer.renderChromeState(state, animate = animate)
        connectionCardController.renderChromeState(state, animate = animate)
        currentChromeState = state
    }

    private fun handleFabAction() {
        if (serviceUiState == ServiceUiState.STARTING || serviceUiState == ServiceUiState.STOPPING) {
            return
        }

        if (mainViewModel.isRunning.value == true) {
            renderServiceUiState(ServiceUiState.STOPPING)
            V2RayServiceManager.stopVService(this)
        } else {
            startServiceWithVpnPreparation()
        }
    }

    private fun startV2Ray(guid: String) {
        MmkvManager.setSelectServer(guid)
        V2RayServiceManager.startVService(this, guid)
    }

    private fun startSelectedV2Ray() {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            renderServiceUiState(ServiceUiState.STOPPED)
            toast(R.string.title_file_chooser)
            return
        }
        startV2Ray(selectedGuid)
    }

    private fun startAfterRestart(guid: String) {
        startServiceWithVpnPreparation(guid)
    }

    private fun startServiceWithVpnPreparation(guid: String? = null) {
        if (guid != null) {
            MmkvManager.setSelectServer(guid)
            mainViewModel.prewarmSelectedConfig(guid)
        } else {
            mainViewModel.prewarmSelectedConfig()
        }
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                requestVpnPermission.launch(intent)
                return
            }
        }
        renderServiceUiState(ServiceUiState.STARTING)
        if (guid != null) {
            startV2Ray(guid)
        } else {
            startSelectedV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            if (isCurrentPingTesting) {
                return
            }
            setCurrentPingTesting(true)
            binding.layoutTest.hapticVirtualKey()
            UiMotion.animatePulse(binding.layoutTest, pulseScale = 1.02f, duration = MotionTokens.PULSE_MEDIUM)
            toast(R.string.connection_test_testing)
            mainViewModel.testCurrentServerRealPing()
        } else {
            binding.layoutTest.hapticClick()
            toast(R.string.connection_test_unavailable)
        }
    }

    fun restartV2Ray(guid: String? = null) {
        val targetGuid = guid ?: MmkvManager.getSelectServer()
        if (targetGuid.isNullOrEmpty()) {
            return
        }
        if (mainViewModel.isRunning.value == true) {
            renderServiceUiState(ServiceUiState.STOPPING)
            V2RayServiceManager.restartVService(this, targetGuid)
        } else {
            startAfterRestart(targetGuid)
        }
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

        binding.fab.isEnabled = !isTransitioning
        binding.fab.isClickable = !isTransitioning
        binding.fab.alpha = if (isTransitioning) 0.92f else 1f
        if (state != ServiceUiState.RUNNING && isCurrentPingTesting) {
            isCurrentPingTesting = false
        }
        syncStatusControls(state)
        connectionCardController.render(state)
        connectionCardController.updateStateVisuals(state, animate = previousState != state)
        updateToolbarSubtitle()
        animateServiceStateChange(previousState, state)
        performServiceStateHaptic(previousState, state)
        applyActionButtonUiModel(buildActionButtonUiModel(state))
    }

    private fun setCurrentPingTesting(enabled: Boolean) {
        if (isCurrentPingTesting == enabled) {
            return
        }
        isCurrentPingTesting = enabled
        syncStatusControls(serviceUiState)
    }

    private fun syncStatusControls(state: ServiceUiState) {
        updateTestButtonState(state)
        animateTestButtonAlpha(state)
        toolbarController.updateStatus(serviceUiState, isCurrentPingTesting)
    }

    private fun performServiceStateHaptic(previousState: ServiceUiState, newState: ServiceUiState) {
        if (previousState == newState) {
            return
        }
        when (newState) {
            ServiceUiState.STARTING -> binding.fab.hapticClick()
            ServiceUiState.STOPPING -> binding.fab.hapticVirtualKey()
            else -> Unit
        }
    }

    private fun performServiceFeedbackHaptic(style: MainViewModel.ServiceFeedback.Style) {
        when (style) {
            MainViewModel.ServiceFeedback.Style.SUCCESS -> binding.toolbar.hapticConfirm()
            MainViewModel.ServiceFeedback.Style.ERROR -> binding.toolbar.hapticReject()
            MainViewModel.ServiceFeedback.Style.NEUTRAL -> binding.toolbar.hapticClick()
        }
    }

    private fun buildActionButtonUiModel(state: ServiceUiState): ActionButtonUiModel {
        return when (state) {
            ServiceUiState.STARTING -> actionButtonUiModel(
                textResId = R.string.connection_starting,
                iconRes = R.drawable.ic_play_24dp
            )

            ServiceUiState.STOPPING -> actionButtonUiModel(
                textResId = R.string.connection_stopping,
                iconRes = R.drawable.ic_stop_24dp
            )

            ServiceUiState.RUNNING -> actionButtonUiModel(
                textResId = R.string.action_stop_service,
                iconRes = R.drawable.ic_stop_24dp
            )

            ServiceUiState.STOPPED -> actionButtonUiModel(
                textResId = R.string.tasker_start_service,
                iconRes = R.drawable.ic_play_24dp
            )
        }
    }

    private fun actionButtonUiModel(
        @StringRes textResId: Int,
        iconRes: Int
    ) = ActionButtonUiModel(
        textResId = textResId,
        iconRes = iconRes,
        contentColorRes = R.color.color_home_on_primary
    )

    private fun applyActionButtonUiModel(model: ActionButtonUiModel) {
        binding.fab.setImageResource(model.iconRes)
        binding.fab.setBackgroundResource(R.drawable.bg_connection_action_circle)
        binding.fab.backgroundTintList = null
        binding.fab.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, model.contentColorRes))
        binding.fab.contentDescription = getString(model.textResId)
    }

    private fun animateServiceStateChange(previousState: ServiceUiState, newState: ServiceUiState) {
        if (previousState == newState) return

        binding.cardConnection.animate().cancel()
        binding.fab.animate().cancel()
        binding.layoutTest.animate().cancel()

        binding.cardConnection.scaleX = 1f
        binding.cardConnection.scaleY = 1f
        binding.cardConnection.alpha = 1f
        binding.fab.scaleX = 1f
        binding.fab.scaleY = 1f
        binding.fab.translationX = 0f
        binding.fab.translationY = 0f

        updateFabProcessingState(newState, animate = true)
        when (newState) {
            ServiceUiState.STARTING,
            ServiceUiState.STOPPING -> {
                UiMotion.animateStatePulse(
                    view = binding.cardConnection,
                    expandScale = 1.01f,
                    contractScale = 0.995f,
                    duration = MotionTokens.MEDIUM_ANIMATION_DURATION
                )
                UiMotion.animatePulse(binding.viewConnectionBackdrop, pulseScale = 1.05f, duration = MotionTokens.PULSE_QUICK)
            }

            ServiceUiState.RUNNING -> {
                UiMotion.animateStatePulse(
                    view = binding.cardConnection,
                    expandScale = 1.014f,
                    contractScale = 0.996f,
                    duration = MotionTokens.EMPHASIS_DURATION
                )
                UiMotion.animatePulse(binding.layoutConnectionSurface, pulseScale = 1.04f, duration = MotionTokens.PULSE_MEDIUM)
            }

            ServiceUiState.STOPPED -> {
                UiMotion.animateStatePulse(
                    view = binding.cardConnection,
                    expandScale = 1.008f,
                    contractScale = 0.996f,
                    duration = MotionTokens.SHORT_ANIMATION_DURATION
                )
            }
        }
        animateTestButtonAlpha(newState)
    }

    private fun updateFabProcessingState(state: ServiceUiState, animate: Boolean = false) {
        val isProcessing = state == ServiceUiState.STARTING || state == ServiceUiState.STOPPING
        binding.indicatorConnectionProgress.isIndeterminate = isProcessing
        if (animate) {
            UiMotion.animateVisibility(
                view = binding.indicatorConnectionProgress,
                visible = isProcessing,
                translationOffsetDp = 4f,
                duration = MotionTokens.SHORT_ANIMATION_DURATION
            )
            UiMotion.animateVisibility(
                view = binding.fab,
                visible = !isProcessing,
                translationOffsetDp = 4f,
                duration = MotionTokens.SHORT_ANIMATION_DURATION
            )
            binding.viewConnectionBackdrop.animate()
                .alpha(if (isProcessing) 1f else 0.94f)
                .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        } else {
            UiMotion.setVisibility(binding.indicatorConnectionProgress, isProcessing)
            UiMotion.setVisibility(binding.fab, !isProcessing)
            binding.viewConnectionBackdrop.alpha = if (isProcessing) 1f else 0.94f
        }
    }

    private fun updateTestButtonState(state: ServiceUiState) {
        val isRunning = state == ServiceUiState.RUNNING
        val canTest = isRunning && !isCurrentPingTesting
        binding.layoutTest.isEnabled = canTest
        binding.layoutTest.isClickable = canTest
        binding.layoutTest.isFocusable = canTest
        binding.layoutTest.text = null
        binding.layoutTest.contentDescription = getString(
            when {
                isCurrentPingTesting -> R.string.connection_test_testing
                isRunning -> R.string.connection_test_pending
                else -> R.string.connection_test_unavailable
            }
        )
    }

    private fun animateTestButtonAlpha(state: ServiceUiState) {
        val targetAlpha = when {
            state != ServiceUiState.RUNNING -> 0.72f
            isCurrentPingTesting -> 0.88f
            else -> 1f
        }
        if (kotlin.math.abs(binding.layoutTest.alpha - targetAlpha) < 0.01f) {
            binding.layoutTest.alpha = targetAlpha
            return
        }
        binding.layoutTest.animate()
            .alpha(targetAlpha)
            .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun updateToolbarSubtitle() {
        binding.toolbar.subtitle = null
    }

    fun refreshConnectionCard() {
        connectionCardController.render(serviceUiState)
        updateToolbarSubtitle()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.search_view)
        toolbarSearchMenuItem = searchItem
        searchItem.setOnActionExpandListener(createSearchExpandListener())
        searchController.setupSearch(searchItem)
        searchItem.isVisible = false
        toolbarActionViews.clear()
        bindToolbarAction(menu.findItem(R.id.action_add_sheet))
        bindToolbarAction(menu.findItem(R.id.action_more_sheet))
        return super.onCreateOptionsMenu(menu)
    }

    private fun createSearchExpandListener() = object : MenuItem.OnActionExpandListener {
        override fun onMenuItemActionExpand(item: MenuItem): Boolean {
            item.isVisible = true
            searchController.onMenuItemExpanded()
            return true
        }

        override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
            searchController.onMenuItemCollapsed()
            item.isVisible = false
            return true
        }
    }

    private fun bindToolbarAction(menuItem: MenuItem?) {
        val item = menuItem ?: return
        val actionView = layoutInflater.inflate(R.layout.item_toolbar_hub_action, binding.toolbar, false)
        val button = actionView.findViewById<FrameLayout>(R.id.toolbar_action_hub_button)
        val iconView = actionView.findViewById<ImageView>(R.id.toolbar_action_hub_icon)
        iconView.setImageDrawable(item.icon)
        bindToolbarActionButton(button, item.title ?: getString(R.string.action_more)) {
            actionsController.handleOptionsItem(item.itemId)
        }
        toolbarActionViews += button
        item.actionView = actionView
    }

    private fun bindToolbarActionButton(button: FrameLayout, contentDescription: CharSequence, onClick: () -> Unit) {
        UiMotion.attachPressFeedback(button, pressedScale = 0.97f)
        button.contentDescription = contentDescription
        button.setOnClickListener {
            button.hapticClick()
            onClick()
        }
    }

    fun setupHomeSearch(
        menuItem: MenuItem?,
        onQueryChanged: (String) -> Unit,
        onClosed: (() -> Unit)? = null,
        debounceMillis: Long = 0L
    ): SearchView? {
        return setupSearchView(
            menuItem = menuItem,
            onQueryChanged = onQueryChanged,
            onClosed = onClosed,
            debounceMillis = debounceMillis
        )?.also { searchView ->
            searchView.maxWidth = Int.MAX_VALUE
            searchView.setIconifiedByDefault(true)
            searchView.setBackgroundColor(Color.TRANSPARENT)
            searchView.setPadding(0, 0, 0, 0)
            searchView.minimumWidth = 0
            updateToolbarSearchActionLayout(searchView, expanded = false)
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)?.apply {
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_home_on_surface))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_home_on_surface_muted))
                textSize = 14f
            }
            searchView.findViewById<android.view.View>(androidx.appcompat.R.id.search_plate)?.apply {
                background = null
                backgroundTintList = null
            }
            searchView.findViewById<android.view.View>(androidx.appcompat.R.id.search_edit_frame)?.setBackgroundColor(Color.TRANSPARENT)
            searchView.findViewById<android.view.View>(androidx.appcompat.R.id.submit_area)?.setBackgroundColor(Color.TRANSPARENT)
            searchView.findViewById<ImageView?>(androidx.appcompat.R.id.search_button)?.let { iconButton ->
                styleToolbarSearchIconButton(iconButton)
            }
            searchView.findViewById<ImageView?>(androidx.appcompat.R.id.search_close_btn)?.let { iconButton ->
                styleToolbarSearchIconButton(iconButton)
            }
            listOf(
                androidx.appcompat.R.id.search_mag_icon,
                androidx.appcompat.R.id.search_go_btn,
                androidx.appcompat.R.id.search_voice_btn
            ).forEach { id ->
                searchView.findViewById<ImageView?>(id)?.apply {
                    imageTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.color_home_on_surface_muted))
                }
            }
        }
    }

    private fun styleToolbarSearchIconButton(iconButton: ImageView) {
        val buttonSize = resources.getDimensionPixelSize(R.dimen.view_height_dp36)
        val iconInset = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp9)
        iconButton.layoutParams = iconButton.layoutParams.apply {
            width = buttonSize
            height = buttonSize
        }
        (iconButton.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.marginStart = 0
            params.marginEnd = 0
            iconButton.layoutParams = params
        }
        iconButton.background = ContextCompat.getDrawable(this, R.drawable.bg_home_icon_circle_ripple)
        iconButton.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_home_on_surface_muted))
        iconButton.scaleType = ImageView.ScaleType.CENTER
        iconButton.setPadding(iconInset, iconInset, iconInset, iconInset)
        UiMotion.attachPressFeedback(iconButton, pressedScale = 0.97f)
    }

    private fun openToolbarSearch() {
        val item = toolbarSearchMenuItem ?: return
        item.isVisible = true
        item.expandActionView()
    }

    fun updateToolbarSearchActionLayout(searchView: SearchView, expanded: Boolean) {
        val buttonSize = resources.getDimensionPixelSize(R.dimen.view_height_dp36)
        val layoutParams = searchView.layoutParams ?: Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            buttonSize
        )
        searchView.layoutParams = layoutParams.apply {
            width = if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            height = buttonSize
        }
        searchView.minimumWidth = 0
        updateToolbarActionVisibility(!expanded)
        searchView.requestLayout()
    }

    private fun updateToolbarActionVisibility(visible: Boolean) {
        toolbarActionViews.forEach { actionView ->
            actionView.isVisible = visible
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        else -> if (actionsController.handleOptionsItem(item.itemId)) true else super.onOptionsItemSelected(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openMorePage() {
        launchActivityWithDefaultTransition(requestActivityLauncher, Intent(this, MoreActivity::class.java))
    }

    override fun onDestroy() {
        toolbarController.clear()
        groupTabsController.onDestroy()
        super.onDestroy()
    }
}
