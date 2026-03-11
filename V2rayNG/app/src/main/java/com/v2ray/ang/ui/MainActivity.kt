package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.navigation.NavigationView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.StartupTracer
import com.v2ray.ang.viewmodel.MainViewModel

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
    }

    private data class ActionButtonUiModel(
        @StringRes val textResId: Int,
        val iconRes: Int,
        val backgroundColorRes: Int,
        val contentColorRes: Int,
        val strokeWidth: Int,
        val strokeColorRes: Int? = null
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
        MainSearchController(this, groupTabsController, mainViewModel) {
            updateConnectionCardVisibility()
        }
    }
    private val actionsController by lazy {
        MainActionsController(
            activity = this,
            mainViewModel = mainViewModel,
            onSetupGroupTabs = { groupTabsController.setupGroupTabs() }
        )
    }
    private var serviceUiState = ServiceUiState.STOPPED
    private var defaultViewPagerBottomPadding = 0
    private var defaultConnectionCardBottomMargin = 0
    private var isImeVisible = false
    private var isCurrentPingTesting = false
    private var pendingRestart = false
    private val toolbarController by lazy {
        MainToolbarController(
            activity = this,
            binding = binding,
            motionInterpolator = motionInterpolator,
            onOpenDrawer = { binding.drawerLayout.openDrawer(GravityCompat.START) },
            statusProvider = { ToolbarStatusState(serviceUiState, isCurrentPingTesting) }
        )
    }
    private val motionInterpolator = FastOutSlowInInterpolator()
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
        StartupTracer.beginSection("MainActivity.onCreate")
        try {
            configureLaunchSplashScreen()
            super.onCreate(savedInstanceState)
            setContentView(binding.root)
            setupToolbar(binding.toolbar, false, getString(R.string.app_name))
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.setHomeButtonEnabled(false)
            binding.toolbar.title = null
            binding.toolbar.subtitle = null
            binding.toolbar.logo = null
            binding.toolbar.logoDescription = getString(R.string.app_name)
            binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.md_theme_primary))
            binding.toolbar.setSubtitleTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            toolbarController.attach()
            toolbarController.updateStatus(serviceUiState, isCurrentPingTesting)
            defaultViewPagerBottomPadding = binding.viewPager.paddingBottom
            defaultConnectionCardBottomMargin = (binding.cardConnection.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin

            // setup viewpager and tablayout
            groupTabsController.initialize()

            // setup navigation drawer
            val toggle = ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
            )
            toggle.isDrawerIndicatorEnabled = false
            binding.toolbar.navigationIcon = null
            setupDrawerMotion(toggle)
            toggle.syncState()
            binding.toolbar.navigationIcon = null
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
            schedulePostLaunchWork()
            StartupTracer.mark("MainActivity.onCreate.end")
        } finally {
            StartupTracer.endSection()
        }
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
            val iconView = splashScreenView.iconView

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
                val shiftPx = resources.displayMetrics.density * MotionTokens.DRAWER_SHIFT_DP * progress
                val contentScale = 1f - (MotionTokens.DRAWER_CONTENT_SCALE_DELTA * progress)
                val toolbarScale = 1f - (MotionTokens.DRAWER_TOOLBAR_SCALE_DELTA * progress)

                binding.toolbar.translationX = shiftPx * 0.45f
                binding.toolbar.scaleX = toolbarScale
                binding.toolbar.scaleY = toolbarScale
                binding.mainContent.translationX = shiftPx
                binding.mainContent.scaleX = contentScale
                binding.mainContent.scaleY = contentScale
                binding.mainContent.alpha = 1f - (MotionTokens.DRAWER_CONTENT_ALPHA_DELTA * progress)
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
        binding.viewPager.setPageTransformer { page, position ->
            val absPos = kotlin.math.abs(position).coerceAtMost(1f)
            page.alpha = 1f - (0.08f * absPos)
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && imeInsets.bottom > systemBars.bottom
            isImeVisible = imeVisible
            searchController.onInsetsChanged(insets)

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
        connectionCardController.updateVisibility(shouldShowConnectionCard())
    }

    private fun shouldShowConnectionCard(): Boolean {
        return !isImeVisible && !searchController.isSearchActive()
    }

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
                isCurrentPingTesting = false
                updateTestButtonState(serviceUiState)
                animateTestButtonAlpha(serviceUiState)
                toolbarController.updateStatus(serviceUiState, isCurrentPingTesting)
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
            if (!isRunning && pendingRestart) {
                pendingRestart = false
                startAfterRestart()
            }
        }
    }

    private fun setupGroupTab() {
        groupTabsController.setupGroupTabs()
    }
    fun onServerListScrolled(dy: Int, canScrollUp: Boolean) {
        groupTabsController.onServerListScrolled(dy, canScrollUp)
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

    private fun startAfterRestart() {
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                renderServiceUiState(ServiceUiState.STARTING)
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
            return
        }
        renderServiceUiState(ServiceUiState.STARTING)
        startV2Ray()
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            if (isCurrentPingTesting) {
                return
            }
            isCurrentPingTesting = true
            binding.layoutTest.hapticVirtualKey()
            updateTestButtonState(serviceUiState)
            animateTestButtonAlpha(serviceUiState)
            toolbarController.updateStatus(serviceUiState, isCurrentPingTesting)
            UiMotion.animatePulse(binding.layoutTest, pulseScale = 1.02f, duration = MotionTokens.PULSE_MEDIUM)
            toast(R.string.connection_test_testing)
            mainViewModel.testCurrentServerRealPing()
        } else {
            binding.layoutTest.hapticClick()
            toast(R.string.connection_test_unavailable)
        }
    }

    fun restartV2Ray() {
        if (V2RayServiceManager.isRunning()) {
            pendingRestart = true
            renderServiceUiState(ServiceUiState.STOPPING)
            V2RayServiceManager.stopVService(this)
        } else {
            startAfterRestart()
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
        updateTestButtonState(state)
        connectionCardController.render(state)
        connectionCardController.updateStateVisuals(state, animate = previousState != state)
        updateToolbarSubtitle()
        toolbarController.updateStatus(serviceUiState, isCurrentPingTesting)
        animateServiceStateChange(previousState, state)
        performServiceStateHaptic(previousState, state)
        applyActionButtonUiModel(buildActionButtonUiModel(state))
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
            .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                binding.fab.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(motionInterpolator)
                    .start()
            }
            .start()

        animateTestButtonAlpha(newState)
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
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchController.onMenuItemExpanded()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchController.onMenuItemCollapsed()
                return true
            }
        })
        searchController.setupSearch(searchItem)
        return super.onCreateOptionsMenu(menu)
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
        )
    }

    fun showLoadingIndicator() {
        showLoading()
    }

    fun hideLoadingIndicator() {
        hideLoading()
    }

    fun launchConfigFileChooser(onResult: (Uri?) -> Unit) {
        launchFileChooser(onResult = onResult)
    }

    fun launchQrScanner(onResult: (String?) -> Unit) {
        launchQRCodeScanner(onResult)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        else -> if (actionsController.handleOptionsItem(item.itemId)) true else super.onOptionsItemSelected(item)
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
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        toolbarController.clear()
        groupTabsController.onDestroy()
        super.onDestroy()
    }
}
