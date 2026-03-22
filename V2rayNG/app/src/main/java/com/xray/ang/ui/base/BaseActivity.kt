package com.xray.ang.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xray.ang.AngApplication
import com.xray.ang.R
import com.xray.ang.handler.SettingsManager
import com.xray.ang.helper.CustomDividerItemDecoration
import com.xray.ang.ui.common.startActivityWithDefaultTransition
import com.xray.ang.util.MyContextWrapper
import com.xray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseActivity : AppCompatActivity() {
    private var progressBar: LinearProgressIndicator? = null
    private var shouldRunPageEnterMotion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPreferredRefreshRate()
        shouldRunPageEnterMotion = savedInstanceState == null && ValueAnimator.areAnimatorsEnabled()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (!Utils.getDarkModeStatus(this)) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
            }
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        SystemFontWeightHelper.scheduleApply(window.decorView)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        SystemFontWeightHelper.scheduleApply(window.decorView)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        SystemFontWeightHelper.scheduleApply(window.decorView)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(MyContextWrapper.wrap(newBase ?: return, SettingsManager.getLocale()))
    }

    protected fun addCustomDividerToRecyclerView(recyclerView: RecyclerView, context: Context, drawableResId: Int, orientation: Int = DividerItemDecoration.VERTICAL) {
        val drawable = ContextCompat.getDrawable(context, drawableResId)
        requireNotNull(drawable) { "Drawable resource not found" }
        recyclerView.addItemDecoration(CustomDividerItemDecoration(drawable, orientation))
    }

    protected fun optimizeRecyclerViewForHighRefresh(recyclerView: RecyclerView) {
        when (val animator = recyclerView.itemAnimator) {
            is DefaultItemAnimator -> {
                animator.supportsChangeAnimations = false
                animator.addDuration = 90L
                animator.moveDuration = 100L
                animator.removeDuration = 90L
                animator.changeDuration = 0L
            }

            is SimpleItemAnimator -> animator.supportsChangeAnimations = false
            null -> Unit
        }
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(16)
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        SystemFontWeightHelper.attachToRecyclerView(recyclerView)
    }

    protected fun setupToolbar(toolbar: Toolbar?, showHomeAsUp: Boolean = true, title: CharSequence? = null) {
        val tb = toolbar ?: findViewById<Toolbar?>(R.id.toolbar)
        tb?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(showHomeAsUp)
            title?.let { t -> this.title = t }
            styleToolbar(it)
        }
        progressBar = findViewById(R.id.progress_bar)
    }

    protected fun setContentViewWithToolbar(layoutResId: Int, showHomeAsUp: Boolean = true, title: CharSequence? = null) {
        val base = LayoutInflater.from(this).inflate(R.layout.activity_base, null)
        val container = base.findViewById<FrameLayout>(R.id.content_container)
        LayoutInflater.from(this).inflate(layoutResId, container, true)
        progressBar = base.findViewById(R.id.progress_bar)
        setContentView(base)
        setupToolbar(base, showHomeAsUp, title)
        scheduleBaseScreenEnterMotion(base)
    }

    protected fun setContentViewWithToolbar(childView: View, showHomeAsUp: Boolean = true, title: CharSequence? = null) {
        val base = LayoutInflater.from(this).inflate(R.layout.activity_base, null)
        val container = base.findViewById<FrameLayout>(R.id.content_container)
        container.addView(childView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progressBar = base.findViewById(R.id.progress_bar)
        setContentView(base)
        setupToolbar(base, showHomeAsUp, title)
        scheduleBaseScreenEnterMotion(base)
    }

    protected fun setupSearchView(
        menuItem: MenuItem?,
        hint: CharSequence = getString(R.string.menu_item_search),
        onQueryChanged: (String) -> Unit,
        onClosed: (() -> Unit)? = null,
        debounceMillis: Long = 0L
    ): SearchView? {
        val searchView = menuItem?.actionView as? SearchView ?: return null
        styleSearchView(searchView)
        SystemFontWeightHelper.scheduleApply(searchView)
        searchView.queryHint = hint
        searchView.maxWidth = Int.MAX_VALUE
        var searchJob: Job? = null
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText.orEmpty()
                if (debounceMillis <= 0L) {
                    onQueryChanged(query)
                    return false
                }
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(debounceMillis)
                    onQueryChanged(query)
                }
                return false
            }
        })
        searchView.setOnCloseListener {
            searchJob?.cancel()
            if (onClosed != null) {
                onClosed()
            } else {
                onQueryChanged("")
            }
            false
        }
        return searchView
    }

    private fun setupToolbar(baseRoot: View, showHomeAsUp: Boolean, title: CharSequence?) {
        val toolbar = baseRoot.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(showHomeAsUp)
            title?.let { t -> supportActionBar?.title = t }
            styleToolbar(it)
        }
    }

    private fun styleToolbar(toolbar: Toolbar) {
        val backgroundColor = ContextCompat.getColor(toolbar.context, R.color.md_theme_background)
        val foregroundColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnBackground, ContextCompat.getColor(toolbar.context, R.color.md_theme_onBackground))
        val subtitleColor = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(toolbar.context, R.color.md_theme_onSurfaceVariant))
        toolbar.setBackgroundColor(backgroundColor)
        toolbar.setTitleTextColor(foregroundColor)
        toolbar.setSubtitleTextColor(subtitleColor)
        toolbar.navigationIcon?.setTint(foregroundColor)
        toolbar.overflowIcon?.setTint(foregroundColor)
        toolbar.elevation = 0f
        SystemFontWeightHelper.scheduleApply(toolbar)
    }

    private fun styleSearchView(searchView: SearchView) {
        val surfaceColor = MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorSurfaceVariant, ContextCompat.getColor(this, R.color.md_theme_surfaceVariant))
        val outlineColor = MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOutlineVariant, ContextCompat.getColor(this, R.color.md_theme_outlineVariant))
        val textColor = MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOnSurface, ContextCompat.getColor(this, R.color.md_theme_onSurface))
        val hintColor = MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
        val searchFieldHeight = resources.getDimensionPixelSize(R.dimen.view_height_dp40)
        val cornerRadius = resources.getDimensionPixelSize(R.dimen.shape_corner_dp20).toFloat()
        val strokeWidth = 0
        val horizontalInset = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp12)
        val verticalInset = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp3)
        val iconInset = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp5)
        val textEndInset = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp6)

        val plateDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(surfaceColor)
            setStroke(strokeWidth, outlineColor)
            this.cornerRadius = cornerRadius
        }

        searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)?.let { plate ->
            plate.background = plateDrawable
            plate.minimumHeight = searchFieldHeight
            plate.setPadding(horizontalInset, verticalInset, horizontalInset, verticalInset)
        }

        searchView.findViewById<View>(androidx.appcompat.R.id.search_edit_frame)?.let { editFrame ->
            editFrame.setPadding(0, 0, 0, 0)
        }

        searchView.findViewById<View>(androidx.appcompat.R.id.submit_area)?.let { submitArea ->
            submitArea.setPadding(0, 0, 0, 0)
        }

        searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)?.let { searchText ->
            searchText.setTextColor(textColor)
            searchText.setHintTextColor(hintColor)
            searchText.textSize = 15f
            searchText.setPadding(0, 0, textEndInset, 0)
        }

        listOf(
            androidx.appcompat.R.id.search_button,
            androidx.appcompat.R.id.search_close_btn,
            androidx.appcompat.R.id.search_mag_icon,
            androidx.appcompat.R.id.search_go_btn,
            androidx.appcompat.R.id.search_voice_btn
        ).forEach { id ->
            searchView.findViewById<ImageView?>(id)?.let { imageView ->
                imageView.imageTintList = ColorStateList.valueOf(hintColor)
                imageView.setPadding(iconInset, iconInset, iconInset, iconInset)
            }
        }
    }

    protected fun applyPressMotion(vararg views: View, pressedScale: Float = 0.985f) {
        views.forEach {
            UiMotion.attachPressFeedbackComposite(
                source = it,
                scaleTarget = it,
                alphaTarget = it,
                pressedScale = pressedScale,
                pressedAlpha = 0.96f
            )
        }
    }

    protected fun postEnterMotion(
        view: View,
        translationOffsetDp: Float = 10f,
        startDelay: Long = 0L,
        duration: Long = MotionTokens.MEDIUM_ANIMATION_DURATION
    ) {
        if (shouldSkipNestedPageEnterMotion(view)) {
            UiMotion.settleView(view)
            return
        }
        view.post {
            UiMotion.animateEntrance(
                view = view,
                translationOffsetDp = translationOffsetDp,
                startDelay = startDelay,
                duration = duration
            )
        }
    }

    protected fun postScreenContentEnterMotion(
        root: View,
        translationOffsetDp: Float = 10f,
        startDelay: Long = 36L
    ) {
        if (shouldSkipNestedPageEnterMotion(root)) {
            return
        }
        (root as? ViewGroup)?.getChildAt(0)?.let { child ->
            (child as? ViewGroup)?.let {
                postStaggeredEnterMotion(it, translationOffsetDp = translationOffsetDp, startDelay = startDelay)
            }
        }
    }

    protected fun bindClickAction(
        view: View,
        withHaptic: Boolean = false,
        action: () -> Unit
    ) {
        view.setOnClickListener {
            action()
        }
    }

    protected fun bindLaunchAction(
        view: View,
        withHaptic: Boolean = false,
        intentProvider: () -> Intent
    ) {
        bindClickAction(view, withHaptic) {
            startActivityWithDefaultTransition(intentProvider())
        }
    }

    protected fun postStaggeredEnterMotion(
        container: ViewGroup,
        translationOffsetDp: Float = 10f,
        startDelay: Long = 0L,
        stepDelay: Long = MotionTokens.STAGGER_DELAY
    ) {
        if (shouldSkipNestedPageEnterMotion(container)) {
            return
        }
        container.post {
            UiMotion.animateStaggeredChildren(
                container = container,
                translationOffsetDp = translationOffsetDp,
                stepDelay = stepDelay,
                startDelay = startDelay
            )
        }
    }

    private fun scheduleBaseScreenEnterMotion(baseRoot: View) {
        val content = baseRoot.findViewById<View>(R.id.content_container)
        if (!shouldRunPageEnterMotion) {
            UiMotion.settleView(content)
            return
        }
        baseRoot.post {
            UiMotion.animateEntrance(
                view = content,
                translationOffsetDp = 6f,
                duration = MotionTokens.SHORT_ANIMATION_DURATION
            )
        }
    }

    private fun shouldSkipNestedPageEnterMotion(view: View): Boolean {
        if (!shouldRunPageEnterMotion) {
            return true
        }
        var current: View? = view
        while (current != null) {
            if (current.id == R.id.content_container) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    protected fun showLoading() {
        runOnUiThread {
            progressBar?.visibility = View.VISIBLE
        }
    }

    protected fun hideLoading() {
        runOnUiThread {
            progressBar?.visibility = View.GONE
        }
    }

    fun showLoadingIndicator() {
        showLoading()
    }

    fun hideLoadingIndicator() {
        hideLoading()
    }

    protected fun <T> launchLoadingTask(
        taskContext: CoroutineDispatcher = Dispatchers.IO,
        onError: ((Exception) -> Unit)? = null,
        task: suspend () -> T,
        onSuccess: (T) -> Unit
    ): Job {
        showLoading()
        return lifecycleScope.launch {
            try {
                val result = withContext(taskContext) { task() }
                onSuccess(result)
            } catch (e: Exception) {
                onError?.invoke(e)
            } finally {
                hideLoading()
            }
        }
    }

    protected fun isLoadingVisible(): Boolean {
        return progressBar?.visibility == View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun applyPreferredRefreshRate() {
        val layoutParams = window.attributes ?: return

        if (AngApplication.preferredDisplayModeResolved) {
            layoutParams.preferredDisplayModeId = AngApplication.cachedPreferredDisplayModeId
            layoutParams.preferredRefreshRate = AngApplication.cachedPreferredRefreshRate
            window.attributes = layoutParams
            return
        }

        val display = windowManager.defaultDisplay ?: return
        val currentMode = display.mode
        val preferredMode = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }

        preferredMode?.let {
            AngApplication.resolvePreferredDisplayMode(it.modeId, it.refreshRate)
            layoutParams.preferredDisplayModeId = it.modeId
            layoutParams.preferredRefreshRate = it.refreshRate
        }

        window.attributes = layoutParams
    }
}
