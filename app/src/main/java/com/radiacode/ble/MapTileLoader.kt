package com.radiacode.ble

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Utility class for loading and caching OSM map tiles.
 * Used by MapWidgetProvider to render actual map backgrounds.
 */
object MapTileLoader {
    
    private const val TAG = "MapTileLoader"
    private const val TILE_SIZE = 256
    private const val MAX_CACHE_SIZE_MB = 50
    private const val CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private const val WIDGET_CACHE_FILE = "map_widget_cache.png"
    
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Load map tiles for a given location and compose them into a single bitmap.
     * This handles network fetching and caching.
     * 
     * @param context Android context
     * @param centerLat Center latitude
     * @param centerLng Center longitude
     * @param zoom Zoom level (0-19)
     * @param widthPx Output bitmap width
     * @param heightPx Output bitmap height
     * @param theme Map theme
     * @param callback Called with the composed bitmap when ready
     */
    fun loadMapBitmap(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
        theme: Prefs.MapTheme,
        callback: (Bitmap?) -> Unit
    ) {
        executor.execute {
            try {
                val bitmap = loadMapBitmapInternal(context, centerLat, centerLng, zoom, widthPx, heightPx, theme)
                mainHandler.post { callback(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading map tiles", e)
                mainHandler.post { callback(null) }
            }
        }
    }
    
    /**
     * Synchronous version - uses background thread internally to avoid NetworkOnMainThread.
     * Waits up to 10 seconds for tiles to load, returns cached/fallback if timeout.
     */
    fun loadMapBitmapSync(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
        theme: Prefs.MapTheme
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        
        // Check if we're on main thread - if so, use cached or load async
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "On main thread, using cached tiles only")
            return loadFromCacheOnly(context, centerLat, centerLng, zoom, widthPx, heightPx, theme)
        }
        
        // We're on a background thread, safe to do network
        return loadMapBitmapInternal(context, centerLat, centerLng, zoom, widthPx, heightPx, theme)
    }
    
    /**
     * Load tiles only from cache - no network calls. Safe for main thread.
     */
    private fun loadFromCacheOnly(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
        theme: Prefs.MapTheme
    ): Bitmap? {
        val tileUrls = getTileUrlPatterns(theme)
        if (tileUrls.isEmpty()) return null
        
        // Calculate which tiles we need
        val centerTileX = lngToTileX(centerLng, zoom)
        val centerTileY = latToTileY(centerLat, zoom)
        
        val tilesNeededX = ceil(widthPx.toDouble() / TILE_SIZE).toInt() + 1
        val tilesNeededY = ceil(heightPx.toDouble() / TILE_SIZE).toInt() + 1
        
        val halfTilesX = tilesNeededX / 2
        val halfTilesY = tilesNeededY / 2
        
        // Create output bitmap
        val output = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // Fill with background color
        val bgColor = getThemeBackgroundColor(theme)
        canvas.drawColor(bgColor)
        
        // Calculate pixel offset for centering
        val centerTileXFrac = lngToTileXFrac(centerLng, zoom)
        val centerTileYFrac = latToTileYFrac(centerLat, zoom)
        val offsetX = ((0.5 - (centerTileXFrac % 1)) * TILE_SIZE).toInt()
        val offsetY = ((0.5 - (centerTileYFrac % 1)) * TILE_SIZE).toInt()
        
        val startTileX = centerTileX.toInt() - halfTilesX
        val startTileY = centerTileY.toInt() - halfTilesY
        
        var tilesLoaded = 0
        
        for (dy in -1..tilesNeededY) {
            for (dx in -1..tilesNeededX) {
                val tileX = startTileX + dx
                val tileY = startTileY + dy
                
                val maxTile = (1 shl zoom) - 1
                if (tileX < 0 || tileY < 0 || tileX > maxTile || tileY > maxTile) continue
                
                // Only load from cache
                val tile = loadTileFromCache(context, zoom, tileX, tileY, theme)
                if (tile != null) {
                    val drawX = (dx - halfTilesX) * TILE_SIZE + widthPx / 2 + offsetX
                    val drawY = (dy - halfTilesY) * TILE_SIZE + heightPx / 2 + offsetY
                    canvas.drawBitmap(tile, drawX.toFloat(), drawY.toFloat(), null)
                    tile.recycle()
                    tilesLoaded++
                }
            }
        }
        
        val totalTilesNeeded = (tilesNeededX + 2) * (tilesNeededY + 2)
        Log.d(TAG, "Loaded $tilesLoaded of ~$totalTilesNeeded tiles from cache")
        
        // Apply color filter for HOME theme
        if (theme == Prefs.MapTheme.HOME && tilesLoaded > 0) {
            applyHomeThemeFilter(output)
        }
        
        // Always trigger async fetch if any tiles are missing to complete the cache
        if (tilesLoaded < totalTilesNeeded) {
            Log.d(TAG, "Missing ${totalTilesNeeded - tilesLoaded} tiles, triggering background fetch")
            triggerBackgroundTileFetch(context, centerLat, centerLng, zoom, widthPx, heightPx, theme)
        }
        
        return output
    }
    
