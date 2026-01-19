#!/usr/bin/env kotlin

/**
 * Mathematical verification of hexagon tessellation fix.
 * This demonstrates that flat-top corner angles produce proper tessellation.
 */

import kotlin.math.*

val HEX_SIZE_METERS = 25.0

data class Point(val x: Double, val y: Double)
data class HexCoord(val q: Int, val r: Int)

// Flat-top hexagon: axial to cartesian
fun hexToCartesian(q: Int, r: Int): Point {
    val x = HEX_SIZE_METERS * (sqrt(3.0) * q + sqrt(3.0) / 2.0 * r)
    val y = HEX_SIZE_METERS * (3.0 / 2.0 * r)
    return Point(x, y)
}

// Flat-top hexagon: cartesian to axial
fun cartesianToHex(x: Double, y: Double): HexCoord {
    val q = (sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * y) / HEX_SIZE_METERS
    val r = (2.0 / 3.0 * y) / HEX_SIZE_METERS
    return axialRound(q, r)
}

fun axialRound(q: Double, r: Double): HexCoord {
    val s = -q - r
    var rq = round(q).toInt()
    var rr = round(r).toInt()
    var rs = round(s).toInt()
    
    val qDiff = abs(rq - q)
    val rDiff = abs(rr - r)
    val sDiff = abs(rs - s)
    
    if (qDiff > rDiff && qDiff > sDiff) {
        rq = -rr - rs
    } else if (rDiff > sDiff) {
        rr = -rq - rs
    }
    
    return HexCoord(rq, rr)
}

// Generate corners using CORRECT flat-top angles
fun getHexCornersCorrect(center: Point): List<Point> {
    return (0 until 6).map { i ->
        val angleDeg = 60.0 * i  // Flat-top: start at 0°
        val angleRad = Math.toRadians(angleDeg)
        val cornerX = center.x + HEX_SIZE_METERS * cos(angleRad)
        val cornerY = center.y + HEX_SIZE_METERS * sin(angleRad)
        Point(cornerX, cornerY)
    }
}

// Generate corners using INCORRECT pointy-top angles
fun getHexCornersIncorrect(center: Point): List<Point> {
    return (0 until 6).map { i ->
        val angleDeg = 60.0 * i - 30.0  // Pointy-top: start at -30°
        val angleRad = Math.toRadians(angleDeg)
        val cornerX = center.x + HEX_SIZE_METERS * cos(angleRad)
        val cornerY = center.y + HEX_SIZE_METERS * sin(angleRad)
        Point(cornerX, cornerY)
    }
}

fun main() {
    println("=== HEXAGON TESSELLATION VERIFICATION ===\n")
    
    // Test adjacent hexagons
    val hex1 = HexCoord(0, 0)
    val hex2 = HexCoord(1, 0)  // Right neighbor
    
    val center1 = hexToCartesian(hex1.q, hex1.r)
    val center2 = hexToCartesian(hex2.q, hex2.r)
    
    println("Hex 1 (0,0) center: (${center1.x}, ${center1.y})")
    println("Hex 2 (1,0) center: (${center2.x}, ${center2.y})")
    println()
    
    // For flat-top hexagons, horizontal neighbors should be spaced by sqrt(3) * size
    val expectedDistance = sqrt(3.0) * HEX_SIZE_METERS
    val actualDistance = sqrt((center2.x - center1.x).pow(2) + (center2.y - center1.y).pow(2))
    
    println("Expected distance between centers: $expectedDistance meters")
    println("Actual distance: $actualDistance meters")
    println("Match: ${abs(expectedDistance - actualDistance) < 0.001}")
    println()
    
    // Test corner overlap with CORRECT angles
    val corners1Correct = getHexCornersCorrect(center1)
    val corners2Correct = getHexCornersCorrect(center2)
    
    println("=== CORRECT (Flat-Top) Corners ===")
    println("Hex 1 corner 0 (rightmost): (${corners1Correct[0].x}, ${corners1Correct[0].y})")
    println("Hex 2 corner 3 (leftmost):  (${corners2Correct[3].x}, ${corners2Correct[3].y})")
    
    // For proper tessellation, hex1's rightmost corner and hex2's leftmost corner should be close
    val cornerDist = sqrt(
        (corners1Correct[0].x - corners2Correct[3].x).pow(2) + 
        (corners1Correct[0].y - corners2Correct[3].y).pow(2)
    )
    println("Distance between shared corners: $cornerDist meters")
    println("Proper tessellation: ${cornerDist < 0.1}") // Should be nearly 0
    println()
    
    // Test corner overlap with INCORRECT angles
    val corners1Incorrect = getHexCornersIncorrect(center1)
    val corners2Incorrect = getHexCornersIncorrect(center2)
    
    println("=== INCORRECT (Pointy-Top) Corners ===")
    println("Hex 1 corner 0: (${corners1Incorrect[0].x}, ${corners1Incorrect[0].y})")
    println("Hex 2 corner 3: (${corners2Incorrect[3].x}, ${corners2Incorrect[3].y})")
    
    val cornerDistIncorrect = sqrt(
        (corners1Incorrect[0].x - corners2Incorrect[3].x).pow(2) + 
        (corners1Incorrect[0].y - corners2Incorrect[3].y).pow(2)
    )
    println("Distance between would-be shared corners: $cornerDistIncorrect meters")
    println("Proper tessellation: ${cornerDistIncorrect < 0.1}") // Should be far from 0
    println()
    
    // Verify round-trip conversion
    println("=== Round-Trip Verification ===")
    val testPoint = Point(100.0, 100.0)
    val hex = cartesianToHex(testPoint.x, testPoint.y)
    val roundTrip = hexToCartesian(hex.q, hex.r)
    
    println("Original point: (${testPoint.x}, ${testPoint.y})")
    println("Converted to hex: (${hex.q}, ${hex.r})")
    println("Back to cartesian: (${roundTrip.x}, ${roundTrip.y})")
    
    val distance = sqrt((testPoint.x - roundTrip.x).pow(2) + (testPoint.y - roundTrip.y).pow(2))
    println("Distance from original: $distance meters")
    println("Within one hex: ${distance <= HEX_SIZE_METERS}")
}

main()
