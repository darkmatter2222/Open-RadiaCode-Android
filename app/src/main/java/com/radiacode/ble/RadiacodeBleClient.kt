package com.radiacode.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class RadiacodeBleClient(
    private val context: Context,
    private val status: (String) -> Unit,
) {
    private companion object {
        private const val TAG = "RadiaCode"
        // With the executor deadlock fixed, the device replies quickly; keep timeouts modest but not brittle.
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 12_000L
        private const val SET_EXCHANGE_TIMEOUT_MS = 25_000L
        private const val TIMEOUT_GRACE_MS = 2_000L
        private const val MAX_HEX_PREVIEW = 64

        private fun timeoutMsForCommand(command: Int): Long {
            val base = when (command) {
                RadiacodeProtocol.COMMAND_SET_EXCHANGE -> SET_EXCHANGE_TIMEOUT_MS
                else -> DEFAULT_REQUEST_TIMEOUT_MS
            }
            // Android scheduling / binder thread jitter can easily be ~100ms at the boundary.
            return base + TIMEOUT_GRACE_MS
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val timeoutScheduler = Executors.newSingleThreadScheduledExecutor()

    private val readyFuture = CompletableFuture<Unit>()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // Response reassembly (length-prefixed on first notification)
    private var expectedResponseBytes: Int? = null
    private var responseBuffer = ByteArrayOutputStream()

    // Single in-flight request
    private var pending: PendingRequest? = null

    // Single in-flight RSSI read
    private var pendingRssi: CompletableFuture<Int>? = null
    private var pendingRssiTimeout: ScheduledFuture<*>? = null

    // Sequenced writes (Android GATT requires waiting for onCharacteristicWrite)
    private var pendingWriteChunks: List<ByteArray>? = null
    private var pendingWriteIndex: Int = 0

    private var seq: Int = 0

    private data class PendingRequest(
        val header4: ByteArray,
        val future: CompletableFuture<ByteArray>,
        val command: Int,
        val reqSeqNo: Int,
        var timeoutTask: ScheduledFuture<*>? = null,
    )

    @SuppressLint("MissingPermission")
    fun connect(device: android.bluetooth.BluetoothDevice) {
        status("Connecting…")
        Log.d(TAG, "connect: ${device.address} name=${device.name}")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun ready(): CompletableFuture<Unit> = readyFuture

    fun close() {
        executor.execute {
            pending?.future?.completeExceptionally(IllegalStateException("Disconnected"))
            pending = null

            pendingRssi?.completeExceptionally(IllegalStateException("Disconnected"))
            pendingRssi = null
            try {
                pendingRssiTimeout?.cancel(true)
            } catch (_: Throwable) {
            }
            pendingRssiTimeout = null

            if (!readyFuture.isDone) {
                readyFuture.completeExceptionally(IllegalStateException("Disconnected"))
            }
        }
        try {
            timeoutScheduler.shutdownNow()
        } catch (_: Throwable) {
        }
        gatt?.close()
        gatt = null
        writeChar = null
        notifyChar = null
    }

    @SuppressLint("MissingPermission")
    fun readRemoteRssi(): CompletableFuture<Int> {
        val g = gatt ?: return CompletableFuture.failedFuture(IllegalStateException("Not connected"))

        val future = CompletableFuture<Int>()
        synchronized(this) {
            if (pendingRssi != null) {
                return CompletableFuture.failedFuture(IllegalStateException("Only one in-flight RSSI read supported"))
            }
            pendingRssi = future
            pendingRssiTimeout = timeoutScheduler.schedule(
                {
                    val shouldFail = synchronized(this) { pendingRssi === future }
                    if (shouldFail) {
                        synchronized(this) {
                            pendingRssi = null
                            pendingRssiTimeout = null
                        }
                        future.completeExceptionally(TimeoutException("Timeout waiting for RSSI"))
                    }
                },
                5_000L,
                TimeUnit.MILLISECONDS
            )
        }

        val ok = g.readRemoteRssi()
        if (!ok) {
            synchronized(this) {
                pendingRssi = null
                try {
                    pendingRssiTimeout?.cancel(true)
                } catch (_: Throwable) {
                }
                pendingRssiTimeout = null
            }
            return CompletableFuture.failedFuture(IllegalStateException("readRemoteRssi failed"))
        }

        return future
    }

    fun initializeSession(): CompletableFuture<Unit> {
        // Matches python init: SET_EXCHANGE, SET_TIME, device_time(0)
        Log.d(TAG, "initializeSession: starting")

        // IMPORTANT: Do not block the single-thread executor with .get().
        // execute() schedules the actual GATT write on that same executor.
        return CompletableFuture
            .runAsync(
                {
                    // Some devices respond slowly immediately after enabling notifications.
                    Thread.sleep(500)
                },
                executor
            )
            .thenCompose {
                Log.d(
                    TAG,
                    "initializeSession: SET_EXCHANGE (timeout=${timeoutMsForCommand(RadiacodeProtocol.COMMAND_SET_EXCHANGE)}ms)"
                )
                execute(
                    RadiacodeProtocol.COMMAND_SET_EXCHANGE,
                    byteArrayOf(0x01, 0xFF.toByte(), 0x12, 0xFF.toByte())
                )
            }
            .thenCompose {
                val now = LocalDateTime.now()
                Log.d(TAG, "initializeSession: SET_TIME local=$now")
                val timePayload = RadiacodeProtocol.packSetTimePayloadLocal(
                    day = now.dayOfMonth,
                    month = now.monthValue,
                    year = now.year,
                    hour = now.hour,
                    minute = now.minute,
                    second = now.second,
                )
                execute(RadiacodeProtocol.COMMAND_SET_TIME, timePayload)
            }
            .thenCompose {
                // device_time(0) => WR_VIRT_SFR(VSFR_DEVICE_TIME, <I 0>)
                val wrPayload = ByteArrayOutputStream().apply {
                    write(RadiacodeProtocol.packU32LE(RadiacodeProtocol.VSFR_DEVICE_TIME.toLong()))
                    write(RadiacodeProtocol.packU32LE(0))
                }.toByteArray()
                execute(RadiacodeProtocol.COMMAND_WR_VIRT_SFR, wrPayload)
            }
            .thenApply { resp ->
                // response payload begins with <I retcode>
                if (resp.size < 4) throw IllegalStateException("WR_VIRT_SFR response too short")
                val ret = ByteBuffer.wrap(resp, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (ret != 1) throw IllegalStateException("WR_VIRT_SFR failed ret=$ret")
                Log.d(TAG, "initializeSession: done")
                Unit
            }
            .whenComplete { _, t ->
                if (t != null) {
                    Log.e(TAG, "initializeSession failed", t)
                }
            }
    }

    fun readDataBuf(): CompletableFuture<ByteArray> {
        Log.d(TAG, "readDataBuf: RD_VIRT_STRING VS_DATA_BUF")
        val payload = RadiacodeProtocol.packU32LE(RadiacodeProtocol.VS_DATA_BUF.toLong())
        return execute(RadiacodeProtocol.COMMAND_RD_VIRT_STRING, payload)
            .thenApply { resp ->
                // resp: <II retcode, flen> then flen bytes
                if (resp.size < 8) throw IllegalStateException("RD_VIRT_STRING response too short")
                val bb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                val retcode = bb.int
                val flen = bb.int
                if (retcode != 1) throw IllegalStateException("RD_VIRT_STRING failed ret=$retcode")
                if (flen < 0 || resp.size < 8 + flen) throw IllegalStateException("RD_VIRT_STRING bad flen=$flen")

                val out = ByteArray(flen)
                bb.get(out)

                // python has a workaround trimming trailing 0x00; keep it harmless here too
                val trimmed = if (out.isNotEmpty() && out.last() == 0.toByte()) {
                    out.copyOfRange(0, out.size - 1)
                } else out

                Log.d(TAG, "readDataBuf: got flen=$flen trimmed=${trimmed.size}")
                trimmed
            }
            .whenComplete { _, t ->
                if (t != null) {
                    Log.e(TAG, "readDataBuf failed", t)
                }
            }
    }

    /**
     * Reads the current gamma spectrum (typically 1024 channels).
     * Returns SpectrumData containing duration, calibration coefficients, and counts.
     */
    fun readSpectrum(): CompletableFuture<SpectrumData> {
        Log.d(TAG, "readSpectrum: RD_VIRT_STRING VS_SPECTRUM")
        val payload = RadiacodeProtocol.packU32LE(RadiacodeProtocol.VS_SPECTRUM.toLong())
        return execute(RadiacodeProtocol.COMMAND_RD_VIRT_STRING, payload)
            .thenApply { resp ->
                if (resp.size < 8) throw IllegalStateException("RD_VIRT_STRING response too short")
                val bb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                val retcode = bb.int
                val flen = bb.int
                if (retcode != 1) throw IllegalStateException("RD_VIRT_STRING (SPECTRUM) failed ret=$retcode")
                if (flen < 16) throw IllegalStateException("Spectrum data too short flen=$flen")
                
                // Format: <Ifff> duration_seconds, a0, a1, a2 (calibration), then counts
                val durationSeconds = bb.int
                val a0 = bb.float  // keV offset
                val a1 = bb.float  // keV/channel (linear)
                val a2 = bb.float  // keV/channel² (quadratic)
                
                // Parse counts - remaining bytes are the spectrum data
                // RadiaCode uses various formats (simple U32 array or RLE compressed)
                val countsBytes = flen - 16  // 4 (duration) + 12 (calibration)
                val counts = parseSpectrumCounts(bb, countsBytes)
                
                Log.d(TAG, "readSpectrum: duration=${durationSeconds}s a0=$a0 a1=$a1 a2=$a2 channels=${counts.size}")
                SpectrumData(
                    durationSeconds = durationSeconds,
                    a0 = a0,
                    a1 = a1,
                    a2 = a2,
                    counts = counts,
                    timestampMs = System.currentTimeMillis()
                )
            }
            .whenComplete { _, t ->
                if (t != null) {
                    Log.e(TAG, "readSpectrum failed", t)
                }
            }
    }
    
    /**
     * Reads the energy calibration coefficients from the device.
     * Returns EnergyCalibration with a0, a1, a2 coefficients.
     * Energy(keV) = a0 + a1 * channel + a2 * channel²
     */
    fun readEnergyCalibration(): CompletableFuture<EnergyCalibration> {
        Log.d(TAG, "readEnergyCalibration: RD_VIRT_STRING VS_ENERGY_CALIB")
        val payload = RadiacodeProtocol.packU32LE(RadiacodeProtocol.VS_ENERGY_CALIB.toLong())
        return execute(RadiacodeProtocol.COMMAND_RD_VIRT_STRING, payload)
            .thenApply { resp ->
                if (resp.size < 8) throw IllegalStateException("RD_VIRT_STRING response too short")
                val bb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                val retcode = bb.int
                val flen = bb.int
                if (retcode != 1) throw IllegalStateException("RD_VIRT_STRING (ENERGY_CALIB) failed ret=$retcode")
                if (flen < 12) throw IllegalStateException("Calibration data too short flen=$flen")
                
                val a0 = bb.float
                val a1 = bb.float
                val a2 = bb.float
                
                Log.d(TAG, "readEnergyCalibration: a0=$a0 a1=$a1 a2=$a2")
                EnergyCalibration(a0 = a0, a1 = a1, a2 = a2)
            }
            .whenComplete { _, t ->
                if (t != null) {
                    Log.e(TAG, "readEnergyCalibration failed", t)
                }
            }
    }
    
    /**
     * Parse spectrum counts from the response buffer.
     * RadiaCode can use simple U32 array or run-length encoded format.
     * We attempt simple parsing first; 1024 channels * 4 bytes = 4096 bytes expected.
     */
    private fun parseSpectrumCounts(bb: ByteBuffer, bytesRemaining: Int): IntArray {
        // Simple format: array of U32 counts
        val numChannels = bytesRemaining / 4
        val counts = IntArray(numChannels)
        for (i in 0 until numChannels) {
            counts[i] = bb.int
        }
        return counts
    }

    private fun execute(command: Int, args: ByteArray): CompletableFuture<ByteArray> {
        val g = gatt ?: return CompletableFuture.failedFuture(IllegalStateException("Not connected"))
        val w = writeChar ?: return CompletableFuture.failedFuture(IllegalStateException("Write char missing"))

        val reqSeq = seq
        val req = RadiacodeProtocol.buildRequest(command, reqSeq, args)
        seq = (seq + 1) and 0x1F

        val timeoutMs = timeoutMsForCommand(command)
        Log.d(
            TAG,
            "execute: cmd=0x${command.toString(16)} seq=$reqSeq timeout=${timeoutMs}ms reqLen=${req.size} req=${req.hexPreview()}"
        )

        val header4 = req.copyOfRange(4, 8) // first 4 bytes after length prefix

        val future = CompletableFuture<ByteArray>()

        synchronized(this) {
            if (pending != null) {
                return CompletableFuture.failedFuture(IllegalStateException("Only one in-flight request supported"))
            }
            val p = PendingRequest(header4 = header4, future = future, command = command, reqSeqNo = reqSeq)
            p.timeoutTask = timeoutScheduler.schedule(
                {
                    val shouldFail = synchronized(this) { pending?.future === future }
                    if (shouldFail) {
                        failPending(TimeoutException("Timeout waiting for response cmd=0x${command.toString(16)} seq=$reqSeq"))
                    }
                },
                timeoutMs,
                TimeUnit.MILLISECONDS
            )
            pending = p
            expectedResponseBytes = null
            responseBuffer.reset()

            // Chunk into 18-byte writes (same as python), but sequence them via callbacks.
            pendingWriteChunks = req.asListChunks(18)
            pendingWriteIndex = 0
        }

        executor.execute {
            try {
                startNextChunkWrite(g, w)
            } catch (t: Throwable) {
                failPending(t)
            }
        }

        return future
    }

    private fun ByteArray.asListChunks(chunkSize: Int): List<ByteArray> {
        if (isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((size + chunkSize - 1) / chunkSize)
        var offset = 0
        while (offset < size) {
            val end = minOf(size, offset + chunkSize)
            out.add(copyOfRange(offset, end))
            offset = end
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun startNextChunkWrite(g: BluetoothGatt, w: BluetoothGattCharacteristic) {
        val chunk: ByteArray = synchronized(this) {
            val chunks = pendingWriteChunks ?: return
            if (pendingWriteIndex >= chunks.size) {
                pendingWriteChunks = null
                return
            }
            chunks[pendingWriteIndex]
        }

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(w, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                w.value = chunk
                g.writeCharacteristic(w)
            }
        }

        if (!ok) {
            failPending(IllegalStateException("writeCharacteristic failed"))
        }
    }

    private fun failPending(t: Throwable) {
        val p = synchronized(this) {
            val cur = pending
            pending = null
            expectedResponseBytes = null
            responseBuffer.reset()
            cur
        }
        try {
            p?.timeoutTask?.cancel(true)
        } catch (_: Throwable) {
        }
        p?.future?.completeExceptionally(t)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$statusCode newState=$newState")
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("GATT error: $statusCode")
                close()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected; discovering services…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status("Disconnected")
                close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            Log.d(TAG, "onServicesDiscovered status=$statusCode")
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Service discovery failed: $statusCode")
                return
            }

            val service = gatt.getService(RadiacodeProtocol.SERVICE_UUID)
            if (service == null) {
                status("RadiaCode service not found")
                return
            }

            writeChar = service.getCharacteristic(RadiacodeProtocol.WRITE_UUID)
            notifyChar = service.getCharacteristic(RadiacodeProtocol.NOTIFY_UUID)

            if (writeChar == null || notifyChar == null) {
                status("RadiaCode characteristics not found")
                return
            }

            status("Enabling notifications…")
            gatt.setCharacteristicNotification(notifyChar, true)

            val cccd = notifyChar?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd == null) {
                status("CCCD not found")
                return
            }
            cccd.value = byteArrayOf(0x01, 0x00) // enable notify
            Log.d(TAG, "writing CCCD enable notify")
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            Log.d(TAG, "onDescriptorWrite status=$statusCode uuid=${descriptor.uuid}")
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("CCCD write failed: $statusCode")
                return
            }
            status("Ready")

            if (!readyFuture.isDone) {
                readyFuture.complete(Unit)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (characteristic.uuid != RadiacodeProtocol.WRITE_UUID) return
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onCharacteristicWrite failed status=$statusCode")
                failPending(IllegalStateException("Characteristic write failed: $statusCode"))
                return
            }

            // Advance to next chunk.
            synchronized(this@RadiacodeBleClient) {
                pendingWriteIndex += 1
            }
            val w = writeChar ?: return
            startNextChunkWrite(gatt, w)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            onNotify(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(characteristic, value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, statusCode: Int) {
            val f = synchronized(this@RadiacodeBleClient) {
                val cur = pendingRssi
                pendingRssi = null
                try {
                    pendingRssiTimeout?.cancel(true)
                } catch (_: Throwable) {
                }
                pendingRssiTimeout = null
                cur
            } ?: return

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                f.completeExceptionally(IllegalStateException("RSSI read failed: $statusCode"))
            } else {
                f.complete(rssi)
            }
        }
    }

    private fun onNotify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != RadiacodeProtocol.NOTIFY_UUID) return

        // Log only the first chunk to avoid logcat spam; the full response is logged on completion.
        val shouldLogFirstChunk = synchronized(this@RadiacodeBleClient) { expectedResponseBytes == null }
        if (shouldLogFirstChunk) {
            Log.d(TAG, "notify: ${value.size} bytes ${value.hexPreview()}")
        }

        val completed: ByteArray? = synchronized(this@RadiacodeBleClient) {
            // We only support request/response. If a response arrives after we timed out (or when disconnected),
            // ignore it and keep the reassembly state clean for the next request.
            if (pending == null) {
                expectedResponseBytes = null
                responseBuffer.reset()
                return@synchronized null
            }
            if (expectedResponseBytes == null) {
                if (value.size < 4) return@synchronized null
                val len = ByteBuffer.wrap(value, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (len < 0) {
                    failPending(IllegalStateException("Negative response length: $len"))
                    return@synchronized null
                }
                expectedResponseBytes = len
                responseBuffer.write(value, 4, value.size - 4)
            } else {
                responseBuffer.write(value)
            }

            val expected = expectedResponseBytes ?: return@synchronized null
            if (responseBuffer.size() >= expected) {
                responseBuffer.toByteArray().copyOfRange(0, expected)
            } else {
                null
            }
        }

        if (completed != null) {
            handleCompletedResponse(completed)
        }
    }

    private fun handleCompletedResponse(message: ByteArray) {
        val p = synchronized(this) {
            val cur = pending
            pending = null
            expectedResponseBytes = null
            responseBuffer.reset()
            cur
        } ?: return

        try {
            p.timeoutTask?.cancel(true)
        } catch (_: Throwable) {
        }

        try {
            if (message.size < 4) throw IllegalStateException("Response too short")
            val header = message.copyOfRange(0, 4)
            if (!header.contentEquals(p.header4)) {
                throw IllegalStateException(
                    "Response header mismatch cmd=0x${p.command.toString(16)} seq=${p.reqSeqNo} " +
                        "expected=${p.header4.hexPreview(4)} got=${header.hexPreview(4)}"
                )
            }

            val payload = message.copyOfRange(4, message.size)
            Log.d(TAG, "response ok cmd=0x${p.command.toString(16)} seq=${p.reqSeqNo} payloadLen=${payload.size} payload=${payload.hexPreview()}")
            p.future.complete(payload)
        } catch (t: Throwable) {
            Log.e(TAG, "response handling failed", t)
            p.future.completeExceptionally(t)
        }
    }

    private fun ByteArray.hexPreview(maxBytes: Int = MAX_HEX_PREVIEW): String {
        val n = minOf(size, maxBytes)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02x", this[i]))
        }
        if (size > n) sb.append(" …")
        return sb.toString()
    }
}
