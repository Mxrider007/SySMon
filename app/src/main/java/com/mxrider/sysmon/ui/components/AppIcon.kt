package com.mxrider.sysmon.ui.components

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Process-wide icon bitmap cache. Four screens used to carry their own copy of
 * an icon loader, each rasterizing at a fixed 40 *pixels* — on this project's
 * 560 dpi test device a 20 dp slot needs 70 px, so every table icon rendered
 * through a ~2x upscale blur — and each LazyColumn row re-hit PackageManager
 * every time it scrolled back into view.
 *
 * Keys include the pixel size: a runtime display-size change (density is in
 * the activity's configChanges) simply misses the cache and re-rasterizes at
 * the new size; stale entries age out through the LRU.
 */
object AppIconCache {
    /**
     * Bounded by ENTRY COUNT, not bytes. At the 20 dp every call site uses,
     * an icon is 70 px on this project's 560 dpi reference device — 70×70×4 ≈
     * 19 KB — so 256 entries is roughly a 5 MB ceiling. Adding a materially
     * larger `AppIcon` size later would raise that silently; switch to a
     * byte-sized cache if one ever appears.
     */
    private val cache = LruCache<String, ImageBitmap>(256)

    fun get(packageName: String, sizePx: Int): ImageBitmap? =
        cache.get("$packageName@$sizePx")

    fun put(packageName: String, sizePx: Int, bitmap: ImageBitmap) {
        cache.put("$packageName@$sizePx", bitmap)
    }

    /** Called on package add/replace/remove: the app's icon may have changed. */
    fun evict(packageName: String) {
        // LruCache has no prefix removal; snapshot() is tiny at this size.
        for (key in cache.snapshot().keys) {
            if (key.substringBeforeLast('@') == packageName) cache.remove(key)
        }
    }
}

/**
 * App icon at [size], density-correct and cached process-wide. Falls back to a
 * tinted generic glyph while loading or when the package has no icon.
 */
@Composable
fun AppIcon(
    packageName: String,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.toPx() }.roundToInt().coerceAtLeast(1)
    val icon = remember(packageName, sizePx) {
        mutableStateOf(AppIconCache.get(packageName, sizePx))
    }
    if (icon.value == null) {
        LaunchedEffect(packageName, sizePx) {
            icon.value = withContext(Dispatchers.IO) {
                try {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(sizePx, sizePx)
                        .asImageBitmap()
                        .also { AppIconCache.put(packageName, sizePx, it) }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    val bitmap = icon.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Android,
            contentDescription = contentDescription,
            tint = FluentTheme.colors.textTertiary,
            modifier = modifier,
        )
    }
}
