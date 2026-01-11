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

internal object RadiacodeDataBuf {
    /**
     * Port of cdump/radiacode decode_VS_DATA_BUF for the realtime record type (eid=0,gid=0).
     *
     * Note: DATA_BUF timestamps are relative; for UI we mostly just show the latest values.
     */
    fun decodeLatestRealTime(dataBuf: ByteArray): RealTimeData? {
        val br = ByteReader(dataBuf)

        // python uses: base_time = datetime.now() + timedelta(seconds=128)
        val baseTime = Instant.now().plusSeconds(128)

        var latest: RealTimeData? = null

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

                    latest = RealTimeData(
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
                    // RareData: <IfHHH>
                    if (br.remaining < 14) break
                    br.readBytes(14)
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

        return latest
    }
}
