package com.mxrider.sysmon.data.system

import com.mxrider.sysmon.core.GpuLoadMonitor
import java.io.File

/**
 * Total GPU busy fraction from vendor sysfs nodes. There is no public API for
 * this — Android exposes GPU *identity* (EGL strings) but not load — so this
 * reads the nodes Adreno and some other kernels publish, and returns null
 * everywhere they are absent or SELinux-blocked. The project's own test device
 * blocks all of them, which is exactly why every consumer must treat null as
 * a first-class answer rather than an error.
 */
class DefaultGpuLoadMonitor : GpuLoadMonitor {

    private sealed interface Node {
        fun read(): Float?
    }

    /** "42" or "42 %" → 0.42. */
    private class PercentNode(private val file: File) : Node {
        override fun read(): Float? {
            val text = try {
                file.readText()
            } catch (_: Exception) {
                return null
            }
            val digits = text.trim().takeWhile { it.isDigit() }
            val percent = digits.toIntOrNull() ?: return null
            return (percent / 100f).coerceIn(0f, 1f)
        }
    }

    /**
     * "busyCycles totalCycles" since the previous read (Adreno `gpubusy`).
     * The since-last-read window semantics mean this node must have exactly
     * one reader; only the Performance tick samples it.
     */
    private class RatioNode(private val file: File) : Node {
        override fun read(): Float? {
            val text = try {
                file.readText()
            } catch (_: Exception) {
                return null
            }
            val parts = text.trim().split(Regex("\\s+"))
            if (parts.size < 2) return null
            val busy = parts[0].toLongOrNull() ?: return null
            val total = parts[1].toLongOrNull() ?: return null
            if (total <= 0L || busy < 0L) return null
            return (busy.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    /** Set once by the first sample; a device's nodes never appear later. */
    private var searched = false
    private var node: Node? = null

    override suspend fun sample(): Float? {
        if (!searched) {
            node = discover()
            searched = true
        }
        return node?.read()
    }

    private fun discover(): Node? {
        for (path in PERCENT_NODES) {
            val file = File(path)
            val probe = PercentNode(file)
            if (probe.read() != null) return probe
        }
        for (path in RATIO_NODES) {
            val file = File(path)
            val probe = RatioNode(file)
            if (probe.read() != null) return probe
        }
        return null
    }

    private companion object {
        /** Ordered by how directly they answer the question. */
        val PERCENT_NODES = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy",
        )
        val RATIO_NODES = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
        )
    }
}
