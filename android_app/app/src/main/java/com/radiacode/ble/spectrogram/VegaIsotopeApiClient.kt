package com.radiacode.ble.spectrogram

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vega Isotope Identification API Client (v2.0)
 * 
 * Communicates with the Vega 2D Isotope Identification API to analyze gamma spectra
 * and identify radioactive isotopes with probabilities and estimated activities.
 * 
 * API Endpoint: POST /identify
 * Input: 2D matrix (time intervals x 1023 energy channels)
 *   - Axis 0: Time intervals (typically 300 one-second intervals)
 *   - Axis 1: Energy channels (1023 channels, 20 keV to 3000 keV)
 * Output: List of detected isotopes with probabilities and activity estimates
 * 
 * API Version: 3.0.0
 */
object VegaIsotopeApiClient {

    private const val TAG = "VegaIsotopeAPI"
    
    // API base URL - same server as Vega TTS
    private const val API_BASE_URL = "http://99.122.58.29:443"
    private const val IDENTIFY_ENDPOINT = "/identify"
    
    // Spectrum dimensions (fixed by model architecture)
    const val EXPECTED_CHANNELS = 1023          // Energy channels
    const val REQUIRED_TIME_INTERVALS = 300     // Time dimension (1-second intervals)
    
    // Model's expected energy calibration (LINEAR: 20-3000 keV over 1023 channels)
    // The model was trained with this specific calibration
    private const val MODEL_E_MIN = 20.0        // keV
    private const val MODEL_E_MAX = 3000.0      // keV
    
    private val executor: Executor = Executors.newSingleThreadExecutor()
    
    /**
     * Decay chain relationships for display purposes.
     * Maps parent isotopes to their characteristic daughter products.
     */
    val DECAY_CHAIN_INFO = mapOf(
        "U-238" to DecayChainInfo(
            parent = "U-238",
            daughters = listOf("Th-234", "Pa-234m", "U-234", "Th-230", "Ra-226", "Rn-222", "Pb-214", "Bi-214", "Pb-210", "Po-210"),
            keyDaughters = listOf("Pb-214", "Bi-214", "Ra-226"),
            description = "Uranium-238 decay chain (detected via daughters)"
        ),
        "Th-232" to DecayChainInfo(
            parent = "Th-232",
            daughters = listOf("Ra-228", "Ac-228", "Th-228", "Ra-224", "Rn-220", "Pb-212", "Bi-212", "Tl-208"),
            keyDaughters = listOf("Ac-228", "Pb-212", "Tl-208"),
            description = "Thorium-232 decay chain"
        ),
        "U-235" to DecayChainInfo(
            parent = "U-235",
            daughters = listOf("Th-231", "Pa-231", "Ac-227", "Th-227", "Ra-223", "Pb-211", "Bi-211"),
            keyDaughters = listOf("Pa-231", "Th-227", "Pb-211"),
            description = "Uranium-235 (Actinium) decay chain"
        )
    )
    
    /**
     * Information about a radioactive decay chain
     */
    data class DecayChainInfo(
        val parent: String,
        val daughters: List<String>,
        val keyDaughters: List<String>,  // Most easily detected daughters
        val description: String
    )
    
    /**
     * Get decay chain info if this isotope is part of a decay chain
     */
    fun getDecayChainParent(isotopeName: String): String? {
        for ((parent, info) in DECAY_CHAIN_INFO) {
            if (isotopeName in info.daughters || isotopeName == parent) {
                return parent
            }
        }
        return null
    }
    
    /**
     * Check if isotope is a key daughter indicator for its parent chain
     */
    fun isKeyDaughter(isotopeName: String): Boolean {
        return DECAY_CHAIN_INFO.values.any { isotopeName in it.keyDaughters }
    }
    
    /**
     * Result of isotope identification
     */
    data class IsotopeResult(
        val name: String,
        val probability: Float,      // 0.0 to 1.0
        val activityBq: Float,       // Estimated activity in Becquerels
        val primaryEnergy: Float     // Primary gamma line energy in keV
    ) {
        /** Returns parent decay chain name if this isotope is part of one */
        val decayChainParent: String? get() = getDecayChainParent(name)
        
        /** True if this isotope is a key daughter for detecting its parent chain */
        val isKeyDaughter: Boolean get() = VegaIsotopeApiClient.isKeyDaughter(name)
    }
    
