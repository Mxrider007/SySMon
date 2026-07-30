package com.mxrider.sysmon

import android.app.Application
import android.content.Context
import com.mxrider.sysmon.core.AppRepository
import com.mxrider.sysmon.core.BatteryMonitor
import com.mxrider.sysmon.core.CpuMonitor
import com.mxrider.sysmon.core.DiagnosticsReporter
import com.mxrider.sysmon.core.GpuInfoProvider
import com.mxrider.sysmon.core.MemoryMonitor
import com.mxrider.sysmon.core.NetworkMonitor
import com.mxrider.sysmon.core.PerfLogger
import com.mxrider.sysmon.core.ProcessRepository
import com.mxrider.sysmon.core.ServiceRepository
import com.mxrider.sysmon.core.SettingsStore
import com.mxrider.sysmon.core.StartupRepository
import com.mxrider.sysmon.core.StorageMonitor
import com.mxrider.sysmon.core.TaskKiller
import com.mxrider.sysmon.core.ThermalMonitor
import com.mxrider.sysmon.core.UsageAccess
import com.mxrider.sysmon.core.UsageRepository
import com.mxrider.sysmon.core.UserRepository
import com.mxrider.sysmon.data.apps.DefaultAppRepository
import com.mxrider.sysmon.data.apps.DefaultProcessRepository
import com.mxrider.sysmon.data.apps.DefaultServiceRepository
import com.mxrider.sysmon.data.apps.DefaultStartupRepository
import com.mxrider.sysmon.data.apps.DefaultTaskKiller
import com.mxrider.sysmon.data.apps.DefaultUsageAccess
import com.mxrider.sysmon.data.apps.DefaultUsageRepository
import com.mxrider.sysmon.data.apps.DefaultUserRepository
import com.mxrider.sysmon.data.logging.DefaultPerfLogger
import com.mxrider.sysmon.data.system.DefaultBatteryMonitor
import com.mxrider.sysmon.data.system.DefaultCpuMonitor
import com.mxrider.sysmon.data.system.DefaultDiagnostics
import com.mxrider.sysmon.data.system.DefaultGpuInfoProvider
import com.mxrider.sysmon.data.system.DefaultMemoryMonitor
import com.mxrider.sysmon.data.system.DefaultNetworkMonitor
import com.mxrider.sysmon.data.system.DefaultStorageMonitor
import com.mxrider.sysmon.data.system.DefaultThermalMonitor
import com.mxrider.sysmon.settings.DefaultSettingsStore

class SysMonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/**
 * Manual service locator. Everything is a cheap, context-holding singleton;
 * no DI framework keeps the APK small and the build simple.
 */
object Graph {
    lateinit var diagnostics: DiagnosticsReporter; private set

    lateinit var settings: SettingsStore; private set
    lateinit var perfLogger: PerfLogger; private set

    lateinit var cpu: CpuMonitor; private set
    lateinit var memory: MemoryMonitor; private set
    lateinit var network: NetworkMonitor; private set
    lateinit var battery: BatteryMonitor; private set
    lateinit var storage: StorageMonitor; private set
    lateinit var thermal: ThermalMonitor; private set
    lateinit var gpu: GpuInfoProvider; private set

    lateinit var usageAccess: UsageAccess; private set
    lateinit var apps: AppRepository; private set
    lateinit var usage: UsageRepository; private set
    lateinit var processes: ProcessRepository; private set
    lateinit var services: ServiceRepository; private set
    lateinit var startup: StartupRepository; private set
    lateinit var users: UserRepository; private set
    lateinit var taskKiller: TaskKiller; private set

    fun init(context: Context) {
        val app = context.applicationContext

        // First, so the crash handler is live before anything else spins up
        // and an init-time crash still lands in the diagnostics file.
        usageAccess = DefaultUsageAccess(app)
        diagnostics = DefaultDiagnostics(app, usageAccess).also { it.installCrashHandler() }

        settings = DefaultSettingsStore(app)
        perfLogger = DefaultPerfLogger(app)

        cpu = DefaultCpuMonitor()
        memory = DefaultMemoryMonitor(app)
        network = DefaultNetworkMonitor(app)
        battery = DefaultBatteryMonitor(app)
        storage = DefaultStorageMonitor(app)
        thermal = DefaultThermalMonitor(app)
        gpu = DefaultGpuInfoProvider()

        apps = DefaultAppRepository(app)
        usage = DefaultUsageRepository(app)
        processes = DefaultProcessRepository(app, apps, usage, usageAccess)
        services = DefaultServiceRepository(app)
        startup = DefaultStartupRepository(app)
        users = DefaultUserRepository(app)
        taskKiller = DefaultTaskKiller(app)
    }
}
