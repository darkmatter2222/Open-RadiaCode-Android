package com.radiacode.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RadiacodeProtocol {
    // From cdump/radiacode
    val SERVICE_UUID = java.util.UUID.fromString("e63215e5-7003-49d8-96b0-b024798fb901")
    val WRITE_UUID = java.util.UUID.fromString("e63215e6-7003-49d8-96b0-b024798fb901")
    val NOTIFY_UUID = java.util.UUID.fromString("e63215e7-7003-49d8-96b0-b024798fb901")

    const val COMMAND_SET_EXCHANGE = 0x0007
    // cdump/radiacode: COMMAND.SET_TIME = 0x0A04
    const val COMMAND_SET_TIME = 0x0A04
    const val COMMAND_WR_VIRT_SFR = 0x0825
    const val COMMAND_RD_VIRT_STRING = 0x0826

    const val VS_DATA_BUF = 0x0100
    const val VS_SPECTRUM = 0x0200        // Current spectrum data (1024 channels)
    const val VS_ENERGY_CALIB = 0x0202    // Energy calibration coefficients
    const val VS_SPEC_ACCUM = 0x0205      // Accumulated spectrum
    const val VS_SPEC_DIFF = 0x0206       // Differential spectrum (recent counts)
    const val VSFR_DEVICE_TIME = 0x0504

    fun buildRequest(command: Int, seq: Int, args: ByteArray): ByteArray {
        val reqSeqNo = (0x80 + (seq and 0x1F)) and 0xFF

        val header = ByteArray(4)
        // <HBB little-endian
        header[0] = (command and 0xFF).toByte()
        header[1] = ((command ushr 8) and 0xFF).toByte()
        header[2] = 0
        header[3] = reqSeqNo.toByte()

        val inner = ByteArray(header.size + args.size)
        System.arraycopy(header, 0, inner, 0, header.size)
        System.arraycopy(args, 0, inner, header.size, args.size)

        // <I len> + inner
        val out = ByteArray(4 + inner.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(inner.size)
        bb.put(inner)
        return out
    }

    fun packU32LE(v: Long): ByteArray {
        val out = ByteArray(4)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((v and 0xFFFF_FFFFL).toInt())
        return out
    }

    fun packI32LE(v: Int): ByteArray {
        val out = ByteArray(4)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v)
        return out
    }

    fun packSetTimePayloadLocal(
        day: Int,
        month: Int,
        year: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): ByteArray {
        // Matches python: struct.pack('<BBBBBBBB', dt.day, dt.month, dt.year-2000, 0, dt.second, dt.minute, dt.hour, 0)
        return byteArrayOf(
            (day and 0xFF).toByte(),
            (month and 0xFF).toByte(),
            ((year - 2000) and 0xFF).toByte(),
            0,
            (second and 0xFF).toByte(),
            (minute and 0xFF).toByte(),
            (hour and 0xFF).toByte(),
            0,
        )
    }
}
