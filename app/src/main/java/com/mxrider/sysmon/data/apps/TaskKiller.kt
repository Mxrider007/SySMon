package com.mxrider.sysmon.data.apps

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.mxrider.sysmon.core.TaskKiller

/**
 * Best-effort "End task" via [ActivityManager.killBackgroundProcesses]. On
 * API 34+ the call is a silent no-op for other apps, so [canKill] reports
 * false there and the UI routes users to App info instead.
 */
class DefaultTaskKiller(context: Context) : TaskKiller {

    private val appContext = context.applicationContext

    override fun canKill(): Boolean = Build.VERSION.SDK_INT < 34

    override fun kill(packageName: String): Boolean {
        try {
            appContext.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(packageName)
        } catch (e: Exception) {
            return false
        }
        return canKill()
    }

    override fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun launchIntent(packageName: String): Intent? = try {
        appContext.packageManager.getLaunchIntentForPackage(packageName)
    } catch (e: Exception) {
        null
    }
}
