package com.mxrider.sysmon.core

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf

/**
 * Fixed-capacity ring buffer of chart samples. Not thread-safe: push from the
 * main thread (ViewModel scope). Charts observe [version] to redraw.
 *
 * Missing samples are [Float.NaN]; charts render gaps for them.
 */
class ChartHistory(val capacity: Int = 240) {

    private val buffer = FloatArray(capacity) { Float.NaN }
    private var head = 0 // next write position

    private val _version = mutableLongStateOf(0L)

    /** Bumps on every push; read it inside a Canvas draw scope to subscribe. */
    val version: State<Long> get() = _version

    var latest: Float = Float.NaN
        private set

    fun push(value: Float) {
        buffer[head] = value
        head = (head + 1) % capacity
        latest = value
        _version.longValue++
    }

    /**
     * The most recent [count] samples ordered oldest → newest. Slots that have
     * never been written are NaN.
     */
    fun window(count: Int): FloatArray {
        val n = count.coerceIn(1, capacity)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val idx = ((head - n + i) % capacity + capacity) % capacity
            out[i] = buffer[idx]
        }
        return out
    }

    fun clear() {
        buffer.fill(Float.NaN)
        latest = Float.NaN
        _version.longValue++
    }
}
