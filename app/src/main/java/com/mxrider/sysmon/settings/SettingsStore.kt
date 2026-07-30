package com.mxrider.sysmon.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mxrider.sysmon.core.AccentOption
import com.mxrider.sysmon.core.LogInterval
import com.mxrider.sysmon.core.SettingsStore
import com.mxrider.sysmon.core.SysMonSettings
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.ThemeMode
import com.mxrider.sysmon.core.UpdateSpeed
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Single Preferences-DataStore file backing all user settings. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ACCENT = stringPreferencesKey("accent")
    val UPDATE_SPEED = stringPreferencesKey("update_speed")
    val START_PAGE = stringPreferencesKey("start_page")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
    val CPU_NOTIFICATION = booleanPreferencesKey("cpu_notification")
    val NOTIFICATION_BOXES = booleanPreferencesKey("notification_boxes")
    val NET_NOTIFICATION = booleanPreferencesKey("net_notification")
    val BATTERY_NOTIFICATION = booleanPreferencesKey("battery_notification")
    val MEMORY_NOTIFICATION = booleanPreferencesKey("memory_notification")
    val LOGGING = booleanPreferencesKey("logging")
    val LOG_INTERVAL = stringPreferencesKey("log_interval")
    val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
    val OVERLAY_X = intPreferencesKey("overlay_x")
    val OVERLAY_Y = intPreferencesKey("overlay_y")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
}

/** Parses a stored enum name, falling back to [default] on null/unknown values. */
private inline fun <reified T : Enum<T>> String?.asEnum(default: T): T =
    if (this == null) default else runCatching { enumValueOf<T>(this) }.getOrDefault(default)

/**
 * DataStore-backed [SettingsStore]. Enum settings are stored by `.name` and
 * parsed defensively so a corrupt or stale value degrades to the default
 * instead of crashing. Read and write I/O failures are swallowed: reads emit
 * defaults, writes are best-effort.
 */
class DefaultSettingsStore(context: Context) : SettingsStore {

    private val dataStore = context.applicationContext.dataStore

    override val settings: Flow<SysMonSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            val defaults = SysMonSettings.DEFAULT
            SysMonSettings(
                themeMode = prefs[Keys.THEME_MODE].asEnum(defaults.themeMode),
                accent = prefs[Keys.ACCENT].asEnum(defaults.accent),
                updateSpeed = prefs[Keys.UPDATE_SPEED].asEnum(defaults.updateSpeed),
                startPage = prefs[Keys.START_PAGE].asEnum(defaults.startPage),
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                showSystemApps = prefs[Keys.SHOW_SYSTEM_APPS] ?: defaults.showSystemApps,
                cpuNotification = prefs[Keys.CPU_NOTIFICATION] ?: defaults.cpuNotification,
                notificationBoxes = prefs[Keys.NOTIFICATION_BOXES] ?: defaults.notificationBoxes,
                netNotification = prefs[Keys.NET_NOTIFICATION] ?: defaults.netNotification,
                batteryNotification = prefs[Keys.BATTERY_NOTIFICATION]
                    ?: defaults.batteryNotification,
                memoryNotification = prefs[Keys.MEMORY_NOTIFICATION]
                    ?: defaults.memoryNotification,
                logging = prefs[Keys.LOGGING] ?: defaults.logging,
                logInterval = prefs[Keys.LOG_INTERVAL].asEnum(defaults.logInterval),
                overlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: defaults.overlayEnabled,
                overlayX = prefs[Keys.OVERLAY_X] ?: defaults.overlayX,
                overlayY = prefs[Keys.OVERLAY_Y] ?: defaults.overlayY,
                onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
            )
        }

    override suspend fun setThemeMode(value: ThemeMode) = setString(Keys.THEME_MODE, value.name)

    override suspend fun setAccent(value: AccentOption) = setString(Keys.ACCENT, value.name)

    override suspend fun setUpdateSpeed(value: UpdateSpeed) = setString(Keys.UPDATE_SPEED, value.name)

    override suspend fun setStartPage(value: TabDestination) = setString(Keys.START_PAGE, value.name)

    override suspend fun setKeepScreenOn(value: Boolean) = setBoolean(Keys.KEEP_SCREEN_ON, value)

    override suspend fun setShowSystemApps(value: Boolean) = setBoolean(Keys.SHOW_SYSTEM_APPS, value)

    override suspend fun setCpuNotification(value: Boolean) = setBoolean(Keys.CPU_NOTIFICATION, value)

    override suspend fun setNotificationBoxes(value: Boolean) =
        setBoolean(Keys.NOTIFICATION_BOXES, value)

    override suspend fun setNetNotification(value: Boolean) = setBoolean(Keys.NET_NOTIFICATION, value)

    override suspend fun setBatteryNotification(value: Boolean) =
        setBoolean(Keys.BATTERY_NOTIFICATION, value)

    override suspend fun setMemoryNotification(value: Boolean) =
        setBoolean(Keys.MEMORY_NOTIFICATION, value)

    override suspend fun setLogging(value: Boolean) = setBoolean(Keys.LOGGING, value)

    override suspend fun setLogInterval(value: LogInterval) =
        setString(Keys.LOG_INTERVAL, value.name)

    override suspend fun setOverlayEnabled(value: Boolean) =
        setBoolean(Keys.OVERLAY_ENABLED, value)

    override suspend fun setOverlayPosition(x: Int, y: Int) {
        try {
            dataStore.edit {
                it[Keys.OVERLAY_X] = x
                it[Keys.OVERLAY_Y] = y
            }
        } catch (_: IOException) {
            // Best-effort persistence; never crash on storage failures.
        }
    }

    override suspend fun setOnboardingDone(value: Boolean) = setBoolean(Keys.ONBOARDING_DONE, value)

    private suspend fun setString(key: Preferences.Key<String>, value: String) {
        try {
            dataStore.edit { it[key] = value }
        } catch (_: IOException) {
            // Best-effort persistence; never crash on storage failures.
        }
    }

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        try {
            dataStore.edit { it[key] = value }
        } catch (_: IOException) {
            // Best-effort persistence; never crash on storage failures.
        }
    }
}
