package com.radiacode.ble

import java.time.Instant

internal data class RealTimeData(
    val timestamp: Instant,
    val countRate: Float,
    val doseRate: Float,
    val countRateErr: Float,
    val doseRateErr: Float,
    val flags: Int,
    val realTimeFlags: Int,
)

/**
 * Device metadata from RareData record (eid=0, gid=3).
 * Contains battery level and temperature.
 */
internal data class DeviceMetadata(
    val timestamp: Instant,
    val batteryLevel: Int,      // 0-100 percent
    val temperature: Float,     // Celsius
)

/**
 * Combined realtime data with device metadata.
 */
internal data class FullDeviceData(
    val realTimeData: RealTimeData?,
    val metadata: DeviceMetadata?
)

internal object RadiacodeDataBuf {
    /**
     * Port of cdump/radiacode decode_VS_DATA_BUF for the realtime record type (eid=0,gid=0).
     *
     * Note: DATA_BUF timestamps are relative; for UI we mostly just show the latest values.
     */
    fun decodeLatestRealTime(dataBuf: ByteArray): RealTimeData? {
        return decodeFullData(dataBuf).realTimeData
    }
    
    /**
     * Decode both RealTimeData and DeviceMetadata from DATA_BUF.
     * Returns both pieces of data when available.
     */
    fun decodeFullData(dataBuf: ByteArray): FullDeviceData {
        val br = ByteReader(dataBuf)

        // python uses: base_time = datetime.now() + timedelta(seconds=128)
        val baseTime = Instant.now().plusSeconds(128)

        var latestRealTime: RealTimeData? = null
        var latestMetadata: DeviceMetadata? = null

        while (br.remaining >= 7) {
            // Ported record header: <BBBi> (seq,eid,gid,ts_offset)
            br.readU8() // seq (unused)
            val eid = br.readU8()
            val gid = br.readU8()
            val tsOffset = br.readI32LE() // units of 10ms

            val recordTime = baseTime.plusMillis(tsOffset.toLong() * 10L)

            // Ported from radiacode/decoders/databuf.py. We only *return* RealTimeData,
            // but we must skip other record types or we'll stop before seeing realtime.
            when {
                eid == 0 && gid == 0 -> {
                    if (br.remaining < (4 + 4 + 2 + 2 + 2 + 1)) break
                    val countRate = br.readF32LE()
                    val doseRate = br.readF32LE()
                    val countRateErr = br.readU16LE() / 10.0f
                    val doseRateErr = br.readU16LE() / 10.0f
                    val flags = br.readU16LE()
                    val rtFlags = br.readU8()

                    latestRealTime = RealTimeData(
                        timestamp = recordTime,
                        countRate = countRate,
                        doseRate = doseRate,
                        countRateErr = countRateErr,
                        doseRateErr = doseRateErr,
                        flags = flags,
                        realTimeFlags = rtFlags,
                    )
                }

                eid == 0 && gid == 1 -> {
                    // RawData: <ff>
                    if (br.remaining < 8) break
                    br.readBytes(8)
                }

                eid == 0 && gid == 2 -> {
                    // DoseRateDB: <IffHH>
                    if (br.remaining < 16) break
                    br.readBytes(16)
                }

                eid == 0 && gid == 3 -> {
                    // RareData: <IfHHH> - Contains duration, dose, temperature, charge_level, flags
                    // Format from cdump/radiacode Python library:
                    //   duration (U32): dose accumulation duration in seconds
                    //   dose (F32): accumulated radiation dose  
                    //   temperature (U16): encoded as (temp_C * 100 + 2000), so decode: (raw - 2000) / 100
                    //   charge_level (U16): encoded as percent * 100 (0-10000 -> 0-100%)
                    //   flags (U16): status flags
                    if (br.remaining < 14) break
                    br.readU32LE()  // duration (unused - dose accumulation time in seconds)
                    br.readF32LE()  // dose (unused - accumulated dose)
                    val temperatureRaw = br.readU16LE()
                    val chargeLevelRaw = br.readU16LE()
                    br.readU16LE()  // flags (unused)
                    
                    // Decode temperature: stored as (temp_C * 100 + 2000)
                    val temperature = (temperatureRaw - 2000) / 100.0f
                    
                    // Decode charge level: stored as percent * 100 (0-10000)
                    val chargeLevel = (chargeLevelRaw / 100).coerceIn(0, 100)
                    
                    latestMetadata = DeviceMetadata(
                        timestamp = recordTime,
                        batteryLevel = chargeLevel,
                        temperature = temperature
                    )
                }

                eid == 0 && (gid == 4 || gid == 5) -> {
                    // UserData / SheduleData: <IffHH> (python marks TODO but skips)
                    if (br.remaining < 16) break
                    br.readBytes(16)
                }

                eid == 0 && gid == 6 -> {
                    // AccelData: <HHH>
                    if (br.remaining < 6) break
                    br.readBytes(6)
                }

                eid == 0 && gid == 7 -> {
                    // Event: <BBH>
                    if (br.remaining < 4) break
                    br.readBytes(4)
                }

                eid == 0 && (gid == 8 || gid == 9) -> {
                    // RawCountRate / RawDoseRate: <fH>
                    if (br.remaining < 6) break
                    br.readBytes(6)
                }

                eid == 1 && gid in 1..3 -> {
                    // Sample blocks: <HI> then skip (8|16|14) * samples
                    if (br.remaining < 6) break
                    val samplesNum = br.readU16LE()
                    br.readU32LE() // smpl_time_ms (unused)
                    val bytesPerSample = when (gid) {
                        1 -> 8
                        2 -> 16
                        else -> 14
                    }
                    val toSkip = bytesPerSample.toLong() * samplesNum.toLong()
                    if (toSkip > Int.MAX_VALUE.toLong()) break
                    val n = toSkip.toInt()
                    if (br.remaining < n) break
                    br.readBytes(n)
                }

                else -> {
                    // Unknown record type; stop parsing.
                    break
                }
            }
        }

        return FullDeviceData(latestRealTime, latestMetadata)
    }
}
