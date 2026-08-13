
# SysMon Privacy Policy

**Effective date: August 13, 2026** *(this update adds the Supporter edition package; the data practices are unchanged since July 4, 2026)*

SysMon is a system monitor for Android, available in two editions with identical features and identical data practices: the free app (package name `com.mxrider.sysmon`) and the paid **SysMon Supporter** edition (package name `com.mxrider.sysmon.supporter`), which exists so users who want to can fund development. This policy covers both packages; everything below applies equally to each. It is short because the app's data practices are short: **SysMon collects nothing, transmits nothing, and shares nothing. All data it displays stays on your device.**

## The app cannot send data anywhere — and you can verify it

SysMon does not request the Android `INTERNET` permission. Without it, the operating system itself prevents the app from making any network connection. This is not just a promise — you can verify it independently by auditing the installed app on your own device with [Exodus Privacy's εxodus app](https://exodus-privacy.eu.org/), or by inspecting the APK's permission list with any package inspector. (Exodus's website only publishes reports for free apps it has crawled, so the on-device audit is the check that works for both editions from day one — the paid Supporter edition in particular will never have a web report.)

There are no ads, no analytics, no crash-reporting services, no trackers, and no third-party SDKs of any kind. There are no accounts, sign-ins, or user profiles.

**Two permissions you will see that SysMon never asked for.** If you inspect that list, it shows `WAKE_LOCK` ("prevent phone from sleeping") and `RECEIVE_BOOT_COMPLETED` ("run at startup"). Neither is declared by SysMon. Both are added automatically by the Android library that draws the optional home-screen widget, which composes it inside the system's background-work scheduler. SysMon registers **no** start-at-boot receiver of its own — that is why the app's own settings tell you a readout "returns the next time you open SysMon" after a reboot instead of starting by itself. We would rather explain these than have you find them unexplained.

If SysMon ever crashes, a technical crash log is written to the app's private storage **on your device only**. You can copy it — together with your device model, Android version and app version — from Settings ("Copy diagnostics") and choose to email it to support yourself. Nothing is ever sent automatically, and the log contains no personal information.

If you enable the optional **performance logging** feature (off by default), SysMon records system metrics — CPU utilization, memory use, battery level/power/temperature, and network transfer rates — as a CSV file **in the app's private storage on your device only** (rolling, capped at 50 MB — the oldest samples are dropped first). It contains device metrics, not personal information, and it is never transmitted anywhere: it leaves your device only if you export it yourself through the system file picker, and you can clear it at any time in Settings. Uninstalling the app deletes it.

## What SysMon reads on your device

To do its job as a monitor, SysMon reads the following information **on-device only**, displays it to you, and does not store it beyond what is needed to draw its live charts and tables:

- **App usage statistics** (screen time, app launch counts, per-app network and storage usage) — only if you grant the optional *Usage access* permission. The app explains this and asks for your consent before sending you to the system settings screen. You can decline; the rest of the app still works.
- **Installed-app metadata** (app names, versions, sizes, install dates, declared services and boot receivers) from the Android package manager.
- **Hardware and system counters**: CPU frequency, memory, network traffic totals, storage, battery, temperature sensors and thermal status, and GPU information exposed by Android.

If you use the **Export CSV** button on the Details screen, SysMon writes the app-inventory table you are looking at to a temporary file and hands it to the app you choose in the system share sheet. Nothing is sent anywhere on its own — you pick the destination, and the file contains only the information already shown on that screen.

Your only stored data — settings (theme, accent color, update speed, start page, notification and logging preferences) and the optional performance log described above — is kept locally on your device.

## Honesty about what the app shows

Android limits what any third-party app can see: system-wide CPU% is estimated from core frequencies where exact figures are unavailable, other apps' exact CPU and RAM usage is not accessible, and on Android 14+ the "End task" action routes you to the system App info screen because Android no longer lets apps stop other apps directly. SysMon labels estimates as estimates and never fabricates data.

## Data deletion

Because SysMon keeps everything on-device, deleting your data is entirely in your hands:

- **Uninstall the app** — this removes all of SysMon's local settings and the optional performance log.
- **Clear the performance log** anytime in SysMon Settings → Logging → Clear log.
- Optionally, **revoke Usage access** in Android Settings → Apps → Special app access → Usage access, at any time, even while the app remains installed.

There is nothing to delete on any server, because there are no servers.

## Children

SysMon collects no personal data from anyone, including children. It is a technical utility rated for a general audience.

## Changes to this policy

If a future version of SysMon ever changes these practices (for example, any feature that transmits data), this policy will be updated at this same URL before that version ships, with a new effective date. The current promise — no data collection, no network access — will only ever change with a clearly announced update.

## Contact

Questions about this policy or the app: **mxrider008@gmail.com**

---

*SysMon is an independent app. Not affiliated with Microsoft.*
