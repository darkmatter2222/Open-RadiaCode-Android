import kotlin.math.*

val HEX_SIZE_METERS = 25.0

data class Point(val x: Double, val y: Double)

fun getHexCornersFlat(centerX: Double, centerY: Double): List<Point> {
    return (0 until 6).map { i ->
        val angleDeg = 60.0 * i  // Flat-top
        val angleRad = Math.toRadians(angleDeg)
        val x = centerX + HEX_SIZE_METERS * cos(angleRad)
        val y = centerY + HEX_SIZE_METERS * sin(angleRad)
        Point(x, y)
    }
}

fun getHexCornersPointy(centerX: Double, centerY: Double): List<Point> {
    return (0 until 6).map { i ->
        val angleDeg = 60.0 * i - 30.0  // Pointy-top
        val angleRad = Math.toRadians(angleDeg)
        val x = centerX + HEX_SIZE_METERS * cos(angleRad)
        val y = centerY + HEX_SIZE_METERS * sin(angleRad)
        Point(x, y)
    }
}

fun distance(p1: Point, p2: Point) = sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))

println("=== HEXAGON SHAPE ANALYSIS ===\n")

val flat = getHexCornersFlat(0.0, 0.0)
val pointy = getHexCornersPointy(0.0, 0.0)

println("Flat-top hexagon (correct for our coordinate system):")
println("Width (x-extent):  %.2f meters".format(flat.maxOf { it.x } - flat.minOf { it.x }))
println("Height (y-extent): %.2f meters".format(flat.maxOf { it.y } - flat.minOf { it.y }))
println("Expected width:    %.2f meters".format(2 * HEX_SIZE_METERS))
println("Expected height:   %.2f meters".format(sqrt(3.0) * HEX_SIZE_METERS))

println("\nPointy-top hexagon (incorrect for our coordinate system):")
println("Width (x-extent):  %.2f meters".format(pointy.maxOf { it.x } - pointy.minOf { it.x }))
println("Height (y-extent): %.2f meters".format(pointy.maxOf { it.y } - pointy.minOf { it.y }))
println("Expected width:    %.2f meters".format(sqrt(3.0) * HEX_SIZE_METERS))
println("Expected height:   %.2f meters".format(2 * HEX_SIZE_METERS))

println("\n=== ROTATION DIFFERENCE ===")
println("Flat-top first corner:  %.1f° from center".format(0.0))
println("Pointy-top first corner: %.1f° from center".format(-30.0))
println("Rotation difference: 30°")
println("\nThis 30° rotation causes hexagons to NOT align with the coordinate grid!")
