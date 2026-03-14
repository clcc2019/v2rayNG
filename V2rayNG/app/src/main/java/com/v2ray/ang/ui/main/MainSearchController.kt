package com.v2ray.ang.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.viewmodel.MainViewModel

class MainSearchController(
    private val activity: MainActivity,
    private val groupTabsController: MainGroupTabsController,
    private val mainViewModel: MainViewModel,
    private val onSearchUiChanged: (Boolean) -> Unit
) {
    private var homeSearchView: SearchView? = null
    private var lastSearchQuery: String = ""
    private var isSearchUiActive = false

    fun isSearchActive(): Boolean = isSearchUiActive

    fun setupSearch(menuItem: android.view.MenuItem?) {
        homeSearchView = activity.setupHomeSearch(
            menuItem = menuItem,
            onQueryChanged = { query ->
                val trimmed = query.trim()
                val shouldScroll = lastSearchQuery.isBlank() != trimmed.isBlank()
                lastSearchQuery = trimmed
                mainViewModel.filterConfig(trimmed)
                if (shouldScroll) {
                    scrollCurrentServerListToTop()
                }
            },
            onClosed = {
                homeSearchView?.let { activity.updateToolbarSearchActionLayout(it, expanded = false) }
                isSearchUiActive = false
                onSearchUiChanged(false)
                lastSearchQuery = ""
                mainViewModel.filterConfig("")
                dismissSearchFocus()
                scrollCurrentServerListToTop()
            },
            debounceMillis = 180L
        )?.also { searchView ->
            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                updateSearchUiState(hasFocus)
            }
            searchView.setOnSearchClickListener {
                activity.updateToolbarSearchActionLayout(searchView, expanded = true)
                updateSearchUiState(true)
                scrollCurrentServerListToTop()
            }
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                ?.setOnEditorActionListener { v, actionId, _ ->
                    updateSearchUiState(v.hasFocus())
                    if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                        dismissSearchFocus()
                        true
                    } else {
                        false
                    }
                }
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                ?.setOnFocusChangeListener { _, hasFocus ->
                    updateSearchUiState(hasFocus)
                }
        }
    }

    fun onMenuItemCollapsed() {
        homeSearchView?.let { activity.updateToolbarSearchActionLayout(it, expanded = false) }
        updateSearchUiState(false)
        dismissSearchFocus()
    }

    fun onMenuItemExpanded() {
        homeSearchView?.let { activity.updateToolbarSearchActionLayout(it, expanded = true) }
        updateSearchUiState(true)
    }

    fun onInsetsChanged(insets: WindowInsetsCompat) {
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        if (imeInsets.bottom == 0 && isSearchUiActive && homeSearchView?.isIconified == true) {
            updateSearchUiState(false)
        }
    }

    private fun scrollCurrentServerListToTop() {
        groupTabsController.scrollCurrentServerListToTop()
    }

    private fun dismissSearchFocus() {
        homeSearchView?.clearFocus()
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val token = homeSearchView?.windowToken ?: activity.currentFocus?.windowToken
        if (token != null) {
            imm?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun updateSearchUiState(active: Boolean) {
        if (isSearchUiActive == active) {
            return
        }
        isSearchUiActive = active
        onSearchUiChanged(active)
    }

}