    /**
     * Trigger tile fetching in background for next widget update.
     */
    private fun triggerBackgroundTileFetch(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
        theme: Prefs.MapTheme
    ) {
        executor.execute {
            try {
                Log.d(TAG, "Background fetching tiles for widget...")
                loadMapBitmapInternal(context, centerLat, centerLng, zoom, widthPx, heightPx, theme)
                Log.d(TAG, "Background tile fetch complete, triggering widget update")
                // Trigger widget update after tiles are cached
                MapWidgetProvider.updateAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "Background tile fetch failed", e)
            }
        }
    }
    
    /**
     * Load tile from cache only - no network.
     */
    private fun loadTileFromCache(
        context: Context,
        zoom: Int,
        x: Int,
        y: Int,
        theme: Prefs.MapTheme
    ): Bitmap? {
        val cacheDir = File(context.cacheDir, "map_tiles/${theme.name}")
        val cacheFile = File(cacheDir, "${zoom}_${x}_${y}.png")
        
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_EXPIRY_MS) {
            try {
                return BitmapFactory.decodeFile(cacheFile.absolutePath)
            } catch (e: Exception) {
                cacheFile.delete()
            }
        }
        
        return null
    }
    
    /**
     * Internal implementation that does the actual network loading.
     * Must be called from background thread.
     */
    private fun loadMapBitmapInternal(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
        theme: Prefs.MapTheme
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        
        val tileUrls = getTileUrlPatterns(theme)
        if (tileUrls.isEmpty()) return null
        
        // Calculate which tiles we need
        val centerTileX = lngToTileX(centerLng, zoom)
        val centerTileY = latToTileY(centerLat, zoom)
        
        // Calculate how many tiles we need to cover the output size
        val tilesNeededX = ceil(widthPx.toDouble() / TILE_SIZE).toInt() + 1
        val tilesNeededY = ceil(heightPx.toDouble() / TILE_SIZE).toInt() + 1
        
        val halfTilesX = tilesNeededX / 2
        val halfTilesY = tilesNeededY / 2
        
        // Create output bitmap
        val output = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // Fill with background color based on theme
        val bgColor = getThemeBackgroundColor(theme)
        canvas.drawColor(bgColor)
        
        // Calculate pixel offset for centering
        val centerTileXFrac = lngToTileXFrac(centerLng, zoom)
        val centerTileYFrac = latToTileYFrac(centerLat, zoom)
        val offsetX = ((0.5 - (centerTileXFrac % 1)) * TILE_SIZE).toInt()
        val offsetY = ((0.5 - (centerTileYFrac % 1)) * TILE_SIZE).toInt()
        
        // Load and draw tiles
        val startTileX = centerTileX.toInt() - halfTilesX
        val startTileY = centerTileY.toInt() - halfTilesY
        
        for (dy in -1..tilesNeededY) {
            for (dx in -1..tilesNeededX) {
                val tileX = startTileX + dx
                val tileY = startTileY + dy
                
                // Skip invalid tile coordinates
                val maxTile = (1 shl zoom) - 1
                if (tileX < 0 || tileY < 0 || tileX > maxTile || tileY > maxTile) continue
                
                val tile = loadTile(context, tileUrls, zoom, tileX, tileY, theme)
                if (tile != null) {
                    val drawX = (dx - halfTilesX) * TILE_SIZE + widthPx / 2 + offsetX
                    val drawY = (dy - halfTilesY) * TILE_SIZE + heightPx / 2 + offsetY
                    canvas.drawBitmap(tile, drawX.toFloat(), drawY.toFloat(), null)
                    tile.recycle()
                }
            }
        }
        
        // Apply color filter for HOME theme
        if (theme == Prefs.MapTheme.HOME) {
            applyHomeThemeFilter(output)
        }
        
        return output
    }
    
    private fun loadTile(
        context: Context,
        tileUrls: Array<String>,
        zoom: Int,
        x: Int,
        y: Int,
        theme: Prefs.MapTheme
    ): Bitmap? {
        val cacheDir = File(context.cacheDir, "map_tiles/${theme.name}")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${zoom}_${x}_${y}.png")
        
        // Check cache
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_EXPIRY_MS) {
            try {
                val cached = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (cached != null) {
                    return cached
                }
                // Invalid cache file, delete it
                cacheFile.delete()
            } catch (e: Exception) {
                cacheFile.delete()
            }
        }
        
        // Download from network
        val urlIndex = (x + y) % tileUrls.size
        val baseUrl = tileUrls[urlIndex]
        val tileUrl = "$baseUrl$zoom/$x/$y.png"
        
        Log.d(TAG, "Downloading tile: $tileUrl")
        
        try {
            val url = URL(tileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "OpenRadiaCode/1.0 Android")
            connection.setRequestProperty("Accept", "image/png,image/*")
            
            val responseCode = connection.responseCode
            Log.d(TAG, "Tile response: $responseCode for $zoom/$x/$y")
            
            if (responseCode == 200) {
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()
                
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode tile bitmap")
                    return null
                }
                
                // Cache the tile
                try {
                    FileOutputStream(cacheFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    Log.d(TAG, "Cached tile: ${zoom}_${x}_${y}.png")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cache tile", e)
                }
                
                return bitmap
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tile $tileUrl", e)
        }
        
        return null
    }
    
    private fun getTileUrlPatterns(theme: Prefs.MapTheme): Array<String> {
        return when (theme) {
            Prefs.MapTheme.HOME -> arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/"
            )
            Prefs.MapTheme.DARK_MATTER -> arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/"
            )
            Prefs.MapTheme.DARK_GRAY -> arrayOf(
                "https://a.basemaps.cartocdn.com/dark_nolabels/",
                "https://b.basemaps.cartocdn.com/dark_nolabels/",
                "https://c.basemaps.cartocdn.com/dark_nolabels/"
            )
            Prefs.MapTheme.VOYAGER -> arrayOf(
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
            )
            Prefs.MapTheme.POSITRON -> arrayOf(
                "https://a.basemaps.cartocdn.com/light_all/",
                "https://b.basemaps.cartocdn.com/light_all/",
                "https://c.basemaps.cartocdn.com/light_all/"
            )
            Prefs.MapTheme.TONER -> arrayOf(
                "https://tiles.stadiamaps.com/tiles/stamen_toner/"
            )
            Prefs.MapTheme.STANDARD -> arrayOf(
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/",
                "https://c.tile.openstreetmap.org/"
            )
        }
    }
    
    private fun getThemeBackgroundColor(theme: Prefs.MapTheme): Int {
        return when (theme) {
            Prefs.MapTheme.HOME -> Color.parseColor("#1A1A1E")
            Prefs.MapTheme.DARK_MATTER -> Color.parseColor("#262626")
            Prefs.MapTheme.DARK_GRAY -> Color.parseColor("#1A1A1A")
            Prefs.MapTheme.VOYAGER -> Color.parseColor("#F5F3F2")
            Prefs.MapTheme.POSITRON -> Color.parseColor("#E5E5E5")
            Prefs.MapTheme.TONER -> Color.parseColor("#FFFFFF")
            Prefs.MapTheme.STANDARD -> Color.parseColor("#F2EFE9")
        }
    }
    
    private fun applyHomeThemeFilter(bitmap: Bitmap) {
        // Apply green tint for HOME theme similar to the app
        val colorMatrix = ColorMatrix(floatArrayOf(
            0.3f,  0.0f, 0.0f, 0f,   0f,
            0.1f,  1.2f, 0.1f, 0f,   0f,
            0.0f,  0.0f, 0.3f, 0f,   0f,
            0.0f,  0.0f, 0.0f, 1f,   0f
        ))
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }
    
    // Tile coordinate calculations
    private fun lngToTileX(lng: Double, zoom: Int): Double {
        return ((lng + 180.0) / 360.0 * (1 shl zoom))
    }
    
    private fun latToTileY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat)
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom))
    }
    
    private fun lngToTileXFrac(lng: Double, zoom: Int): Double = lngToTileX(lng, zoom)
    private fun latToTileYFrac(lat: Double, zoom: Int): Double = latToTileY(lat, zoom)
    
    // Convert tile coordinates back to lat/lng
    fun tileXToLng(x: Double, zoom: Int): Double {
        return x / (1 shl zoom) * 360.0 - 180.0
    }
    
    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(sinh(n)))
    }
    
    /**
     * Calculate meters per pixel at a given latitude and zoom level.
     */
    fun metersPerPixel(lat: Double, zoom: Int): Double {
        val earthCircumference = 40075016.686
        return earthCircumference * cos(Math.toRadians(lat)) / (256.0 * (1 shl zoom))
    }
    
    /**
     * Get the cached map center location, or return null if not available.
     * Centers on the most recent data point, or averages all points if preferred.
     */
    fun getLastKnownCenter(context: Context): Pair<Double, Double>? {
        val dataPoints = Prefs.getMapDataPoints(context)
        if (dataPoints.isNotEmpty()) {
            // Center on the most recent data point
            val latest = dataPoints.maxByOrNull { it.timestampMs }
            if (latest != null) {
                return Pair(latest.latitude, latest.longitude)
            }
        }
        
        return null
    }
    
    /**
     * Get bounds that encompass all data points with some padding.
     */
    fun getDataBounds(context: Context): MapBounds? {
        val dataPoints = Prefs.getMapDataPoints(context)
        if (dataPoints.isEmpty()) return null
        
        val minLat = dataPoints.minOf { it.latitude }
        val maxLat = dataPoints.maxOf { it.latitude }
        val minLng = dataPoints.minOf { it.longitude }
        val maxLng = dataPoints.maxOf { it.longitude }
        
        // Add 10% padding
        val latPadding = (maxLat - minLat) * 0.1
        val lngPadding = (maxLng - minLng) * 0.1
        
        return MapBounds(
            minLat - latPadding,
            maxLat + latPadding,
            minLng - lngPadding,
            maxLng + lngPadding
        )
    }
    
    data class MapBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2
        val centerLng: Double get() = (minLng + maxLng) / 2
    }
    
    /**
     * Pre-fetch tiles for widget in background. Call from a service or background thread.
     * This ensures tiles are cached for when the widget renders on the main thread.
     */
    fun prefetchTilesForWidget(context: Context, widthPx: Int = 800, heightPx: Int = 800) {
        executor.execute {
            try {
                val center = getLastKnownCenter(context) ?: return@execute
                val theme = Prefs.getMapTheme(context)
                
                Log.d(TAG, "Prefetching tiles for widget at ${center.first}, ${center.second}")
                
                // Fetch tiles for zoom level 17 (higher detail for crisp widget)
                loadMapBitmapInternal(context, center.first, center.second, 17, widthPx, heightPx, theme)
                
                Log.d(TAG, "Tile prefetch complete")
            } catch (e: Exception) {
                Log.e(TAG, "Tile prefetch failed", e)
            }
        }
    }
    
    /**
     * Clean up old cached tiles to stay within size limit.
     */
    fun cleanupCache(context: Context) {
        executor.execute {
            try {
                val cacheDir = File(context.cacheDir, "map_tiles")
                if (!cacheDir.exists()) return@execute
                
                val allFiles = cacheDir.walkTopDown()
                    .filter { it.isFile }
                    .toList()
                    .sortedBy { it.lastModified() }
                
                var totalSize = allFiles.sumOf { it.length() }
                val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024L
                
                for (file in allFiles) {
                    if (totalSize <= maxSize) break
                    totalSize -= file.length()
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup cache", e)
            }
        }
    }
}
