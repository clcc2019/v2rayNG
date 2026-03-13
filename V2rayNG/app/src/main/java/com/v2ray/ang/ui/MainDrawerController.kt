package com.v2ray.ang.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding

class MainDrawerController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {
    fun attach(onNavigationItemSelected: NavigationView.OnNavigationItemSelectedListener) {
        val toggle = createDrawerToggle()
        binding.toolbar.navigationIcon = null
        setupDrawerMotion(toggle)
        toggle.syncState()
        binding.toolbar.navigationIcon = null
        binding.navView.setNavigationItemSelectedListener(onNavigationItemSelected)
        setupNavigationDrawerInsets()
        setupBackHandling()
    }

    private fun createDrawerToggle(): ActionBarDrawerToggle {
        return ActionBarDrawerToggle(
            activity,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        ).also { it.isDrawerIndicatorEnabled = false }
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

    private fun setupBackHandling() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupDrawerMotion(toggle: ActionBarDrawerToggle) {
        binding.drawerLayout.addDrawerListener(toggle)
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (drawerView !== binding.navView) return
                val progress = slideOffset.coerceIn(0f, 1f)
                val shiftPx = activity.resources.displayMetrics.density * MotionTokens.DRAWER_SHIFT_DP * progress
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

    private fun applyNavigationDrawerProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val offsetPx = activity.resources.displayMetrics.density * 16f * (1f - clamped)
        val headerView = binding.navView.getHeaderView(0)
        headerView.alpha = clamped
        headerView.translationY = offsetPx
        val menuView = binding.navView.getChildAt(0) as? ViewGroup ?: return
        updateDrawerMenuProgress(menuView, clamped)
    }

    private fun updateDrawerMenuProgress(menuView: ViewGroup, progress: Float) {
        for (index in 0 until menuView.childCount) {
            val child = menuView.getChildAt(index)
            val itemProgress = ((progress - (index * 0.04f)) / 0.92f).coerceIn(0f, 1f)
            child.alpha = itemProgress
            child.translationY = activity.resources.displayMetrics.density * 10f * (1f - itemProgress)
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
}
