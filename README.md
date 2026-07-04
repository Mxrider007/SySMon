# SysMon — Task Manager for Android

A Windows 11 Task Manager–style system monitor for Android. Native Kotlin + Jetpack Compose, styled after the Fluent design language (Mica-style surfaces, Selawik typeface, accent-tinted live charts).

**Private by design: the app requests no `INTERNET` permission.** Nothing ever leaves the device — a key selling point for a paid utility.

## Screens (Win11 Task Manager → Android mapping)

| Win11 tab | SysMon screen | Data source |
|---|---|---|
| Processes | Processes — recently active apps, screen time, network & storage per app, End task | `UsageStatsManager`, `NetworkStatsManager`, `StorageStatsManager` |
| Performance | Live 60-second charts: CPU (incl. per-core small multiples), Memory (+composition bar), Network (receive/send), Storage, Battery (power draw in watts), GPU info | `/proc`, `/sys` cpufreq, `ActivityManager`, `TrafficStats`, `BatteryManager`, `StorageManager`, EGL |
| App history | Screen time / launches / Wi-Fi & mobile data per app over Today / 7 / 30 days | `UsageStatsManager`, `NetworkStatsManager` |
| Startup apps | Apps registered for boot (`BOOT_COMPLETED` receivers) with impact heuristic | `PackageManager` |
| Users | Device users & work profiles (Android exposes limited info) | `UserManager` |
| Details | Full installed-app inventory: versions, SDKs, sizes, installer, dates | `PackageManager` |
| Services | Declared services of installed apps, exported/enabled/FGS types | `PackageManager` |
| Settings | Theme, accent, update speed (High/Normal/Low/Paused), start page | DataStore |

### Honest platform limits (surfaced in-app, never faked)
- Per-process CPU/RAM of *other* apps is not available to third-party apps on modern Android. CPU utilization is measured from `/proc/stat` where readable, otherwise estimated from per-core frequency scaling and labeled as such.
- "End task" uses `killBackgroundProcesses` (a no-op for other apps since API 34) and always offers the App-info deep link where the user can Force stop.
- Live service state is not exposed; the Services screen shows declared services.

## Build

Requires JDK 17 and the Android SDK (platform 36). `local.properties` points at the SDK.

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release (set up signing first — see PLAY_STORE.md)
```

## Architecture

Single `:app` module, no DI framework, manual service locator (`Graph`).

```
core/        contracts: data models, monitor/repo interfaces, ChartHistory, Ticker, Format
data/system/ CPU, memory, network, battery, storage, GPU collectors
data/apps/   PackageManager / UsageStats / NetworkStats repositories, task killer
settings/    DataStore-backed SettingsStore
ui/theme/    Fluent tokens (colors, Selawik typography, metrics) — contrast-checked accents
ui/components/  NavRail, DataTable, Fluent controls, Win11-style charts
ui/screens/  one package per screen + ViewModel
```

`CONTRACTS_UI.md` documents the binding component signatures and the Win11 visual spec.

## Licensing notes

- Selawik font © Microsoft, SIL Open Font License 1.1 (`SELAWIK_LICENSE.txt`). Attribution shipped in Settings → About.
- The app is *Fluent-inspired* but uses no Microsoft trademarks, icons, or the "Windows" name anywhere in product, listing, or marketing. Do not change this — see PLAY_STORE.md.
