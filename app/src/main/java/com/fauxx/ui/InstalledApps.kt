package com.fauxx.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether another app is installed (#341: the dashboard's DuckDuckGo hint). Only packages listed
 * under `<queries>` in the manifest are visible on Android 11+. A package-manager call: never on
 * the main thread.
 */
fun interface InstalledApps {
    fun isInstalled(packageName: String): Boolean

    companion object {
        /** Nothing installed; for tests and fakes. */
        val NONE = InstalledApps { false }

        fun system(context: Context) = InstalledApps { packageName ->
            runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
                true
            }.getOrDefault(false)
        }
    }
}