    /**
     * Response from the isotope identification API
     */
    data class IdentificationResponse(
        val success: Boolean,
        val isotopes: List<IsotopeResult>,
        val numDetected: Int,
        val confidence: Float,       // Overall confidence 0.0 to 1.0
        val processingTimeMs: Float,
        val errorMessage: String? = null
    )
    
    /**
     * Identify isotopes from a series of gamma spectrum snapshots.
     * 
     * @param snapshots List of spectrum snapshots (each is one 1-second measurement)
     * @param threshold Detection threshold (0-1). Lower = more sensitive. Default 0.5
     * @param returnAll If true, return all 82 isotopes. If false, only detected ones.
     * @param onResult Callback with the identification results
     */
    fun identifyIsotopes(
        snapshots: List<SpectrumSnapshot>,
        threshold: Float = 0.5f,
        returnAll: Boolean = false,
        requestId: String? = null,
        clientDeviceId: String? = null,
        windowStartMs: Long? = null,
        windowEndMs: Long? = null,
        integrationTimeMs: Int? = null,
        windowLengthSeconds: Int? = null,
        onResult: (IdentificationResponse) -> Unit
    ) {
        executor.execute {
            try {
                val reqId = requestId ?: "vega_iso_${System.currentTimeMillis()}"
                val t0 = android.os.SystemClock.elapsedRealtime()

                if (snapshots.isEmpty()) {
                    onResult(IdentificationResponse(
                        success = false,
                        isotopes = emptyList(),
                        numDetected = 0,
                        confidence = 0f,
                        processingTimeMs = 0f,
                        errorMessage = "No spectrum data provided"
                    ))
                    return@execute
                }
                
                // Get device calibration from first snapshot (all should have same calibration)
                val deviceA0 = snapshots[0].spectrumData.a0
                val deviceA1 = snapshots[0].spectrumData.a1
                val deviceA2 = snapshots[0].spectrumData.a2
                
                Log.d(TAG, "[$reqId] Device calibration: a0=$deviceA0, a1=$deviceA1, a2=$deviceA2")
                
                // Step 1: Rebin all spectra from device calibration to model calibration
                // The model expects LINEAR calibration: E = 20 + channel * (2980/1023)
                // The device uses QUADRATIC: E = a0 + a1*ch + a2*ch²
                val rebinnedSpectra = snapshots.map { snapshot ->
                    rebinSpectrum(
                        sourceCounts = snapshot.spectrumData.counts,
                        sourceA0 = deviceA0,
                        sourceA1 = deviceA1,
                        sourceA2 = deviceA2
                    )
                }

                val tRebin = android.os.SystemClock.elapsedRealtime()
                
                // Step 2: Find global max across rebinned Tx1023 matrix for normalization
                var globalMax = 0.0
                var totalCounts = 0.0
                
                for (rebinned in rebinnedSpectra) {
                    for (count in rebinned) {
                        if (count > globalMax) globalMax = count
                        totalCounts += count
                    }
                }
                
                Log.d(TAG, "[$reqId] Rebin done in ${tRebin - t0}ms. globalMax=$globalMax totalCounts=$totalCounts")
                
                // Step 3: Build normalized 2D matrix (values in [0.0, 1.0])
                val matrix = JSONArray()
                
                for (rebinned in rebinnedSpectra) {
                    val row = JSONArray()
                    for (count in rebinned) {
                        val normalized = if (globalMax > 0) count / globalMax else 0.0
                        row.put(normalized)
                    }
                    matrix.put(row)
                }
                
                // Validate matrix dimensions (model expects a fixed time window; server may pad/truncate)
                if (snapshots.size != REQUIRED_TIME_INTERVALS) {
                    Log.w(TAG, "Warning: Expected $REQUIRED_TIME_INTERVALS time intervals, got ${snapshots.size}")
                }
                
                // Build request JSON with normalized 2D spectrum matrix
                val requestJson = JSONObject().apply {
                    put("spectrum", matrix)
                    put("threshold", threshold.toDouble())
                    put("return_all", returnAll)

                    // Extra metadata (server may ignore; helps debug end-to-end issues)
                    put("request_id", reqId)
                    put("client_device_id", clientDeviceId)
                    put("window_start_ms", windowStartMs)
                    put("window_end_ms", windowEndMs)
                    put("window_length_seconds", windowLengthSeconds)
                    put("integration_time_ms", integrationTimeMs)
                    put("client_normalization", "global_max")
                    put(
                        "calibration",
                        JSONObject().apply {
                            put("type", "quadratic")
                            put("a0", deviceA0.toDouble())
                            put("a1", deviceA1.toDouble())
                            put("a2", deviceA2.toDouble())
                            put("model_grid_min_kev", MODEL_E_MIN)
                            put("model_grid_max_kev", MODEL_E_MAX)
                            put("model_grid_channels", EXPECTED_CHANNELS)
                        }
                    )
                }
                
                Log.d(TAG, "[$reqId] Sending ${snapshots.size}x$EXPECTED_CHANNELS matrix; globalMax=$globalMax totalCounts=$totalCounts")
                
                // Make HTTP request
                val url = URL("$API_BASE_URL$IDENTIFY_ENDPOINT")
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    doOutput = true
                }
                
                // Send request body
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestJson.toString())
                    writer.flush()
                }

