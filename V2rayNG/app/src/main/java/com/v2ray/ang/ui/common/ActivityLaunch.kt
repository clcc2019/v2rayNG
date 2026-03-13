package com.v2ray.ang.ui.common

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import androidx.fragment.app.FragmentActivity
import com.v2ray.ang.R

fun FragmentActivity.startActivityWithDefaultTransition(intent: Intent) {
    val options = ActivityOptionsCompat.makeCustomAnimation(
        this,
        R.anim.activity_open_enter,
        R.anim.activity_open_exit
    )
    startActivity(intent, options.toBundle())
}

fun FragmentActivity.launchActivityWithDefaultTransition(
    launcher: ActivityResultLauncher<Intent>,
    intent: Intent
) {
    val options = ActivityOptionsCompat.makeCustomAnimation(
        this,
        R.anim.activity_open_enter,
        R.anim.activity_open_exit
    )
    launcher.launch(intent, options)
}
