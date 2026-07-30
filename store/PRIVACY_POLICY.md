<!--
HOSTING INSTRUCTIONS (do this BEFORE Play submission — the URL must be stable):

Option A — GitHub Pages (recommended, free, stable):
  1. Create a public repo, e.g. github.com/<you>/sysmon-legal (or use the app repo).
  2. Commit this file as privacy-policy.md (GitHub Pages renders Markdown), or
     rename to index.md inside a /privacy/ folder for a cleaner URL.
  3. Repo Settings -> Pages -> Deploy from branch -> main -> / (root). Save.
  4. Final URL will look like: https://<you>.github.io/sysmon-legal/privacy-policy
     Verify it loads in an incognito window before pasting it anywhere.

Option B — any static host (Cloudflare Pages, Netlify) works the same way.

Then paste the SAME URL in:
  - Play Console -> App content -> Privacy policy
  - SysMon Settings -> About (in-app link)
Do not move or rename the page after submission; Google re-checks the link.
-->

# SysMon Privacy Policy

**Effective date: July 4, 2026**

SysMon (package name `com.mxrider.sysmon`) is a system monitor for Android, sold as a one-time purchase. This policy is short because the app's data practices are short: **SysMon collects nothing, transmits nothing, and shares nothing. All data it displays stays on your device.**

## The app cannot send data anywhere — and you can verify it

SysMon does not request the Android `INTERNET` permission. Without it, the operating system itself prevents the app from making any network connection. This is not just a promise — you can verify it independently by auditing the installed app on your own device with [Exodus Privacy's εxodus app](https://exodus-privacy.eu.org/), or by inspecting the APK's permission list with any package inspector. (Exodus's website only publishes reports for free apps, so a paid app like SysMon is verified on-device rather than via a web report.)

There are no ads, no analytics, no crash-reporting services, no trackers, and no third-party SDKs of any kind. There are no accounts, sign-ins, or user profiles.

If SysMon ever crashes, a technical crash log is written to the app's private storage **on your device only**. You can copy it — together with your device model, Android version and app version — from Settings ("Copy diagnostics") and choose to email it to support yourself. Nothing is ever sent automatically, and the log contains no personal information.

If you enable the optional **performance logging** feature (off by default), SysMon records system metrics — CPU utilization, memory use, battery level/power/temperature, and network transfer rates — as a CSV file **in the app's private storage on your device only** (rolling, capped at 50 MB — the oldest samples are dropped first). It contains device metrics, not personal information, and it is never transmitted anywhere: it leaves your device only if you export it yourself through the system file picker, and you can clear it at any time in Settings. Uninstalling the app deletes it.

## What SysMon reads on your device

To do its job as a monitor, SysMon reads the following information **on-device only**, displays it to you, and does not store it beyond what is needed to draw its live charts and tables:

- **App usage statistics** (screen time, app launch counts, per-app network and storage usage) — only if you grant the optional *Usage access* permission. The app explains this and asks for your consent before sending you to the system settings screen. You can decline; the rest of the app still works.
- **Installed-app metadata** (app names, versions, sizes, install dates, declared services and boot receivers) from the Android package manager.
- **Hardware and system counters**: CPU frequency, memory, network traffic totals, storage, battery, temperature sensors and thermal status, and GPU information exposed by Android.

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
