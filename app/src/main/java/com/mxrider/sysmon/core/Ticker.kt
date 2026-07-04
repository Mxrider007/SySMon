package com.mxrider.sysmon.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Emits an incrementing tick at the interval selected by [speed].
 * [UpdateSpeed.PAUSED] suspends emission until the speed changes again.
 * The first tick of an interval fires immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun ticker(speed: Flow<UpdateSpeed>): Flow<Long> =
    speed.distinctUntilChanged().flatMapLatest { s ->
        if (s == UpdateSpeed.PAUSED) {
            emptyFlow()
        } else {
            flow {
                var tick = 0L
                while (true) {
                    emit(tick++)
                    delay(s.intervalMs)
                }
            }
        }
    }
