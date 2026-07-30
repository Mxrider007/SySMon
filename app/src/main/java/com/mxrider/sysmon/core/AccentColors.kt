package com.mxrider.sysmon.core

import android.content.Context
import android.os.Build

/**
 * The light-mode control color for accent-painted surfaces OUTSIDE the compose
 * theme — the notification usage tiles and the overlay card's pill, which both
 * always use the light control color regardless of theme. [AccentOption.DYNAMIC]
 * resolves from the system's wallpaper palette here; everything else returns
 * its constant. Fallback (below API 31 or if resolution throws) is the
 * constant baked into the enum entry, never a crash.
 */
fun AccentOption.resolvedControlLightArgb(context: Context): Long {
    if (this != AccentOption.DYNAMIC || Build.VERSION.SDK_INT < 31) return controlLightArgb
    return try {
        // system_accent1_600: the same tone the in-app light theme uses, so
        // notification tiles match the app's own dynamic accent.
        0xFFFFFFFF and context.getColor(android.R.color.system_accent1_600).toLong()
    } catch (_: Exception) {
        controlLightArgb
    }
}
