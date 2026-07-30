package com.mxrider.sysmon.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map

/**
 * Single Preferences-DataStore file backing all user settings.
 *
 * The corruption handler is load-bearing, not boilerplate. Without it DataStore
 * installs a no-op handler that rethrows forever and never repairs the file, so
 * a truncated or foreign `settings.preferences_pb` — which Android's auto-backup
 * can restore onto a new device, since `backup_rules.xml` includes `datastore` —
 * would leave the app permanently stuck: reads fail, the onboarding flag reads
 * false, and the write that would clear it fails too. Replacing the unreadable
 * file with empty preferences costs the user their settings once and heals
 * everything else automatically.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
 * instead of crashing. Reads retry transient storage failures and fall back to
 * defaults only as a last resort; writes are best-effort and never throw.
 */
class DefaultSettingsStore(context: Context) : SettingsStore {

    private val dataStore = context.applicationContext.dataStore

    /**
     * Ordering here matters as much as the operators.
     *
     * `map` comes FIRST so a value stored under the wrong type — possible after
     * a restore from a different build — is caught here instead of throwing
     * ClassCastException downstream, where nothing would catch it.
     *
     * `retryWhen` handles transient storage failures by RESUBSCRIBING. `catch`
     * cannot do that job: it emits once and then COMPLETES the flow, and a
     * completed settings flow freezes the whole UI on whatever it last saw
     * (theme, start page, and the service-reconciliation effect all read it).
     * The trailing `catch` is therefore a last resort only, after the retries
     * are spent.
     */
    override val settings: Flow<SysMonSettings> = dataStore.data
        .map { prefs -> prefs.toSettings() }
        .retryWhen { cause, attempt ->
            if (cause is IOException && attempt < RETRY_ATTEMPTS) {
                delay(RETRY_DELAY_MS * (attempt + 1))
                true
            } else {
                false
            }
        }
        .catch { emit(SysMonSettings.DEFAULT) }

    private fun Preferences.toSettings(): SysMonSettings {
        val prefs = this
        val defaults = SysMonSettings.DEFAULT
        return SysMonSettings(
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

    private companion object {
        /** Storage hiccups are transient; a few spaced retries beat a dead flow. */
        const val RETRY_ATTEMPTS = 3L
        const val RETRY_DELAY_MS = 200L
    }

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
