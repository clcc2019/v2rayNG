package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.LogcatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatActivity : BaseActivity(), SwipeRefreshLayout.OnRefreshListener {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }
    private val viewModel: LogcatViewModel by viewModels()
    private lateinit var adapter: LogcatRecyclerAdapter
    private var filterJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_logcat))

        adapter = LogcatRecyclerAdapter(::onLogLongClick)

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setColorSchemeResources(R.color.md_theme_primary)
        binding.refreshLayout.setProgressBackgroundColorSchemeResource(R.color.md_theme_surfaceVariant)
        binding.refreshLayout.setOnRefreshListener(this)

        toast(getString(R.string.pull_down_to_refresh))
    }

    private fun onLogLongClick(log: String): Boolean {
        Utils.setClipboard(this, log)
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logcat, menu)
        setupSearchView(
            menuItem = menu.findItem(R.id.search_view),
            onQueryChanged = {
                scheduleFilter(it)
            },
            onClosed = {
                scheduleFilter("")
            },
            debounceMillis = 150L
        )
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.copy_all -> {
            lifecycleScope.launch(Dispatchers.Default) {
                val all = viewModel.getAll().joinToString("\n")
                withContext(Dispatchers.Main) {
                    Utils.setClipboard(this@LogcatActivity, all)
                    toastSuccess(R.string.toast_success)
                }
            }
            true
        }

        R.id.clear_all -> {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.clearLogcat()
                withContext(Dispatchers.Main) {
                    refreshData()
                }
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.loadLogcat()
            withContext(Dispatchers.Main) {
                binding.refreshLayout.isRefreshing = false
                refreshData()
            }
        }
    }

    fun refreshData() {
        adapter.submitList(viewModel.getAll())
    }

    private fun scheduleFilter(content: String) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch(Dispatchers.Default) {
            viewModel.filter(content)
            withContext(Dispatchers.Main) {
                refreshData()
            }
        }
    }
}
