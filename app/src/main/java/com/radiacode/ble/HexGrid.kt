package com.radiacode.ble

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hex grid math for a static, tessellating hexagonal grid (flat-top orientation).
 *
 * Uses axial coordinates (q, r) with Red Blob Games' flat-top layout:
 *   pixel->axial:
 *     q = (2/3 * x) / size
 *     r = (-1/3 * x + sqrt(3)/3 * y) / size
 *   axial->pixel:
 *     x = size * (3/2 * q)
 *     y = size * (sqrt(3)/2 * q + sqrt(3) * r)
 *
 * All x/y are in meters in a local tangent plane anchored at an origin lat/lng.
 */
object HexGrid {
    private const val METERS_PER_DEG_LAT = 111320.0

    data class Origin(val lat: Double, val lng: Double) {
        val metersPerDegLng: Double = METERS_PER_DEG_LAT * cos(Math.toRadians(lat))
    }

    data class Axial(val q: Int, val r: Int) {
        override fun toString(): String = "$q,$r"

        companion object {
            fun parse(id: String): Axial? {
                val parts = id.split(",")
                if (parts.size != 2) return null
                val q = parts[0].toIntOrNull() ?: return null
                val r = parts[1].toIntOrNull() ?: return null
                return Axial(q, r)
            }
        }
    }

    fun ensureReasonableOrigin(origin: Origin): Origin {
        // Guard against near-poles where cos(lat) gets too small.
        // This app is not expected to operate near the poles; clamp to avoid instability.
        val clampedLat = origin.lat.coerceIn(-85.0, 85.0)
        return if (clampedLat == origin.lat) origin else Origin(clampedLat, origin.lng)
    }

    fun latLngToAxial(lat: Double, lng: Double, origin: Origin, sizeMeters: Double): Axial {
        val o = ensureReasonableOrigin(origin)
        val x = (lng - o.lng) * o.metersPerDegLng
        val y = (lat - o.lat) * METERS_PER_DEG_LAT

        val q = (2.0 / 3.0 * x) / sizeMeters
        val r = (-1.0 / 3.0 * x + sqrt(3.0) / 3.0 * y) / sizeMeters

        return cubeRound(q, r)
    }

    fun axialToLatLng(axial: Axial, origin: Origin, sizeMeters: Double): Pair<Double, Double> {
        val o = ensureReasonableOrigin(origin)
        val (x, y) = axialToMeters(axial, sizeMeters)
        val lat = o.lat + (y / METERS_PER_DEG_LAT)
        val lng = o.lng + (x / o.metersPerDegLng)
        return Pair(lat, lng)
    }

    fun axialToCornersLatLng(axial: Axial, origin: Origin, sizeMeters: Double): List<Pair<Double, Double>> {
        val o = ensureReasonableOrigin(origin)
        val (cx, cy) = axialToMeters(axial, sizeMeters)

        // Flat-top corners: 0°, 60°, 120°, 180°, 240°, 300°
        return (0 until 6).map { i ->
            val angleRad = Math.toRadians(60.0 * i)
            val x = cx + sizeMeters * cos(angleRad)
            val y = cy + sizeMeters * sin(angleRad)
            val lat = o.lat + (y / METERS_PER_DEG_LAT)
            val lng = o.lng + (x / o.metersPerDegLng)
            Pair(lat, lng)
        }
    }

    private fun axialToMeters(axial: Axial, sizeMeters: Double): Pair<Double, Double> {
        val q = axial.q.toDouble()
        val r = axial.r.toDouble()
        val x = sizeMeters * (3.0 / 2.0 * q)
        val y = sizeMeters * (sqrt(3.0) / 2.0 * q + sqrt(3.0) * r)
        return Pair(x, y)
    }

    private fun cubeRound(q: Double, r: Double): Axial {
        // cube coords: x=q, z=r, y=-x-z
        val x = q
        val z = r
        val y = -x - z

        var rx = x.roundToInt()
        var ry = y.roundToInt()
        var rz = z.roundToInt()

        val xDiff = abs(rx - x)
        val yDiff = abs(ry - y)
        val zDiff = abs(rz - z)

        if (xDiff > yDiff && xDiff > zDiff) {
            rx = -ry - rz
        } else if (yDiff > zDiff) {
            ry = -rx - rz
        } else {
            rz = -rx - ry
        }

        return Axial(rx, rz)
    }
}
