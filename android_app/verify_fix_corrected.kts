#!/usr/bin/env kotlin

/**
 * Corrected mathematical verification - checking the right corners!
 */

import kotlin.math.*

val HEX_SIZE_METERS = 25.0

data class Point(val x: Double, val y: Double)
data class HexCoord(val q: Int, val r: Int)

fun hexToCartesian(q: Int, r: Int): Point {
    val x = HEX_SIZE_METERS * (sqrt(3.0) * q + sqrt(3.0) / 2.0 * r)
    val y = HEX_SIZE_METERS * (3.0 / 2.0 * r)
    return Point(x, y)
}

fun getHexCornersFlat(center: Point): List<Point> {
    return (0 until 6).map { i ->
        val angleDeg = 60.0 * i  // Flat-top: 0°, 60°, 120°, 180°, 240°, 300°
        val angleRad = Math.toRadians(angleDeg)
        val cornerX = center.x + HEX_SIZE_METERS * cos(angleRad)
        val cornerY = center.y + HEX_SIZE_METERS * sin(angleRad)
        Point(cornerX, cornerY)
    }
}

fun getHexCornersPointy(center: Point): List<Point> {
    return (0 until 6).map { i ->
        val angleDeg = 60.0 * i - 30.0  // Pointy-top: -30°, 30°, 90°, 150°, 210°, 270°
        val angleRad = Math.toRadians(angleDeg)
        val cornerX = center.x + HEX_SIZE_METERS * cos(angleRad)
        val cornerY = center.y + HEX_SIZE_METERS * sin(angleRad)
        Point(cornerX, cornerY)
    }
}

fun distance(p1: Point, p2: Point): Double {
    return sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
}

fun main() {
    println("=== HEXAGON TESSELLATION ANALYSIS ===\n")
    
    // Test three adjacent hexagons in a row
    val hex0 = HexCoord(0, 0)
    val hex1 = HexCoord(1, 0)  // Right neighbor (q+1, r)
    val hex2 = HexCoord(0, 1)  // Lower-right neighbor (q, r+1)
    
    val center0 = hexToCartesian(hex0.q, hex0.r)
    val center1 = hexToCartesian(hex1.q, hex1.r)
    val center2 = hexToCartesian(hex2.q, hex2.r)
    
    println("Testing flat-top hexagon tessellation:")
    println("Hex (0,0) center: (%.2f, %.2f)".format(center0.x, center0.y))
    println("Hex (1,0) center: (%.2f, %.2f)".format(center1.x, center1.y))
    println("Hex (0,1) center: (%.2f, %.2f)".format(center2.x, center2.y))
    println()
    
    // Get corners using flat-top angles (CORRECT)
    val corners0 = getHexCornersFlat(center0)
    val corners1 = getHexCornersFlat(center1)
    val corners2 = getHexCornersFlat(center2)
    
    println("=== FLAT-TOP CORNERS (CORRECT) ===")
    println("\nHex (0,0) corners:")
    corners0.forEachIndexed { i, c -> 
        val angle = 60.0 * i
        println("  [$i] ${"%.0f".format(angle)}°: (%.2f, %.2f)".format(c.x, c.y))
    }
    
    println("\nHex (1,0) corners:")
    corners1.forEachIndexed { i, c -> 
        val angle = 60.0 * i
        println("  [$i] ${"%.0f".format(angle)}°: (%.2f, %.2f)".format(c.x, c.y))
    }
    
    // For flat-top, hex(0,0) and hex(1,0) share an edge
    // The shared edge is between:
    // - hex(0,0)'s corners at 60° and 300° (indices 1 and 5)
    // - hex(1,0)'s corners at 120° and 240° (indices 2 and 4)
    println("\n--- Checking shared edge between (0,0) and (1,0) ---")
    println("Hex (0,0) corner [1] 60°:  (%.2f, %.2f)".format(corners0[1].x, corners0[1].y))
    println("Hex (1,0) corner [4] 240°: (%.2f, %.2f)".format(corners1[4].x, corners1[4].y))
    val dist1 = distance(corners0[1], corners1[4])
    println("Distance: %.6f meters".format(dist1))
    println("Match: ${dist1 < 0.001}")
    println()
    
    println("Hex (0,0) corner [5] 300°: (%.2f, %.2f)".format(corners0[5].x, corners0[5].y))
    println("Hex (1,0) corner [2] 120°: (%.2f, %.2f)".format(corners1[2].x, corners1[2].y))
    val dist2 = distance(corners0[5], corners1[2])
    println("Distance: %.6f meters".format(dist2))
    println("Match: ${dist2 < 0.001}")
    println()
    
    // Get corners using pointy-top angles (INCORRECT)
    val cornersWrong0 = getHexCornersPointy(center0)
    val cornersWrong1 = getHexCornersPointy(center1)
    
    println("=== POINTY-TOP CORNERS (INCORRECT) ===")
    println("\n--- Checking would-be shared edge with wrong angles ---")
    // With pointy-top angles on flat-top grid, corners won't align
    println("Hex (0,0) corner [1] 30°:  (%.2f, %.2f)".format(cornersWrong0[1].x, cornersWrong0[1].y))
    println("Hex (1,0) corner [4] 210°: (%.2f, %.2f)".format(cornersWrong1[4].x, cornersWrong1[4].y))
    val distWrong1 = distance(cornersWrong0[1], cornersWrong1[4])
    println("Distance: %.6f meters".format(distWrong1))
    println("Match: ${distWrong1 < 0.001}")
    println()
    
    println("=== CONCLUSION ===")
    println("Flat-top corners: Edges ${if (dist1 < 0.001 && dist2 < 0.001) "MATCH" else "DON'T MATCH"}")
    println("Pointy-top corners: Edges ${if (distWrong1 < 0.001) "MATCH" else "DON'T MATCH"}")
}

main()
