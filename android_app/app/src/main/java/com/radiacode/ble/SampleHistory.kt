package com.radiacode.ble

import kotlin.math.min

internal class SampleHistory(private var capacity: Int) {
    private var timestamps = LongArray(capacity)
    private var values = FloatArray(capacity)

    private var size: Int = 0
    private var head: Int = 0 // index of oldest

    fun clear() {
        size = 0
        head = 0
    }

    fun ensureCapacity(newCapacity: Int) {
        if (newCapacity <= capacity) return
        val newT = LongArray(newCapacity)
        val newV = FloatArray(newCapacity)
        val copyCount = size
        for (i in 0 until copyCount) {
            val idx = (head + i) % capacity
            newT[i] = timestamps[idx]
            newV[i] = values[idx]
        }
        timestamps = newT
        values = newV
        capacity = newCapacity
        head = 0
        size = copyCount
    }

    fun add(timestampMs: Long, value: Float) {
        if (capacity <= 0) return
        if (size < capacity) {
            val idx = (head + size) % capacity
            timestamps[idx] = timestampMs
            values[idx] = value
            size += 1
        } else {
            // overwrite oldest
            timestamps[head] = timestampMs
            values[head] = value
            head = (head + 1) % capacity
        }
    }

    fun lastN(maxPoints: Int): Series {
        if (size == 0 || maxPoints <= 0) return Series(emptyList(), emptyList())
        val n = min(size, maxPoints)
        val startOffset = size - n
        val outT = ArrayList<Long>(n)
        val outV = ArrayList<Float>(n)
        for (i in 0 until n) {
            val idx = (head + startOffset + i) % capacity
            outT.add(timestamps[idx])
            outV.add(values[idx])
        }
        return Series(outT, outV)
    }

    data class Series(
        val timestampsMs: List<Long>,
        val values: List<Float>,
    )
}
