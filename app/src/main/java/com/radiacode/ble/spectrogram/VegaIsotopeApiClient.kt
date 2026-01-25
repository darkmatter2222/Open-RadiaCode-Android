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

/**
 * Vega Isotope Identification API Client (v2.0)
 * 
 * Communicates with the Vega 2D Isotope Identification API to analyze gamma spectra
 * and identify radioactive isotopes with probabilities and estimated activities.
 * 
 * API Endpoint: POST /identify
 * Input: 2D matrix (60 time intervals x 1023 energy channels)
 *   - Axis 0: Time intervals (60 one-second intervals)
 *   - Axis 1: Energy channels (1023 channels, 20 keV to 3000 keV)
 * Output: List of detected isotopes with probabilities and activity estimates
 * 
 * API Version: 2.0.0
 */
object VegaIsotopeApiClient {

    private const val TAG = "VegaIsotopeAPI"
    
    // API base URL - same server as Vega TTS
    private const val API_BASE_URL = "http://99.122.58.29:443"
    private const val IDENTIFY_ENDPOINT = "/identify"
    
    // Spectrum dimensions (fixed by model architecture)
    const val EXPECTED_CHANNELS = 1023          // Energy channels
    const val REQUIRED_TIME_INTERVALS = 60      // Time dimension (1-second intervals)
    
    // Model's expected energy calibration (LINEAR: 20-3000 keV over 1023 channels)
    // The model was trained with this specific calibration
    private const val MODEL_E_MIN = 20.0        // keV
    private const val MODEL_E_MAX = 3000.0      // keV
    
    private val executor: Executor = Executors.newSingleThreadExecutor()
    
    /**
     * Result of isotope identification
     */
    data class IsotopeResult(
        val name: String,
        val probability: Float,      // 0.0 to 1.0
        val activityBq: Float,       // Estimated activity in Becquerels
        val primaryEnergy: Float     // Primary gamma line energy in keV
    )
    
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
        onResult: (IdentificationResponse) -> Unit
    ) {
        executor.execute {
            try {
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
                
                Log.d(TAG, "Device calibration: a0=$deviceA0, a1=$deviceA1, a2=$deviceA2")
                
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
                
                // Step 2: Find global max across rebinned 60x1023 matrix for normalization
                var globalMax = 0.0
                var totalCounts = 0.0
                
                for (rebinned in rebinnedSpectra) {
                    for (count in rebinned) {
                        if (count > globalMax) globalMax = count
                        totalCounts += count
                    }
                }
                
                Log.d(TAG, "Rebinned global max: $globalMax, total counts: $totalCounts")
                
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
                
                // Validate matrix dimensions (API v2.0 requires 60x1023)
                if (snapshots.size != REQUIRED_TIME_INTERVALS) {
                    Log.w(TAG, "Warning: Expected $REQUIRED_TIME_INTERVALS time intervals, got ${snapshots.size}")
                }
                
                // Build request JSON with normalized 2D spectrum matrix
                val requestJson = JSONObject().apply {
                    put("spectrum", matrix)
                    put("threshold", threshold.toDouble())
                    put("return_all", returnAll)
                }
                
                Log.d(TAG, "Sending ${snapshots.size}x$EXPECTED_CHANNELS normalized matrix, " +
                        "global_max: $globalMax, total_counts: $totalCounts")
                
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
                
                // Read response
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    val response = parseResponse(responseText)
                    Log.d(TAG, "Identification complete: ${response.numDetected} isotopes detected, " +
                            "${response.processingTimeMs}ms processing time")
                    onResult(response)
                } else {
                    val errorText = try {
                        connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "Unknown error"
                    } catch (e: Exception) {
                        "HTTP $responseCode"
                    }
                    Log.e(TAG, "API error: HTTP $responseCode - $errorText")
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
        val output = DoubleArray(EXPECTED_CHANNELS)
        
        // For each output channel, calculate what energy the model expects
        // then find where that energy is in the source spectrum
        for (outChannel in 0 until EXPECTED_CHANNELS) {
            // Model's expected energy for this channel (linear calibration)
            val targetEnergy = MODEL_E_MIN + outChannel * (MODEL_E_MAX - MODEL_E_MIN) / EXPECTED_CHANNELS
            
            // Find corresponding channel in source spectrum using device's quadratic calibration
            // Solve: a0 + a1*ch + a2*ch² = targetEnergy for ch
            val sourceChannel = energyToChannel(
                energyKeV = targetEnergy.toFloat(),
                a0 = sourceA0,
                a1 = sourceA1,
                a2 = sourceA2,
                maxChannel = sourceCounts.size - 1
            )
            
            // Linear interpolation between adjacent channels
            val channelFloor = sourceChannel.toInt().coerceIn(0, sourceCounts.size - 2)
            val channelCeil = (channelFloor + 1).coerceIn(0, sourceCounts.size - 1)
            val fraction = sourceChannel - channelFloor
            
            // Interpolate counts
            val countFloor = sourceCounts.getOrElse(channelFloor) { 0 }.toDouble()
            val countCeil = sourceCounts.getOrElse(channelCeil) { 0 }.toDouble()
            output[outChannel] = countFloor * (1 - fraction) + countCeil * fraction
        }
        
        return output
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
        return if (kotlin.math.abs(a2) < 1e-9f) {
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
