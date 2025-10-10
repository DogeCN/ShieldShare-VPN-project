package com.example.shieldshare.managers.meter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface TrafficMeter {
    fun bytesFlow(): Flow<Long>
}

class TrafficMeterNoop : TrafficMeter {
    override fun bytesFlow(): Flow<Long> = flowOf(0L)
}
