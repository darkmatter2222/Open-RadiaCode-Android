package com.radiacode.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ByteReader(private val data: ByteArray) {
    var pos: Int = 0
        private set

    val remaining: Int
        get() = data.size - pos

    fun readBytes(count: Int): ByteArray {
        require(count >= 0 && count <= remaining) { "readBytes($count) out of range (remaining=$remaining)" }
        val out = data.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun readU8(): Int {
        require(remaining >= 1)
        val v = data[pos].toInt() and 0xFF
        pos += 1
        return v
    }

    fun readU16LE(): Int {
        require(remaining >= 2)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun readI32LE(): Int {
        require(remaining >= 4)
        val bb = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN)
        val v = bb.int
        pos += 4
        return v
    }

    fun readU32LE(): Long {
        return readI32LE().toLong() and 0xFFFF_FFFFL
    }

    fun readF32LE(): Float {
        require(remaining >= 4)
        val bb = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN)
        val v = bb.float
        pos += 4
        return v
    }
}
