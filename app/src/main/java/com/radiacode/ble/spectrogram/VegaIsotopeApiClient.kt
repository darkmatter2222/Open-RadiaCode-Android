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
 * Vega Isotope Identification API Client
 * 
 * Communicates with the Vega Isotope Identification API to analyze gamma spectra
 * and identify radioactive isotopes with probabilities and estimated activities.
 * 
 * API Endpoint: POST /identify
 * Input: 2D matrix of spectrum snapshots (each row is a 1-second differential snapshot with 1023 channels)
 * Output: List of detected isotopes with probabilities and activity estimates
 */
object VegaIsotopeApiClient {

    private const val TAG = "VegaIsotopeAPI"
    
    // API base URL - same server as Vega TTS
    private const val API_BASE_URL = "http://99.122.58.29:443"
    private const val IDENTIFY_ENDPOINT = "/identify"
    
    // Expected channel count for the API
    const val EXPECTED_CHANNELS = 1023
    
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
                
                // Build 2D matrix: each row is one snapshot, each column is one channel
                val matrix = JSONArray()
                var totalCounts = 0L
                
                for (snapshot in snapshots) {
                    val counts = snapshot.spectrumData.counts
                    val row = JSONArray()
                    
                    // Ensure each row has exactly EXPECTED_CHANNELS values
                    for (i in 0 until EXPECTED_CHANNELS) {
                        val count = if (i < counts.size) counts[i] else 0
                        row.put(count)
                        totalCounts += count
                    }
                    matrix.put(row)
                }
                
                // Build request JSON with 2D spectrum matrix
                val requestJson = JSONObject().apply {
                    put("spectrum", matrix)
                    put("threshold", threshold.toDouble())
                    put("return_all", returnAll)
                }
                
                Log.d(TAG, "Sending ${snapshots.size} rows x $EXPECTED_CHANNELS channels, " +
                        "total counts: $totalCounts")
                
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
    
}
