package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MmkvPreferenceDataStore
import com.v2ray.ang.util.Utils
import java.util.concurrent.TimeUnit

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_settings, showHomeAsUp = true, title = getString(R.string.title_settings))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_settings, menu)
        val searchItem = menu.findItem(R.id.action_search)
        setupSearchView(
            menuItem = searchItem,
            hint = getString(R.string.hint_search_settings),
            onQueryChanged = { query ->
                getSettingsFragment()?.filterPreferences(query)
            }
        )

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                getSettingsFragment()?.filterPreferences("")
                return true
            }
        })
        return true
    }

    private fun getSettingsFragment(): SettingsFragment? {
        return supportFragmentManager.findFragmentById(R.id.fragment_settings) as? SettingsFragment
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private val DEFAULT_EXPANDED_CHILDREN = mapOf(
                "category_connection_settings" to 9,
                "category_advanced_settings" to 4,
            )
        }

        private val localDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_LOCAL_DNS_ENABLED) }
        private val fakeDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FAKE_DNS_ENABLED) }
        private val appendHttpProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_APPEND_HTTP_PROXY) }

        //        private val localDnsPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_LOCAL_DNS_PORT) }
        private val vpnDns by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_DNS) }
        private val vpnBypassLan by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_BYPASS_LAN) }
        private val vpnInterfaceAddress by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX) }
        private val vpnMtu by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_MTU) }

        private val mux by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_MUX_ENABLED) }
        private val muxConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_CONCURRENCY) }
        private val muxXudpConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_XUDP_CONCURRENCY) }
        private val muxXudpQuic by lazy { findPreference<ListPreference>(AppConfig.PREF_MUX_XUDP_QUIC) }

        private val fragment by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FRAGMENT_ENABLED) }
        private val fragmentPackets by lazy { findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS) }
        private val fragmentLength by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH) }
        private val fragmentInterval by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL) }

        private val autoUpdateCheck by lazy { findPreference<CheckBoxPreference>(AppConfig.SUBSCRIPTION_AUTO_UPDATE) }
        private val autoUpdateInterval by lazy { findPreference<EditTextPreference>(AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL) }
        private val mode by lazy { findPreference<ListPreference>(AppConfig.PREF_MODE) }

        private val hevTunLogLevel by lazy { findPreference<ListPreference>(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) }
        private val hevTunRwTimeout by lazy { findPreference<EditTextPreference>(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) }

        override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
            return GroupedSettingsAdapter(preferenceScreen)
        }

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            // Use MMKV as the storage backend for all Preferences
            // This prevents inconsistencies between SharedPreferences and MMKV
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            addPreferencesFromResource(R.xml.pref_settings)
            preferenceScreen?.let { applyPreferenceVisuals(it) }
            updateExpandableSections(isSearchActive = false)

            initPreferenceSummaries()

            localDns?.setOnPreferenceChangeListener { _, any ->
                updateLocalDns(any as Boolean)
                true
            }

            mux?.setOnPreferenceChangeListener { _, newValue ->
                updateMux(newValue as Boolean)
                true
            }
            muxConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxConcurrency(newValue as String)
                true
            }
            muxXudpConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxXudpConcurrency(newValue as String)
                true
            }

            fragment?.setOnPreferenceChangeListener { _, newValue ->
                updateFragment(newValue as Boolean)
                true
            }

            autoUpdateCheck?.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as Boolean
                autoUpdateCheck?.isChecked = value
                autoUpdateInterval?.isEnabled = value
                autoUpdateInterval?.text?.toLongEx()?.let {
                    if (newValue) configureUpdateTask(it) else cancelUpdateTask()
                }
                true
            }
            mode?.setOnPreferenceChangeListener { pref, newValue ->
                val valueStr = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(valueStr)
                    lp.summary = if (idx >= 0) lp.entries[idx] else valueStr
                }
                updateMode(valueStr)
                true
            }
            mode?.dialogLayoutResource = R.layout.preference_with_help_link

        }


        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.apply {
                clipToPadding = false
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16),
                    resources.getDimensionPixelSize(R.dimen.padding_spacing_dp8),
                    resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16),
                    resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16)
                )
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.md_theme_background))
                itemAnimator = null
            }
            setDivider(null)
            setDividerHeight(0)
            view.post {
                UiMotion.animateEntrance(
                    view = listView,
                    translationOffsetDp = 8f,
                    startDelay = 48L,
                    duration = MotionTokens.MEDIUM_ANIMATION_DURATION
                )
            }
        }

        private fun applyPreferenceVisuals(group: PreferenceGroup, depth: Int = 0) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.isIconSpaceReserved = false
                when {
                    preference is PreferenceCategory -> {
                        preference.layoutResource = if (depth == 0) {
                            R.layout.preference_category_primary
                        } else {
                            R.layout.preference_category_modern
                        }
                        applyPreferenceVisuals(preference, depth + 1)
                    }
                    preference is PreferenceGroup -> {
                        preference.layoutResource = R.layout.preference_item_modern
                        applyPreferenceVisuals(preference, depth + 1)
                    }
                    else -> {
                        preference.layoutResource = R.layout.preference_item_modern
                    }
                }
            }
        }

        private fun initPreferenceSummaries() {
            fun updateSummary(pref: androidx.preference.Preference) {
                when (pref) {
                    is EditTextPreference -> {
                        pref.summary = pref.text.orEmpty()
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            p.summary = (newValue as? String).orEmpty()
                            true
                        }
                    }

                    is ListPreference -> {
                        pref.summary = pref.entry ?: ""
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            val lp = p as ListPreference
                            val idx = lp.findIndexOfValue(newValue as? String)
                            lp.summary = (if (idx >= 0) lp.entries[idx] else newValue) as CharSequence?
                            true
                        }
                    }

                    is CheckBoxPreference, is androidx.preference.SwitchPreferenceCompat -> {
                    }
                }
            }

            fun traverse(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val p = group.getPreference(i)) {
                        is androidx.preference.PreferenceGroup -> traverse(p)
                        else -> updateSummary(p)
                    }
                }
            }

            preferenceScreen?.let { traverse(it) }
        }

        override fun onStart() {
            super.onStart()

            // Initialize mode-dependent UI states
            updateMode(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN))

            // Initialize mux-dependent UI states
            updateMux(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false))

            // Initialize fragment-dependent UI states
            updateFragment(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))

            // Initialize auto-update interval state
            autoUpdateInterval?.isEnabled = MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, false)
        }

        fun filterPreferences(query: String?) {
            val keyword = query?.trim()?.lowercase().orEmpty()
            val showAll = keyword.isBlank()
            preferenceScreen?.let { screen ->
                filterGroup(screen, keyword, forceVisible = showAll)
            }
            updateExpandableSections(isSearchActive = keyword.isNotBlank())
        }

        private fun updateExpandableSections(isSearchActive: Boolean) {
            preferenceScreen ?: return
            DEFAULT_EXPANDED_CHILDREN.forEach { (key, defaultCount) ->
                findPreference<PreferenceCategory>(key)?.initialExpandedChildrenCount =
                    if (isSearchActive) Int.MAX_VALUE else defaultCount
            }
        }

        private fun filterGroup(group: PreferenceGroup, keyword: String, forceVisible: Boolean): Boolean {
            val groupMatches = !forceVisible && matchesPreference(group, keyword)
            val childForceVisible = forceVisible || groupMatches
            var anyVisible = false

            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                val visible = when (preference) {
                    is PreferenceGroup -> filterGroup(preference, keyword, childForceVisible)
                    else -> childForceVisible || matchesPreference(preference, keyword)
                }
                preference.isVisible = visible
                if (visible) anyVisible = true
            }

            return anyVisible || groupMatches || forceVisible
        }

        private fun matchesPreference(preference: androidx.preference.Preference, keyword: String): Boolean {
            if (keyword.isBlank()) return true
            val title = preference.title?.toString()?.lowercase().orEmpty()
            val summary = preference.summary?.toString()?.lowercase().orEmpty()
            return title.contains(keyword) || summary.contains(keyword)
        }

        private fun updateMode(value: String?) {
            val vpn = value == VPN
            localDns?.isEnabled = vpn
            fakeDns?.isEnabled = vpn
            appendHttpProxy?.isEnabled = vpn
//            localDnsPort?.isEnabled = vpn
            vpnDns?.isEnabled = vpn
            vpnBypassLan?.isEnabled = vpn
            vpnInterfaceAddress?.isEnabled = vpn
            vpnMtu?.isEnabled = vpn
            updateHevTunSettings(false)
            if (vpn) {
                updateLocalDns(
                    MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_LOCAL_DNS_ENABLED,
                        false
                    )
                )
                updateHevTunSettings(true)
            }
        }

        private fun updateLocalDns(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
//            localDnsPort?.isEnabled = enabled
            vpnDns?.isEnabled = !enabled
        }

        private fun configureUpdateTask(interval: Long) {
            AngApplication.application.ensureWorkManagerInitialized()
            val workManager = WorkManager.getInstance(AngApplication.application)
            workManager.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            workManager.enqueueUniquePeriodicWork(
                AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(
                    SubscriptionUpdater.UpdateTask::class.java,
                    interval,
                    TimeUnit.MINUTES
                )
                    .apply {
                        setInitialDelay(interval, TimeUnit.MINUTES)
                    }
                    .build()
            )
        }

        private fun cancelUpdateTask() {
            AngApplication.application.ensureWorkManagerInitialized()
            WorkManager.getInstance(AngApplication.application)
                .cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
        }

        private fun updateMux(enabled: Boolean) {
            muxConcurrency?.isEnabled = enabled
            muxXudpConcurrency?.isEnabled = enabled
            muxXudpQuic?.isEnabled = enabled
            if (enabled) {
                updateMuxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"))
                updateMuxXudpConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"))
            }
        }

        private fun updateMuxConcurrency(value: String?) {
            val concurrency = value?.toIntOrNull() ?: 8
            muxConcurrency?.summary = concurrency.toString()
        }


        private fun updateMuxXudpConcurrency(value: String?) {
            if (value == null) {
                muxXudpQuic?.isEnabled = true
            } else {
                val concurrency = value.toIntOrNull() ?: 8
                muxXudpConcurrency?.summary = concurrency.toString()
                muxXudpQuic?.isEnabled = concurrency >= 0
            }
        }

        private fun updateFragment(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
        }

        private fun updateHevTunSettings(enabled: Boolean) {
            hevTunLogLevel?.isEnabled = enabled
            hevTunRwTimeout?.isEnabled = enabled
        }

        private fun isPreferenceRow(preference: androidx.preference.Preference?): Boolean {
            return preference != null &&
                preference !is PreferenceCategory
        }

        private inner class GroupedSettingsAdapter(
            preferenceScreen: PreferenceScreen
        ) : PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val preference = getItem(position)
                if (!isPreferenceRow(preference)) return

                val itemSurface = holder.findViewById(R.id.settings_item_surface) ?: return
                val divider = holder.findViewById(R.id.settings_row_divider)

                val isFirstInSection = !isPreferenceRow(getItemOrNull(position - 1))
                val isLastInSection = !isPreferenceRow(getItemOrNull(position + 1))
                val backgroundRes = when {
                    isFirstInSection && isLastInSection -> R.drawable.bg_settings_row_single
                    isFirstInSection -> R.drawable.bg_settings_row_top
                    isLastInSection -> R.drawable.bg_settings_row_bottom
                    else -> R.drawable.bg_settings_row_middle
                }
                itemSurface.background = ContextCompat.getDrawable(requireContext(), backgroundRes)
                divider?.isVisible = !isLastInSection
            }

            private fun getItemOrNull(position: Int): androidx.preference.Preference? {
                if (position !in 0 until itemCount) return null
                return super.getItem(position)
            }
        }
    }

    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.APP_WIKI_MODE)
    }
}
