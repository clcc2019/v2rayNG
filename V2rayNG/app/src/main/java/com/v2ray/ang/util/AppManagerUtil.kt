package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import com.v2ray.ang.dto.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManagerUtil {
    /**
     * Load the list of network applications.
     *
     * @param context The context to use.
     * @return A list of AppInfo objects representing the network applications.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledApplications(0)
            val apps = ArrayList<AppInfo>()

            for (pkg in packages) {
                val appName = pkg.loadLabel(packageManager).toString()
                val appIcon = pkg.loadIcon(packageManager) ?: continue
                val isSystemApp = pkg.flags and ApplicationInfo.FLAG_SYSTEM > 0

                val appInfo = AppInfo(appName, pkg.packageName, appIcon, isSystemApp, 0)
                apps.add(appInfo)
            }

            return@withContext apps
        }

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

}
