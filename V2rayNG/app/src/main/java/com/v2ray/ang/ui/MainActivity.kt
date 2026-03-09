package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
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
    }

    private enum class ServiceUiState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var serviceUiState = ServiceUiState.STOPPED
    private var isGroupTabHidden = false
    private val motionInterpolator = FastOutSlowInInterpolator()
    private val groupTabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) = syncGroupTabSelection()
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
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        setupNavigationDrawerInsets()
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

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()
        refreshConnectionCard()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
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

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { result ->
            val message = compactTestResult(result)
            if (message.isNotBlank()) {
                toast(message)
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            renderServiceUiState(if (isRunning) ServiceUiState.RUNNING else ServiceUiState.STOPPED)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)
        configureGroupTabLayout(groups.size)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.customView = createGroupTabView(it)
                tab.tag = it.id
            }
        }.also { it.attach() }

        binding.tabGroup.removeOnTabSelectedListener(groupTabSelectedListener)
        binding.tabGroup.addOnTabSelectedListener(groupTabSelectedListener)

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)
        syncGroupTabSelection()
        binding.tabGroup.post { applyCompactGroupTabHeight() }

        binding.tabGroup.isVisible = groups.size > 1
        binding.cardTabGroup.isVisible = groups.size > 1
        setGroupTabVisible(groups.size > 1, immediate = true)
        binding.cardTabGroup.post { updateGroupTabContentInset() }
        refreshConnectionCard()
    }

    private fun configureGroupTabLayout(groupCount: Int) {
        val useFixedLayout = groupCount in 2..3
        binding.tabGroup.tabMode = if (useFixedLayout) TabLayout.MODE_FIXED else TabLayout.MODE_SCROLLABLE
        binding.tabGroup.tabGravity = if (useFixedLayout) TabLayout.GRAVITY_FILL else TabLayout.GRAVITY_CENTER

        val cardHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp32)
        val cardTopMargin = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp8)
        val cardLayoutParams = binding.cardTabGroup.layoutParams as CoordinatorLayout.LayoutParams
        cardLayoutParams.width = CoordinatorLayout.LayoutParams.MATCH_PARENT
        cardLayoutParams.height = cardHeight
        cardLayoutParams.topMargin = cardTopMargin
        val sideMargin = resources.getDimensionPixelSize(
            if (useFixedLayout) R.dimen.padding_spacing_dp20 else R.dimen.padding_spacing_dp16
        )
        cardLayoutParams.marginStart = sideMargin
        cardLayoutParams.marginEnd = sideMargin
        binding.cardTabGroup.layoutParams = cardLayoutParams

        binding.tabGroup.layoutParams = binding.tabGroup.layoutParams.apply {
            width = CoordinatorLayout.LayoutParams.MATCH_PARENT
            height = cardHeight
        }
        binding.tabGroup.minimumHeight = 0
        binding.tabGroup.requestLayout()
    }

    private fun createGroupTabView(group: GroupMapItem): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_tab_group, binding.tabGroup, false)
        view.tag = group
        view.findViewById<TextView>(R.id.tv_tab_label).text = buildGroupTabText(group, selected = false)
        return view
    }

    private fun syncGroupTabSelection() {
        for (index in 0 until binding.tabGroup.tabCount) {
            val tab = binding.tabGroup.getTabAt(index) ?: continue
            val customView = tab.customView ?: continue
            val isSelected = tab.isSelected
            customView.isSelected = isSelected
            customView.animate()
                .scaleX(if (isSelected) 1f else 0.97f)
                .scaleY(if (isSelected) 1f else 0.97f)
                .setDuration(SHORT_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()

            val group = customView.tag as? GroupMapItem ?: continue
            val label = customView.findViewById<TextView>(R.id.tv_tab_label)
            label.text = buildGroupTabText(group, selected = isSelected)
            label.alpha = if (isSelected) 1f else 0.88f
        }
    }

    private fun applyCompactGroupTabHeight() {
        val compactHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp30)
        binding.tabGroup.minimumHeight = compactHeight

        val slidingTabIndicator = binding.tabGroup.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until slidingTabIndicator.childCount) {
            val tabView = slidingTabIndicator.getChildAt(index)
            tabView.minimumHeight = compactHeight
            tabView.layoutParams = tabView.layoutParams.apply {
                height = compactHeight
            }
            tabView.setPadding(tabView.paddingLeft, 0, tabView.paddingRight, 0)
            tabView.requestLayout()
        }
    }

    private fun buildGroupTabText(group: GroupMapItem, selected: Boolean): CharSequence {
        val labelColor = ContextCompat.getColor(
            this,
            if (selected) R.color.md_theme_onSecondaryContainer else R.color.md_theme_onSurfaceVariant
        )
        val counterColor = ContextCompat.getColor(
            this,
            if (selected) R.color.md_theme_primary else R.color.md_theme_onSurfaceVariant
        )

        return SpannableStringBuilder(group.remarks).apply {
            setSpan(ForegroundColorSpan(labelColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (group.count > 0) {
                val start = length
                append("  ${group.count}")
                setSpan(ForegroundColorSpan(counterColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.88f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
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
        val listGap = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp12)
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

        animateProgressBar(isTransitioning)
        binding.fab.isEnabled = !isTransitioning
        binding.fab.isClickable = !isTransitioning
        binding.fab.alpha = if (isTransitioning) 0.92f else 1f
        binding.layoutTest.isEnabled = state == ServiceUiState.RUNNING
        binding.layoutTest.isClickable = state == ServiceUiState.RUNNING
        binding.layoutTest.isFocusable = state == ServiceUiState.RUNNING
        binding.layoutTest.alpha = if (state == ServiceUiState.RUNNING) 1f else 0.72f
        updateConnectionCard(state)
        updateToolbarSubtitle(state)
        animateServiceStateChange(previousState, state)

        when (state) {
            ServiceUiState.STARTING -> {
                binding.fab.setIconResource(R.drawable.ic_play_24dp)
                binding.fab.text = getString(R.string.connection_starting)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primaryContainer))
                binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer))
                binding.fab.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer))
                binding.fab.strokeWidth = 0
                binding.fab.contentDescription = getString(R.string.connection_starting)
            }

            ServiceUiState.STOPPING -> {
                binding.fab.setIconResource(R.drawable.ic_stop_24dp)
                binding.fab.text = getString(R.string.connection_stopping)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primaryContainer))
                binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer))
                binding.fab.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer))
                binding.fab.strokeWidth = 0
                binding.fab.contentDescription = getString(R.string.connection_stopping)
            }

            ServiceUiState.RUNNING -> {
                binding.fab.setIconResource(R.drawable.ic_stop_24dp)
                binding.fab.text = getString(R.string.action_stop_service)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primaryContainer))
                binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer))
                binding.fab.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimaryContainer))
                binding.fab.strokeWidth = 0
                binding.fab.contentDescription = getString(R.string.action_stop_service)
            }

            ServiceUiState.STOPPED -> {
                binding.fab.setIconResource(R.drawable.ic_play_24dp)
                binding.fab.text = getString(R.string.tasker_start_service)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_surface))
                binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_onSurface))
                binding.fab.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface))
                binding.fab.strokeWidth = 1
                binding.fab.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_outlineVariant))
                binding.fab.contentDescription = getString(R.string.tasker_start_service)
            }
        }
    }

    private fun animateProgressBar(visible: Boolean) {
        binding.progressBar.animate().cancel()
        if (visible) {
            if (!binding.progressBar.isVisible) {
                binding.progressBar.alpha = 0f
                binding.progressBar.visibility = View.VISIBLE
            }
            binding.progressBar.animate()
                .alpha(1f)
                .setDuration(MEDIUM_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        } else if (binding.progressBar.isVisible) {
            binding.progressBar.animate()
                .alpha(0f)
                .setDuration(MEDIUM_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .withEndAction {
                    binding.progressBar.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    private fun animateServiceStateChange(previousState: ServiceUiState, newState: ServiceUiState) {
        if (previousState == newState) return

        binding.cardConnection.animate().cancel()
        binding.fab.animate().cancel()
        binding.layoutTest.animate().cancel()

        binding.cardConnection.scaleX = 0.985f
        binding.cardConnection.scaleY = 0.985f
        binding.cardConnection.alpha = 0.94f
        binding.cardConnection.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(MEDIUM_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .start()

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

    private fun updateToolbarSubtitle(state: ServiceUiState) {
        val selectedName = getSelectedServerName().orEmpty()

        binding.toolbar.subtitle = when (state) {
            ServiceUiState.STARTING -> getString(R.string.connection_starting)
            ServiceUiState.STOPPING -> getString(R.string.connection_stopping)
            ServiceUiState.RUNNING -> selectedName.ifEmpty { getString(R.string.connection_connected) }
            ServiceUiState.STOPPED -> selectedName.ifEmpty { null }
        }
    }

    fun refreshConnectionCard() {
        updateConnectionCard(serviceUiState)
        updateToolbarSubtitle(serviceUiState)
    }

    private fun updateConnectionCard(state: ServiceUiState) {
        val profile = getSelectedProfile()
        val selectedName = profile?.remarks?.takeIf { it.isNotBlank() }
        binding.tvActiveServer.text = selectedName ?: getString(R.string.connection_not_connected)
        binding.tvConnectionLabel.text = getString(R.string.current_config)
        binding.tvConfigType.text = profile?.configType?.name.orEmpty()
        binding.tvConfigType.isVisible = profile != null

        val configBadgeText = buildConfigBadgeText(profile)
        binding.tvConfigMeta.text = configBadgeText
        binding.tvConfigMeta.isVisible = !configBadgeText.isNullOrBlank()

        val serverAddress = buildConnectionAddress(profile)
        binding.tvConnectionAddress.text = serverAddress ?: getString(R.string.connection_not_connected)

        val metaLine = buildConnectionMetaLine(profile)
        binding.tvConnectionMetaLine.text = metaLine
        binding.tvConnectionMetaLine.isVisible = !metaLine.isNullOrBlank()

        val statusText = when (state) {
            ServiceUiState.STARTING -> getString(R.string.connection_starting)
            ServiceUiState.STOPPING -> getString(R.string.connection_stopping)
            ServiceUiState.RUNNING -> getString(R.string.connection_connected)
            ServiceUiState.STOPPED -> getString(R.string.connection_not_connected)
        }
        binding.tvConnectionBadge.text = statusText

        val badgeColorRes = when (state) {
            ServiceUiState.STARTING -> R.color.md_theme_onPrimaryContainer
            ServiceUiState.STOPPING -> R.color.md_theme_onSurfaceVariant
            ServiceUiState.RUNNING -> R.color.md_theme_onTertiaryContainer
            ServiceUiState.STOPPED -> R.color.md_theme_onSurfaceVariant
        }
        val badgeBackgroundRes = when (state) {
            ServiceUiState.STARTING -> R.color.md_theme_primaryContainer
            ServiceUiState.STOPPING -> R.color.md_theme_surfaceVariant
            ServiceUiState.RUNNING -> R.color.md_theme_tertiaryContainer
            ServiceUiState.STOPPED -> R.color.md_theme_surfaceVariant
        }
        DrawableCompat.setTint(binding.tvConnectionBadge.background.mutate(), ContextCompat.getColor(this, badgeBackgroundRes))
        binding.tvConnectionBadge.setTextColor(ContextCompat.getColor(this, badgeColorRes))
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

        binding.cardConnection.setCardBackgroundColor(
            ContextCompat.getColor(this, if (state == ServiceUiState.RUNNING) R.color.colorSelectionFill else R.color.md_theme_surface)
        )
        binding.cardConnection.setStrokeColor(
            ContextCompat.getColor(
                this,
                if (state == ServiceUiState.RUNNING) R.color.colorSelectionIndicator else R.color.md_theme_outlineVariant
            )
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

    private fun buildConfigBadgeText(profile: ProfileItem?): String? {
        val network = profile?.network?.trim().orEmpty()
        return network.takeIf { it.isNotEmpty() }?.uppercase()
    }

    private fun buildConnectionMetaLine(profile: ProfileItem?): String? {
        if (profile == null) return null
        val details = linkedSetOf<String>()
        profile.security?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }?.let {
            details += it.uppercase()
        }
        profile.flow?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        profile.method?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        profile.host?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        return details.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun getSelectedServerName(): String? {
        return getSelectedProfile()?.remarks
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        setupSearchView(
            menuItem = menu.findItem(R.id.search_view),
            onQueryChanged = { mainViewModel.filterConfig(it) },
            onClosed = { mainViewModel.filterConfig("") }
        )
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }


        else -> super.onOptionsItemSelected(item)
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
