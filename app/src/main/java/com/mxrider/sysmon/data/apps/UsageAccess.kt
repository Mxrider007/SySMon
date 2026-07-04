package com.mxrider.sysmon.data.apps

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.mxrider.sysmon.core.UsageAccess

/**
 * Gate for the special "Usage access" grant (PACKAGE_USAGE_STATS). Checked via
 * [AppOpsManager]; MODE_DEFAULT falls back to a plain permission check as some
 * OEMs report it for pre-granted builds.
 */
class DefaultUsageAccess(context: Context) : UsageAccess {

    private val appContext = context.applicationContext

    override fun hasPermission(): Boolean {
        return try {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName,
                )
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT ->
                    appContext.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                        PackageManager.PERMISSION_GRANTED
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
