package com.radiacode.ble.ui

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Smart Location Manager
 * Provides intelligent location tagging with place recognition and caching
 */
class SmartLocationManager(private val context: Context) {

    data class LocationTag(
        val latitude: Double,
        val longitude: Double,
        val placeName: String,
        val placeType: PlaceType,
        val address: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class PlaceType(val emoji: String, val label: String) {
        HOME("🏠", "Home"),
        WORK("🏢", "Work"),
        OUTDOOR("🌳", "Outdoors"),
        TRANSIT("🚗", "Transit"),
        BUILDING("🏛️", "Building"),
        UNKNOWN("📍", "Location")
    }

    // Saved locations for quick recognition
    private val savedLocations = mutableMapOf<String, LocationTag>()
    
    // Recent location cache
    private var lastGeocodedLocation: LocationTag? = null
    private var lastGeocodedTime = 0L
    private val geocodeCacheTimeMs = 60_000L // Cache for 1 minute

    init {
        loadSavedLocations()
    }

    /**
     * Get a smart tag for the given location
     * Uses saved locations first, then geocoding
     */
    suspend fun getLocationTag(location: Location): LocationTag {
        // Check saved locations first
        savedLocations.values.find { saved ->
            distanceMeters(location.latitude, location.longitude, saved.latitude, saved.longitude) < 100
        }?.let { return it }

        // Check cache
        lastGeocodedLocation?.let { cached ->
            if (System.currentTimeMillis() - lastGeocodedTime < geocodeCacheTimeMs &&
                distanceMeters(location.latitude, location.longitude, cached.latitude, cached.longitude) < 50) {
                return cached
            }
        }

        // Geocode
        return geocodeLocation(location)
    }

    /**
     * Quick tag without geocoding (for frequent updates)
     */
    fun getQuickTag(location: Location): LocationTag {
        // Check saved locations
        savedLocations.values.find { saved ->
            distanceMeters(location.latitude, location.longitude, saved.latitude, saved.longitude) < 100
        }?.let { return it }

        // Use cached result if close enough
        lastGeocodedLocation?.let { cached ->
            if (distanceMeters(location.latitude, location.longitude, cached.latitude, cached.longitude) < 200) {
                return cached.copy(latitude = location.latitude, longitude = location.longitude)
            }
        }

        // Return generic location tag
        return LocationTag(
            latitude = location.latitude,
            longitude = location.longitude,
            placeName = formatCoordinates(location.latitude, location.longitude),
            placeType = PlaceType.UNKNOWN,
            address = null
        )
    }

    /**
     * Save current location with a name
     */
    fun saveLocation(location: Location, name: String, type: PlaceType) {
        val tag = LocationTag(
            latitude = location.latitude,
            longitude = location.longitude,
            placeName = name,
            placeType = type,
            address = null
        )
        savedLocations[name] = tag
        persistSavedLocations()
    }

    /**
     * Remove a saved location
     */
    fun removeSavedLocation(name: String) {
        savedLocations.remove(name)
        persistSavedLocations()
    }

    /**
     * Get all saved locations
     */
    fun getSavedLocations(): List<LocationTag> = savedLocations.values.toList()

    private suspend fun geocodeLocation(location: Location): LocationTag {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val tag = addressToLocationTag(location, address)
                    
                    // Cache result
                    lastGeocodedLocation = tag
                    lastGeocodedTime = System.currentTimeMillis()
                    
                    tag
                } else {
                    createDefaultTag(location)
                }
            } catch (e: Exception) {
                createDefaultTag(location)
            }
        }
    }

    private fun addressToLocationTag(location: Location, address: Address): LocationTag {
        val placeName = buildPlaceName(address)
        val placeType = inferPlaceType(address)
        val fullAddress = buildFullAddress(address)

        return LocationTag(
            latitude = location.latitude,
            longitude = location.longitude,
            placeName = placeName,
            placeType = placeType,
            address = fullAddress
        )
    }

    private fun buildPlaceName(address: Address): String {
        // Priority: Feature name > Thoroughfare > Locality > Admin area
        return when {
            !address.featureName.isNullOrBlank() && 
                address.featureName != address.subThoroughfare -> address.featureName
            !address.thoroughfare.isNullOrBlank() -> {
                if (!address.subThoroughfare.isNullOrBlank()) {
                    "${address.subThoroughfare} ${address.thoroughfare}"
                } else {
                    address.thoroughfare
                }
            }
            !address.locality.isNullOrBlank() -> address.locality
            !address.adminArea.isNullOrBlank() -> address.adminArea
            else -> formatCoordinates(address.latitude, address.longitude)
        }
    }

    private fun buildFullAddress(address: Address): String {
        val parts = mutableListOf<String>()
        
        if (!address.subThoroughfare.isNullOrBlank()) parts.add(address.subThoroughfare)
        if (!address.thoroughfare.isNullOrBlank()) parts.add(address.thoroughfare)
        if (!address.locality.isNullOrBlank()) parts.add(address.locality)
        if (!address.adminArea.isNullOrBlank()) parts.add(address.adminArea)
        if (!address.postalCode.isNullOrBlank()) parts.add(address.postalCode)
        
        return parts.joinToString(", ")
    }

    private fun inferPlaceType(address: Address): PlaceType {
        val featureName = address.featureName?.lowercase() ?: ""
        val thoroughfare = address.thoroughfare?.lowercase() ?: ""
        
        return when {
            featureName.contains("park") || featureName.contains("garden") -> PlaceType.OUTDOOR
            featureName.contains("office") || featureName.contains("center") -> PlaceType.WORK
            featureName.contains("station") || featureName.contains("terminal") -> PlaceType.TRANSIT
            thoroughfare.contains("highway") || thoroughfare.contains("freeway") -> PlaceType.TRANSIT
            else -> PlaceType.BUILDING
        }
    }

    private fun createDefaultTag(location: Location): LocationTag {
        return LocationTag(
            latitude = location.latitude,
            longitude = location.longitude,
            placeName = formatCoordinates(location.latitude, location.longitude),
            placeType = PlaceType.UNKNOWN,
            address = null
        )
    }

    private fun formatCoordinates(lat: Double, lon: Double): String {
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format("%.4f°%s, %.4f°%s", kotlin.math.abs(lat), latDir, kotlin.math.abs(lon), lonDir)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun loadSavedLocations() {
        val prefs = context.getSharedPreferences("smart_locations", Context.MODE_PRIVATE)
        val json = prefs.getString("saved_locations", null) ?: return
        
        try {
            // Simple JSON parsing
            val regex = """"name":"([^"]+)","lat":([\d.-]+),"lon":([\d.-]+),"type":"([^"]+)"""".toRegex()
            regex.findAll(json).forEach { match ->
                val name = match.groupValues[1]
                val lat = match.groupValues[2].toDouble()
                val lon = match.groupValues[3].toDouble()
                val type = PlaceType.values().find { it.name == match.groupValues[4] } ?: PlaceType.UNKNOWN
                
                savedLocations[name] = LocationTag(lat, lon, name, type, null)
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }

    private fun persistSavedLocations() {
        val prefs = context.getSharedPreferences("smart_locations", Context.MODE_PRIVATE)
        
        val jsonArray = savedLocations.values.joinToString(",") { loc ->
            """{"name":"${loc.placeName}","lat":${loc.latitude},"lon":${loc.longitude},"type":"${loc.placeType.name}"}"""
        }
        
        prefs.edit().putString("saved_locations", "[$jsonArray]").apply()
    }

    companion object {
        @Volatile
        private var instance: SmartLocationManager? = null

        fun getInstance(context: Context): SmartLocationManager {
            return instance ?: synchronized(this) {
                instance ?: SmartLocationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
