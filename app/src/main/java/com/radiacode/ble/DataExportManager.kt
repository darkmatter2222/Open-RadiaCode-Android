package com.radiacode.ble

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data Export Manager
 * Handles exporting session data to CSV, JSON, and GPX formats.
 */
object DataExportManager {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    
    /**
     * Export data point with all available information
     */
    data class ExportDataPoint(
        val timestampMs: Long,
        val uSvPerHour: Float,
        val cps: Float,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,
        val accuracy: Float? = null,
        val deviceId: String? = null,
        val deviceName: String? = null,
        val isotopeTop: String? = null,
        val isotopeConfidence: Float? = null,
        val temperature: Float? = null,
        val batteryLevel: Int? = null
    )
    
    /**
     * Export session metadata
     */
    data class SessionMetadata(
        val sessionId: String,
        val startTime: Long,
        val endTime: Long,
        val sampleCount: Int,
        val deviceIds: List<String>,
        val appVersion: String,
        val exportTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Export format options
     */
    enum class ExportFormat {
        CSV,        // Standard CSV for spreadsheet import
        JSON,       // JSON for data processing
        GPX,        // GPX for GPS track visualization
        ALL         // Export all formats
    }
    
    /**
     * Generate a unique session ID
     */
    fun generateSessionId(): String {
        return "session_${filenameDateFormat.format(Date())}_${UUID.randomUUID().toString().take(8)}"
    }
    
    /**
     * Export session data to files
     * @return Map of format to file path
     */
    fun exportSession(
        context: Context,
        dataPoints: List<ExportDataPoint>,
        metadata: SessionMetadata,
        format: ExportFormat = ExportFormat.ALL
    ): Map<ExportFormat, File> {
        val results = mutableMapOf<ExportFormat, File>()
        val exportDir = getExportDirectory(context)
        
        val baseFilename = "radiacode_${filenameDateFormat.format(Date(metadata.startTime))}"
        
        if (format == ExportFormat.CSV || format == ExportFormat.ALL) {
            val csvFile = exportToCsv(dataPoints, metadata, File(exportDir, "$baseFilename.csv"))
            results[ExportFormat.CSV] = csvFile
        }
        
        if (format == ExportFormat.JSON || format == ExportFormat.ALL) {
            val jsonFile = exportToJson(dataPoints, metadata, File(exportDir, "$baseFilename.json"))
            results[ExportFormat.JSON] = jsonFile
        }
        
        if (format == ExportFormat.GPX || format == ExportFormat.ALL) {
            // Only export GPX if we have location data
            val hasGps = dataPoints.any { it.latitude != null && it.longitude != null }
            if (hasGps) {
                val gpxFile = exportToGpx(dataPoints, metadata, File(exportDir, "$baseFilename.gpx"))
                results[ExportFormat.GPX] = gpxFile
            }
        }
        
        return results
    }
    
    /**
     * Export to CSV format
     */
    private fun exportToCsv(
        dataPoints: List<ExportDataPoint>,
        metadata: SessionMetadata,
        outputFile: File
    ): File {
        outputFile.bufferedWriter().use { writer ->
            // Header comment with metadata
            writer.write("# RadiaCode Export\n")
            writer.write("# Session ID: ${metadata.sessionId}\n")
            writer.write("# Start Time: ${dateFormat.format(Date(metadata.startTime))}\n")
            writer.write("# End Time: ${dateFormat.format(Date(metadata.endTime))}\n")
            writer.write("# Sample Count: ${metadata.sampleCount}\n")
            writer.write("# App Version: ${metadata.appVersion}\n")
            writer.write("# Export Time: ${dateFormat.format(Date(metadata.exportTime))}\n")
            writer.write("#\n")
            
            // CSV Header
            writer.write("timestamp_utc,timestamp_ms,usv_per_hour,cps,latitude,longitude,altitude_m,accuracy_m,device_id,device_name,isotope_top,isotope_confidence,temperature_c,battery_percent\n")
            
            // Data rows
            for (point in dataPoints) {
                writer.write(buildString {
                    append(dateFormat.format(Date(point.timestampMs)))
                    append(",")
                    append(point.timestampMs)
                    append(",")
                    append(point.uSvPerHour)
                    append(",")
                    append(point.cps)
                    append(",")
                    append(point.latitude?.toString() ?: "")
                    append(",")
                    append(point.longitude?.toString() ?: "")
                    append(",")
                    append(point.altitude?.toString() ?: "")
                    append(",")
                    append(point.accuracy?.toString() ?: "")
                    append(",")
                    append(point.deviceId ?: "")
                    append(",")
                    append(escapeCSV(point.deviceName ?: ""))
                    append(",")
                    append(escapeCSV(point.isotopeTop ?: ""))
                    append(",")
                    append(point.isotopeConfidence?.toString() ?: "")
                    append(",")
                    append(point.temperature?.toString() ?: "")
                    append(",")
                    append(point.batteryLevel?.toString() ?: "")
                    append("\n")
                })
            }
        }
        return outputFile
    }
    
    /**
     * Export to JSON format
     */
    private fun exportToJson(
        dataPoints: List<ExportDataPoint>,
        metadata: SessionMetadata,
        outputFile: File
    ): File {
        outputFile.bufferedWriter().use { writer ->
            writer.write("{\n")
            writer.write("  \"metadata\": {\n")
            writer.write("    \"sessionId\": \"${metadata.sessionId}\",\n")
            writer.write("    \"startTime\": \"${dateFormat.format(Date(metadata.startTime))}\",\n")
            writer.write("    \"startTimeMs\": ${metadata.startTime},\n")
            writer.write("    \"endTime\": \"${dateFormat.format(Date(metadata.endTime))}\",\n")
            writer.write("    \"endTimeMs\": ${metadata.endTime},\n")
            writer.write("    \"sampleCount\": ${metadata.sampleCount},\n")
            writer.write("    \"deviceIds\": [${metadata.deviceIds.joinToString(",") { "\"$it\"" }}],\n")
            writer.write("    \"appVersion\": \"${metadata.appVersion}\",\n")
            writer.write("    \"exportTime\": \"${dateFormat.format(Date(metadata.exportTime))}\"\n")
            writer.write("  },\n")
            writer.write("  \"readings\": [\n")
            
            dataPoints.forEachIndexed { index, point ->
                writer.write("    {\n")
                writer.write("      \"timestamp\": \"${dateFormat.format(Date(point.timestampMs))}\",\n")
                writer.write("      \"timestampMs\": ${point.timestampMs},\n")
                writer.write("      \"uSvPerHour\": ${point.uSvPerHour},\n")
                writer.write("      \"cps\": ${point.cps}")
                
                if (point.latitude != null && point.longitude != null) {
                    writer.write(",\n      \"location\": {\n")
                    writer.write("        \"latitude\": ${point.latitude},\n")
                    writer.write("        \"longitude\": ${point.longitude}")
                    point.altitude?.let { writer.write(",\n        \"altitude\": $it") }
                    point.accuracy?.let { writer.write(",\n        \"accuracy\": $it") }
                    writer.write("\n      }")
                }
                
                if (point.deviceId != null) {
                    writer.write(",\n      \"device\": {\n")
                    writer.write("        \"id\": \"${point.deviceId}\"")
                    point.deviceName?.let { writer.write(",\n        \"name\": \"${escapeJson(it)}\"") }
                    point.temperature?.let { writer.write(",\n        \"temperature\": $it") }
                    point.batteryLevel?.let { writer.write(",\n        \"battery\": $it") }
                    writer.write("\n      }")
                }
                
                if (point.isotopeTop != null) {
                    writer.write(",\n      \"isotope\": {\n")
                    writer.write("        \"top\": \"${escapeJson(point.isotopeTop)}\"")
                    point.isotopeConfidence?.let { writer.write(",\n        \"confidence\": $it") }
                    writer.write("\n      }")
                }
                
                writer.write("\n    }")
                if (index < dataPoints.size - 1) writer.write(",")
                writer.write("\n")
            }
            
            writer.write("  ]\n")
            writer.write("}\n")
        }
        return outputFile
    }
    
    /**
     * Export to GPX format for GPS track visualization
     */
    private fun exportToGpx(
        dataPoints: List<ExportDataPoint>,
        metadata: SessionMetadata,
        outputFile: File
    ): File {
        val gpxDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        
        outputFile.bufferedWriter().use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Open RadiaCode"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:radiacode="http://radiacode.com/gpx/extensions"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata>
    <name>RadiaCode Recording ${metadata.sessionId}</name>
    <desc>Radiation monitoring track recorded with Open RadiaCode</desc>
    <time>${gpxDateFormat.format(Date(metadata.startTime))}</time>
  </metadata>
  <trk>
    <name>Radiation Track</name>
    <desc>Dose rate and count measurements along route</desc>
    <trkseg>
""")
            
            for (point in dataPoints) {
                if (point.latitude != null && point.longitude != null) {
                    writer.write("      <trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">\n")
                    point.altitude?.let { writer.write("        <ele>$it</ele>\n") }
                    writer.write("        <time>${gpxDateFormat.format(Date(point.timestampMs))}</time>\n")
                    writer.write("        <extensions>\n")
                    writer.write("          <radiacode:uSvPerHour>${point.uSvPerHour}</radiacode:uSvPerHour>\n")
                    writer.write("          <radiacode:cps>${point.cps}</radiacode:cps>\n")
                    point.isotopeTop?.let { writer.write("          <radiacode:isotope>${escapeXml(it)}</radiacode:isotope>\n") }
                    writer.write("        </extensions>\n")
                    writer.write("      </trkpt>\n")
                }
            }
            
            writer.write("""    </trkseg>
  </trk>
  
  <!-- Waypoints for significant readings -->
""")
            
            // Add waypoints for spikes (readings > 2x average)
            val avgDose = dataPoints.map { it.uSvPerHour }.average().toFloat()
            val spikes = dataPoints.filter { 
                it.latitude != null && it.longitude != null && it.uSvPerHour > avgDose * 2 
            }
            
            for ((index, spike) in spikes.withIndex()) {
                writer.write("""  <wpt lat="${spike.latitude}" lon="${spike.longitude}">
    <name>Hotspot ${index + 1}</name>
    <desc>${String.format("%.3f", spike.uSvPerHour)} μSv/h at ${gpxDateFormat.format(Date(spike.timestampMs))}</desc>
    <sym>Danger Area</sym>
    <extensions>
      <radiacode:uSvPerHour>${spike.uSvPerHour}</radiacode:uSvPerHour>
    </extensions>
  </wpt>
""")
            }
            
            writer.write("</gpx>\n")
        }
        return outputFile
    }
    
    /**
     * Get the export directory, creating it if necessary
     */
    private fun getExportDirectory(context: Context): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
        return exportDir
    }
    
    /**
     * Get list of previous exports
     */
    fun getPreviousExports(context: Context): List<File> {
        val exportDir = getExportDirectory(context)
        return exportDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Delete old exports (keep last N)
     */
    fun cleanupOldExports(context: Context, keepLast: Int = 10) {
        val exports = getPreviousExports(context)
        if (exports.size > keepLast) {
            exports.drop(keepLast).forEach { it.delete() }
        }
    }
    
    // Escape helpers
    private fun escapeCSV(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }
    
    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
