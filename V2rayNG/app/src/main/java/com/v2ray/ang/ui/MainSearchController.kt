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
    private val onSearchUiChanged: () -> Unit
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
                isSearchUiActive = false
                onSearchUiChanged()
                lastSearchQuery = ""
                mainViewModel.filterConfig("")
                dismissSearchFocus()
                scrollCurrentServerListToTop()
            },
            debounceMillis = 180L
        )?.also { searchView ->
            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                isSearchUiActive = hasFocus
                onSearchUiChanged()
            }
            searchView.setOnSearchClickListener {
                isSearchUiActive = true
                onSearchUiChanged()
                scrollCurrentServerListToTop()
            }
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                ?.setOnEditorActionListener { v, actionId, _ ->
                    isSearchUiActive = v.hasFocus()
                    onSearchUiChanged()
                    if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                        dismissSearchFocus()
                        true
                    } else {
                        false
                    }
                }
            searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                ?.setOnFocusChangeListener { _, hasFocus ->
                    isSearchUiActive = hasFocus
                    onSearchUiChanged()
                }
        }
    }

    fun onMenuItemCollapsed() {
        isSearchUiActive = false
        onSearchUiChanged()
        dismissSearchFocus()
    }

    fun onMenuItemExpanded() {
        isSearchUiActive = true
        onSearchUiChanged()
    }

    fun onInsetsChanged(insets: WindowInsetsCompat) {
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        if (imeInsets.bottom == 0 && isSearchUiActive && homeSearchView?.isIconified == true) {
            isSearchUiActive = false
            onSearchUiChanged()
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

}
