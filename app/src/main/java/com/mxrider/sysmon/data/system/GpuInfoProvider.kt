package com.mxrider.sysmon.data.system

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.mxrider.sysmon.core.GpuInfo
import com.mxrider.sysmon.core.GpuInfoProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time GPU identification via a 1x1 offscreen EGL/GLES2 context. The
 * result (including a failed all-null result) is cached after the first
 * query; a [Mutex] guards against concurrent double-initialization.
 */
class DefaultGpuInfoProvider : GpuInfoProvider {

    private val mutex = Mutex()

    @Volatile
    private var cached: GpuInfo? = null

    override suspend fun get(): GpuInfo {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: queryGpuInfo().also { cached = it }
        }
    }

    private fun queryGpuInfo(): GpuInfo {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return UNKNOWN
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                // Never initialized: skip termination in finally.
                display = EGL14.EGL_NO_DISPLAY
                return UNKNOWN
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val chosen = EGL14.eglChooseConfig(
                display, configAttribs, 0, configs, 0, configs.size, numConfigs, 0,
            )
            if (!chosen || numConfigs[0] < 1) return UNKNOWN
            val config = configs[0] ?: return UNKNOWN

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return UNKNOWN

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            eglContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) return UNKNOWN

            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return UNKNOWN

            return GpuInfo(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                glVersion = GLES20.glGetString(GLES20.GL_VERSION),
            )
        } catch (_: Exception) {
            return UNKNOWN
        } catch (_: UnsatisfiedLinkError) {
            return UNKNOWN
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Exception) {
                // Teardown is best-effort.
            }
        }
    }

    private companion object {
        val UNKNOWN = GpuInfo(renderer = null, vendor = null, glVersion = null)
    }
}
