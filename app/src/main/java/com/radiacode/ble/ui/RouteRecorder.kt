package com.radiacode.ble.ui

import android.content.Context
import android.location.Location
import com.radiacode.ble.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Route Recorder with Waypoints
 * 
 * Records GPS tracks with radiation readings and allows user-defined waypoints.
 * Supports export to GPX format for use in mapping software.
 */
class RouteRecorder(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("route_recorder", Context.MODE_PRIVATE)
    
    private var isRecording = false
    private var currentRoute: Route? = null
    private var routePoints = mutableListOf<RoutePoint>()
    private var waypoints = mutableListOf<Waypoint>()
    private var startTimeMs: Long = 0
    
    var onRouteUpdated: ((Route) -> Unit)? = null

    /**
     * Start recording a new route.
     */
    fun startRecording(name: String = generateRouteName()): Route {
        isRecording = true
        startTimeMs = System.currentTimeMillis()
        routePoints.clear()
        waypoints.clear()
        
        currentRoute = Route(
            id = UUID.randomUUID().toString(),
            name = name,
            startTimeMs = startTimeMs,
            endTimeMs = 0,
            points = routePoints,
            waypoints = waypoints
        )
        
        saveCurrentRoute()
        return currentRoute!!
    }

    /**
     * Stop recording and finalize the route.
     */
    fun stopRecording(): Route? {
        if (!isRecording) return null
        
        isRecording = false
        currentRoute = currentRoute?.copy(
            endTimeMs = System.currentTimeMillis()
        )
        
        // Save to route history
        saveRouteToHistory(currentRoute!!)
        clearCurrentRoute()
        
        val route = currentRoute
        currentRoute = null
        return route
    }

    /**
     * Add a point to the current route.
     */
    fun addPoint(location: Location, doseRate: Float, countRate: Float, temperature: Float? = null) {
        if (!isRecording) return
        
        val point = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            accuracy = location.accuracy,
            timestampMs = System.currentTimeMillis(),
            doseRate = doseRate,
            countRate = countRate,
            temperature = temperature
        )
        
        routePoints.add(point)
        saveCurrentRoute()
        
        currentRoute?.let { onRouteUpdated?.invoke(it) }
    }

    /**
     * Add a user-defined waypoint.
     */
    fun addWaypoint(
        location: Location,
        name: String,
        description: String = "",
        type: WaypointType = WaypointType.POINT_OF_INTEREST,
        doseRate: Float? = null
    ): Waypoint {
        val waypoint = Waypoint(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            timestampMs = System.currentTimeMillis(),
            type = type,
            doseRate = doseRate
        )
        
        if (isRecording) {
            waypoints.add(waypoint)
            saveCurrentRoute()
        }
        
        return waypoint
    }

    /**
     * Add a hotspot waypoint automatically when high radiation is detected.
     */
    fun addHotspotWaypoint(location: Location, doseRate: Float, countRate: Float): Waypoint {
        return addWaypoint(
            location = location,
            name = "Hotspot (%.2f µSv/h)".format(doseRate),
            description = "Auto-detected radiation hotspot. CPS: %.0f".format(countRate),
            type = WaypointType.HOTSPOT,
            doseRate = doseRate
        )
    }

    /**
     * Export route to GPX format.
     */
    fun exportToGpx(route: Route): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        
        val gpx = StringBuilder()
        gpx.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        gpx.appendLine("""<gpx version="1.1" creator="Open RadiaCode">""")
        gpx.appendLine("""  <metadata>""")
        gpx.appendLine("""    <name>${route.name}</name>""")
        gpx.appendLine("""    <time>${dateFormat.format(Date(route.startTimeMs))}</time>""")
        gpx.appendLine("""  </metadata>""")
        
        // Waypoints
        route.waypoints.forEach { wp ->
            gpx.appendLine("""  <wpt lat="${wp.latitude}" lon="${wp.longitude}">""")
            wp.altitude?.let { gpx.appendLine("""    <ele>$it</ele>""") }
            gpx.appendLine("""    <time>${dateFormat.format(Date(wp.timestampMs))}</time>""")
            gpx.appendLine("""    <name>${wp.name}</name>""")
            if (wp.description.isNotEmpty()) {
                gpx.appendLine("""    <desc>${wp.description}</desc>""")
            }
            gpx.appendLine("""    <sym>${wp.type.gpxSymbol}</sym>""")
            // Custom extensions for radiation data
            wp.doseRate?.let {
                gpx.appendLine("""    <extensions><radiacode:doserate>$it</radiacode:doserate></extensions>""")
            }
            gpx.appendLine("""  </wpt>""")
        }
        
        // Track
        gpx.appendLine("""  <trk>""")
        gpx.appendLine("""    <name>${route.name}</name>""")
        gpx.appendLine("""    <trkseg>""")
        
        route.points.forEach { pt ->
            gpx.appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
            pt.altitude?.let { gpx.appendLine("""        <ele>$it</ele>""") }
            gpx.appendLine("""        <time>${dateFormat.format(Date(pt.timestampMs))}</time>""")
            // Custom extensions for radiation data
            gpx.appendLine("""        <extensions>""")
            gpx.appendLine("""          <radiacode:doserate>${pt.doseRate}</radiacode:doserate>""")
            gpx.appendLine("""          <radiacode:countrate>${pt.countRate}</radiacode:countrate>""")
            pt.temperature?.let {
                gpx.appendLine("""          <radiacode:temperature>$it</radiacode:temperature>""")
            }
            gpx.appendLine("""        </extensions>""")
            gpx.appendLine("""      </trkpt>""")
        }
        
        gpx.appendLine("""    </trkseg>""")
        gpx.appendLine("""  </trk>""")
        gpx.appendLine("""</gpx>""")
        
        return gpx.toString()
    }

    /**
     * Get route statistics.
     */
    fun getRouteStats(route: Route): RouteStats {
        if (route.points.isEmpty()) {
            return RouteStats.EMPTY
        }
        
        val doseRates = route.points.map { it.doseRate }
        val countRates = route.points.map { it.countRate }
        
        // Calculate distance
        var totalDistance = 0.0
        for (i in 1 until route.points.size) {
            val prev = route.points[i - 1]
            val curr = route.points[i]
            totalDistance += calculateDistance(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude
            )
        }
        
        // Calculate elevation gain
        var elevationGain = 0.0
        var elevationLoss = 0.0
        val altitudes = route.points.mapNotNull { it.altitude }
        if (altitudes.size > 1) {
            for (i in 1 until altitudes.size) {
                val diff = altitudes[i] - altitudes[i - 1]
                if (diff > 0) elevationGain += diff
                else elevationLoss -= diff
            }
        }
        
        val durationMs = if (route.endTimeMs > 0) {
            route.endTimeMs - route.startTimeMs
        } else {
            route.points.lastOrNull()?.timestampMs?.minus(route.startTimeMs) ?: 0
        }
        
        return RouteStats(
            pointCount = route.points.size,
            waypointCount = route.waypoints.size,
            durationMs = durationMs,
            distanceMeters = totalDistance,
            elevationGainMeters = elevationGain,
            elevationLossMeters = elevationLoss,
            minDoseRate = doseRates.minOrNull() ?: 0f,
            maxDoseRate = doseRates.maxOrNull() ?: 0f,
            avgDoseRate = doseRates.average().toFloat(),
            minCountRate = countRates.minOrNull() ?: 0f,
            maxCountRate = countRates.maxOrNull() ?: 0f,
            avgCountRate = countRates.average().toFloat(),
            hotspotCount = route.waypoints.count { it.type == WaypointType.HOTSPOT }
        )
    }

    // Haversine formula for distance
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun isRecording() = isRecording
    
    fun getCurrentRoute() = currentRoute

    private fun generateRouteName(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "Route ${dateFormat.format(Date())}"
    }

    private fun saveCurrentRoute() {
        currentRoute?.let { route ->
            val json = routeToJson(route)
            sharedPrefs.edit().putString("current_route", json.toString()).apply()
        }
    }

    private fun clearCurrentRoute() {
        sharedPrefs.edit().remove("current_route").apply()
    }

    private fun saveRouteToHistory(route: Route) {
        val historyJson = sharedPrefs.getString("route_history", "[]")
        val history = JSONArray(historyJson)
        history.put(routeToJson(route))
        
        // Keep only last 50 routes
        while (history.length() > 50) {
            history.remove(0)
        }
        
        sharedPrefs.edit().putString("route_history", history.toString()).apply()
    }

    fun getRouteHistory(): List<Route> {
        val historyJson = sharedPrefs.getString("route_history", "[]")
        val history = JSONArray(historyJson)
        val routes = mutableListOf<Route>()
        
        for (i in 0 until history.length()) {
            try {
                routes.add(jsonToRoute(history.getJSONObject(i)))
            } catch (e: Exception) {
                // Skip invalid entries
            }
        }
        
        return routes.sortedByDescending { it.startTimeMs }
    }

    private fun routeToJson(route: Route): JSONObject {
        return JSONObject().apply {
            put("id", route.id)
            put("name", route.name)
            put("startTimeMs", route.startTimeMs)
            put("endTimeMs", route.endTimeMs)
            
            val pointsArray = JSONArray()
            route.points.forEach { pt ->
                pointsArray.put(JSONObject().apply {
                    put("lat", pt.latitude)
                    put("lon", pt.longitude)
                    pt.altitude?.let { put("alt", it) }
                    put("acc", pt.accuracy)
                    put("ts", pt.timestampMs)
                    put("dose", pt.doseRate)
                    put("cps", pt.countRate)
                    pt.temperature?.let { put("temp", it) }
                })
            }
            put("points", pointsArray)
            
            val waypointsArray = JSONArray()
            route.waypoints.forEach { wp ->
                waypointsArray.put(JSONObject().apply {
                    put("id", wp.id)
                    put("name", wp.name)
                    put("desc", wp.description)
                    put("lat", wp.latitude)
                    put("lon", wp.longitude)
                    wp.altitude?.let { put("alt", it) }
                    put("ts", wp.timestampMs)
                    put("type", wp.type.name)
                    wp.doseRate?.let { put("dose", it) }
                })
            }
            put("waypoints", waypointsArray)
        }
    }

    private fun jsonToRoute(json: JSONObject): Route {
        val pointsArray = json.getJSONArray("points")
        val points = mutableListOf<RoutePoint>()
        for (i in 0 until pointsArray.length()) {
            val pt = pointsArray.getJSONObject(i)
            points.add(RoutePoint(
                latitude = pt.getDouble("lat"),
                longitude = pt.getDouble("lon"),
                altitude = if (pt.has("alt")) pt.getDouble("alt") else null,
                accuracy = pt.getDouble("acc").toFloat(),
                timestampMs = pt.getLong("ts"),
                doseRate = pt.getDouble("dose").toFloat(),
                countRate = pt.getDouble("cps").toFloat(),
                temperature = if (pt.has("temp")) pt.getDouble("temp").toFloat() else null
            ))
        }
        
        val waypointsArray = json.getJSONArray("waypoints")
        val waypoints = mutableListOf<Waypoint>()
        for (i in 0 until waypointsArray.length()) {
            val wp = waypointsArray.getJSONObject(i)
            waypoints.add(Waypoint(
                id = wp.getString("id"),
                name = wp.getString("name"),
                description = wp.optString("desc", ""),
                latitude = wp.getDouble("lat"),
                longitude = wp.getDouble("lon"),
                altitude = if (wp.has("alt")) wp.getDouble("alt") else null,
                timestampMs = wp.getLong("ts"),
                type = WaypointType.valueOf(wp.optString("type", "POINT_OF_INTEREST")),
                doseRate = if (wp.has("dose")) wp.getDouble("dose").toFloat() else null
            ))
        }
        
        return Route(
            id = json.getString("id"),
            name = json.getString("name"),
            startTimeMs = json.getLong("startTimeMs"),
            endTimeMs = json.getLong("endTimeMs"),
            points = points,
            waypoints = waypoints
        )
    }

    // Data classes
    data class Route(
        val id: String,
        val name: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val points: List<RoutePoint>,
        val waypoints: List<Waypoint>
    )

    data class RoutePoint(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val accuracy: Float,
        val timestampMs: Long,
        val doseRate: Float,
        val countRate: Float,
        val temperature: Float?
    )

    data class Waypoint(
        val id: String,
        val name: String,
        val description: String,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val timestampMs: Long,
        val type: WaypointType,
        val doseRate: Float?
    )

    enum class WaypointType(val gpxSymbol: String, val emoji: String) {
        POINT_OF_INTEREST("Flag, Blue", "📍"),
        HOTSPOT("Danger Area", "☢️"),
        SAMPLE_LOCATION("Geocache", "🧪"),
        PHOTO("Photo", "📷"),
        NOTE("Information", "📝"),
        START("Trailhead", "🏁"),
        END("City (Medium)", "🎯")
    }

    data class RouteStats(
        val pointCount: Int,
        val waypointCount: Int,
        val durationMs: Long,
        val distanceMeters: Double,
        val elevationGainMeters: Double,
        val elevationLossMeters: Double,
        val minDoseRate: Float,
        val maxDoseRate: Float,
        val avgDoseRate: Float,
        val minCountRate: Float,
        val maxCountRate: Float,
        val avgCountRate: Float,
        val hotspotCount: Int
    ) {
        companion object {
            val EMPTY = RouteStats(0, 0, 0, 0.0, 0.0, 0.0, 0f, 0f, 0f, 0f, 0f, 0f, 0)
        }
        
        fun formatDuration(): String {
            val seconds = durationMs / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, secs)
            } else {
                "%d:%02d".format(minutes, secs)
            }
        }
        
        fun formatDistance(): String {
            return if (distanceMeters >= 1000) {
                "%.2f km".format(distanceMeters / 1000)
            } else {
                "%.0f m".format(distanceMeters)
            }
        }
    }
}
