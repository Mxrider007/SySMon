# SysMon — Task Manager for Android

A Windows 11 Task Manager–style system monitor for Android. Native Kotlin + Jetpack Compose, styled after the Fluent design language (Mica-style surfaces, Selawik typeface, accent-tinted live charts).

**Private by design: the app requests no `INTERNET` permission.** Nothing ever leaves the device.

**Two editions, one codebase (s16):** the main app is **free and complete** (`com.mxrider.sysmon`, gradle flavor `free`); **SysMon Supporter** (`com.mxrider.sysmon.supporter`, flavor `supporter`) is a feature-identical paid donation edition — a tip jar for people who want to fund development, which a no-INTERNET app cannot accept any other way. The only differences are the launcher label, an About-card edition line, and the package name; both install side by side.

Localized (UI + Play listing) in English, German, French, Spanish, Brazilian Portuguese, Italian, Polish, Japanese and Korean; the Android 13+ per-app language picker is wired via `localeConfig`. Translations live in `app/src/main/res/values-<locale>/strings.xml` (one consolidated file per locale) and `store/listings/` + `store/listings-supporter/`; audit ALL listings with `store/tools/check_listings.py` after any copy change.

## Screens (Win11 Task Manager → Android mapping)

| Win11 tab | SysMon screen | Data source |
|---|---|---|
| Processes | Processes — recently active apps, screen time, network & storage per app, End task | `UsageStatsManager`, `NetworkStatsManager`, `StorageStatsManager` |
| Performance | Live 60-second charts: CPU (incl. per-core small multiples), Memory (+composition bar), Network (receive/send), Storage, Battery (power draw in watts + temperature, with a fuel-gauge capacity estimate), Thermal (OS throttling status + per-sensor temperatures where the device permits them), GPU (identity always; total load only where the vendor's counter is readable, which is a minority of devices) | `/proc`, `/sys` cpufreq, `/sys/class/thermal`, `/sys/class/kgsl`, `ActivityManager`, `TrafficStats`, `BatteryManager`, `PowerManager`, `StorageManager`, EGL |
| *(no Win11 counterpart)* | Timeline — the performance log charted over 1 h / 6 h / 24 h / 7 d / everything: CPU, memory, battery level, power draw, temperature, network, with screen-on shading and crosshair scrubbing. Recording gaps stay gaps — nothing is interpolated | the app's own CSV perf-log ring (Settings → Performance logging) |
| App history | Screen time / launches / Wi-Fi & mobile data per app over Today / 7 / 30 days | `UsageStatsManager`, `NetworkStatsManager` |
| Startup apps | Apps registered for boot (`BOOT_COMPLETED` receivers) with impact heuristic | `PackageManager` |
| Users | Device users & work profiles (Android exposes limited info) | `UserManager` |
| Details | Full installed-app inventory: versions, SDKs, sizes, installer, dates | `PackageManager` |
| Services | Declared services of installed apps, exported/enabled/FGS types | `PackageManager` |
| Settings | Theme, accent, update speed (High/Normal/Low/Paused), start page, readout notifications, floating monitor, performance logging | DataStore |

### Honest platform limits (surfaced in-app, never faked)
- Per-process CPU/RAM of *other* apps is not available to third-party apps on modern Android. CPU utilization is measured from `/proc/stat` where readable, otherwise estimated from per-core frequency scaling and labeled as such.
- The optional "CPU usage in notification" readout (foreground service, off by default) shows the same figure under the same rules — estimates stay marked with ~, and sampling pauses while the screen is off.
- The optional home-screen widget pulls in Jetpack Glance, which transitively adds `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` to the merged manifest (Glance composes widgets inside WorkManager). SysMon declares neither and registers no boot receiver; both are disclosed in the listing and privacy policy rather than left for a user to discover in a permission inspector.
- The optional floating mini-monitor (same service, off by default, needs "Display over other apps") shows live CPU/memory/network/battery in a draggable always-on-top card under the same honesty rules; if the special access is revoked, the toggle turns itself off rather than pretend. Android itself hides *all* third-party overlays while security-sensitive screens are in front (the Settings app does this to prevent tapjacking), so the card disappears there and returns on its own — that is the OS protecting you, not a failure.
- The home-screen widget (Glance) samples at refresh time — ↻ tap or the platform's 30-minute cycle — and stamps every card with "Updated HH:mm" instead of passing a stale number off as live. CPU and network get a real one-second two-sample window per refresh, never a fabricated first-call zero.
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