                val tSent = android.os.SystemClock.elapsedRealtime()
                
                // Read response
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    val response = parseResponse(responseText)
                    val tDone = android.os.SystemClock.elapsedRealtime()
                    Log.d(
                        TAG,
                        "[$reqId] HTTP 200; netMs=${tDone - tSent} totalMs=${tDone - t0} numDetected=${response.numDetected} apiProcessingMs=${response.processingTimeMs}"
                    )
                    onResult(response)
                } else {
                    val errorText = try {
                        connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "Unknown error"
                    } catch (e: Exception) {
                        "HTTP $responseCode"
                    }
                    Log.e(TAG, "[$reqId] API error: HTTP $responseCode - $errorText")
                    onResult(IdentificationResponse(
                        success = false,
                        isotopes = emptyList(),
                        numDetected = 0,
                        confidence = 0f,
                        processingTimeMs = 0f,
                        errorMessage = "API error: HTTP $responseCode"
                    ))
                }
                
                connection.disconnect()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to identify isotopes", e)
                onResult(IdentificationResponse(
                    success = false,
                    isotopes = emptyList(),
                    numDetected = 0,
                    confidence = 0f,
                    processingTimeMs = 0f,
                    errorMessage = e.message ?: "Network error"
                ))
            }
        }
    }
    
    /**
     * Parse the JSON response from the API
     */
    private fun parseResponse(json: String): IdentificationResponse {
        return try {
            val obj = JSONObject(json)
            
            val isotopesArray = obj.optJSONArray("isotopes") ?: JSONArray()
            val isotopes = mutableListOf<IsotopeResult>()
            
            for (i in 0 until isotopesArray.length()) {
                val iso = isotopesArray.getJSONObject(i)
                isotopes.add(IsotopeResult(
                    name = iso.getString("name"),
                    probability = iso.getDouble("probability").toFloat(),
                    activityBq = iso.optDouble("activity_bq", 0.0).toFloat(),
                    primaryEnergy = iso.optDouble("primary_energy_kev", 0.0).toFloat()
                ))
            }
            
            IdentificationResponse(
                success = true,
                isotopes = isotopes,
                numDetected = obj.optInt("num_detected", isotopes.size),
                confidence = obj.optDouble("confidence", 0.0).toFloat(),
                processingTimeMs = obj.optDouble("processing_time_ms", 0.0).toFloat()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $json", e)
            IdentificationResponse(
                success = false,
                isotopes = emptyList(),
                numDetected = 0,
                confidence = 0f,
                processingTimeMs = 0f,
                errorMessage = "Failed to parse response: ${e.message}"
            )
        }
    }
    
    /**
     * Rebin spectrum from device calibration to model's expected calibration.
     * 
     * The model was trained with LINEAR calibration: E = 20 + channel * (2980/1023) keV
     * The device uses QUADRATIC calibration: E = a0 + a1*channel + a2*channel²
     * 
     * This function transforms the spectrum so peaks appear at the correct channels
     * for the model, regardless of the device's actual calibration.
     * 
     * @param sourceCounts Raw counts from device (typically 1024 channels)
     * @param sourceA0 Device calibration offset (keV)
     * @param sourceA1 Device calibration linear coefficient (keV/channel)
     * @param sourceA2 Device calibration quadratic coefficient (keV/channel²)
     * @return Rebinned counts array with exactly EXPECTED_CHANNELS (1023) elements
     */
    private fun rebinSpectrum(
        sourceCounts: IntArray,
        sourceA0: Float,
        sourceA1: Float,
        sourceA2: Float
    ): DoubleArray {
        // Count-preserving rebinning using energy-bin overlap.
        // We treat each source channel as an energy interval, and distribute counts into
        // fixed model bins (1023 bins spanning 20-3000 keV).
        val nSrc = sourceCounts.size
        val nTgt = EXPECTED_CHANNELS

        // Build monotonic source energy edges (length nSrc + 1) using channel centers +/- 0.5.
        val srcEdges = DoubleArray(nSrc + 1)
        for (i in 0..nSrc) {
            val ch = i.toDouble() - 0.5
            srcEdges[i] = energyAtChannel(ch, sourceA0, sourceA1, sourceA2)
        }
        // Enforce monotonic increasing edges (device calibration should be increasing, but be defensive)
        for (i in 1 until srcEdges.size) {
            if (srcEdges[i] <= srcEdges[i - 1]) {
                srcEdges[i] = srcEdges[i - 1] + 1e-6
            }
        }

        // Target edges for model bins (length nTgt + 1)
        val tgtEdges = DoubleArray(nTgt + 1)
        val step = (MODEL_E_MAX - MODEL_E_MIN) / nTgt.toDouble()
        for (j in 0..nTgt) {
            tgtEdges[j] = MODEL_E_MIN + j * step
        }

        val output = DoubleArray(nTgt)

        var iSrc = 0
        var iTgt = 0

        while (iSrc < nSrc && iTgt < nTgt) {
            val srcLo = srcEdges[iSrc]
            val srcHi = srcEdges[iSrc + 1]
            val tgtLo = tgtEdges[iTgt]
            val tgtHi = tgtEdges[iTgt + 1]

            val overlap = min(srcHi, tgtHi) - max(srcLo, tgtLo)
            if (overlap > 0) {
                val srcWidth = (srcHi - srcLo).coerceAtLeast(1e-9)
                val frac = overlap / srcWidth
                output[iTgt] += sourceCounts[iSrc].toDouble() * frac
            }

            // Advance whichever interval ends first
            if (srcHi <= tgtHi) {
                iSrc++
            } else {
                iTgt++
            }
        }

        return output
    }

    private fun energyAtChannel(channel: Double, a0: Float, a1: Float, a2: Float): Double {
        // Energy(keV) = a0 + a1*ch + a2*ch^2
        return a0.toDouble() + a1.toDouble() * channel + a2.toDouble() * channel * channel
    }
    
    /**
     * Convert energy (keV) to channel number using device calibration.
     * Solves: a0 + a1*ch + a2*ch² = E for ch using quadratic formula.
     */
    private fun energyToChannel(
        energyKeV: Float,
        a0: Float,
        a1: Float,
        a2: Float,
        maxChannel: Int
    ): Double {
        return if (abs(a2) < 1e-9f) {
            // Linear case: ch = (E - a0) / a1
            ((energyKeV - a0) / a1).toDouble().coerceIn(0.0, maxChannel.toDouble())
        } else {
            // Quadratic formula: ch = (-a1 + sqrt(a1² - 4*a2*(a0-E))) / (2*a2)
            val discriminant = a1 * a1 - 4f * a2 * (a0 - energyKeV)
            if (discriminant < 0) {
                // Fallback to linear approximation
                ((energyKeV - a0) / a1).toDouble().coerceIn(0.0, maxChannel.toDouble())
            } else {
                val channel = (-a1 + kotlin.math.sqrt(discriminant)) / (2f * a2)
                channel.toDouble().coerceIn(0.0, maxChannel.toDouble())
            }
        }
    }
    
}
