package com.radiacode.ble

/**
 * Contextual comparisons for radiation readings.
 * Converts abstract μSv/h values into relatable real-world equivalents.
 * 
 * Reference sources:
 * - UNSCEAR reports
 * - US NRC
 * - Health Physics Society
 */
object RadiationComparison {

    /**
     * Radiation context comparison with source attribution
     */
    data class Comparison(
        val description: String,
        val equivalentValue: Float,    // Equivalent dose in μSv
        val emoji: String,
        val category: Category,
        val timeMultiplier: String     // e.g., "per hour", "total", "per year"
    ) {
        enum class Category {
            NATURAL_BACKGROUND,
            FOOD,
            MEDICAL,
            TRAVEL,
            OCCUPATIONAL,
            SAFETY_LIMIT
        }
    }
    
    // Reference doses (all in μSv)
    private val BANANA_DOSE = 0.1f               // μSv per banana (K-40)
    private val DENTAL_XRAY = 5f                 // μSv per dental X-ray
    private val CHEST_XRAY = 20f                 // μSv per chest X-ray
    private val MAMMOGRAM = 400f                 // μSv per mammogram
    private val CT_HEAD = 2000f                  // μSv per head CT
    private val CT_CHEST = 7000f                 // μSv per chest CT
    private val CT_ABDOMEN = 10000f              // μSv per abdominal CT
    
    private val FLIGHT_PER_HOUR = 3f             // μSv/h at cruising altitude
    private val NYC_TO_LA_FLIGHT = 40f           // μSv total
    private val TRANSATLANTIC_FLIGHT = 60f       // μSv total (NYC to London)
    
    private val US_AVG_ANNUAL = 3100f            // μSv/year average American
    private val GLOBAL_AVG_ANNUAL = 2400f        // μSv/year global average
    
    private val NRC_WORKER_ANNUAL = 50000f       // μSv/year occupational limit
    private val NRC_PUBLIC_ANNUAL = 1000f        // μSv/year public limit
    
    private val ASTRONAUT_PER_DAY = 500f         // μSv/day on ISS
    
    private val BACKGROUND_LOW = 0.05f           // μSv/h typical low background
    private val BACKGROUND_NORMAL = 0.1f         // μSv/h typical average
    private val BACKGROUND_HIGH = 0.3f           // μSv/h elevated background
    
    /**
     * Get contextual comparisons for a dose rate reading.
     * @param uSvPerHour Current dose rate in μSv/h
     * @return List of relevant comparisons, sorted by relevance
     */
    fun getComparisons(uSvPerHour: Float): List<Comparison> {
        val comparisons = mutableListOf<Comparison>()
        
        // Calculate hourly dose
        val hourlyDose = uSvPerHour
        val dailyDose = uSvPerHour * 24
        val yearlyDose = uSvPerHour * 8760  // 24 * 365
        
        // Background comparisons (always relevant)
        when {
            hourlyDose < BACKGROUND_LOW -> {
                comparisons.add(Comparison(
                    "Below typical background",
                    BACKGROUND_LOW,
                    "🌿",
                    Comparison.Category.NATURAL_BACKGROUND,
                    "per hour"
                ))
            }
            hourlyDose < BACKGROUND_NORMAL -> {
                comparisons.add(Comparison(
                    "Normal background radiation",
                    BACKGROUND_NORMAL,
                    "🏠",
                    Comparison.Category.NATURAL_BACKGROUND,
                    "per hour"
                ))
            }
            hourlyDose < BACKGROUND_HIGH -> {
                comparisons.add(Comparison(
                    "Slightly elevated background",
                    BACKGROUND_HIGH,
                    "🏔️",
                    Comparison.Category.NATURAL_BACKGROUND,
                    "per hour"
                ))
            }
            else -> {
                comparisons.add(Comparison(
                    "Above typical background",
                    hourlyDose,
                    "⚠️",
                    Comparison.Category.NATURAL_BACKGROUND,
                    "per hour"
                ))
            }
        }
        
        // Banana equivalent (fun, relatable)
        val bananasPerHour = hourlyDose / BANANA_DOSE
        comparisons.add(Comparison(
            "≈ ${formatNumber(bananasPerHour)} bananas/hour",
            BANANA_DOSE,
            "🍌",
            Comparison.Category.FOOD,
            "per hour"
        ))
        
        // Flight comparison
        val flightComparison = hourlyDose / FLIGHT_PER_HOUR
        comparisons.add(Comparison(
            "≈ ${formatPercent(flightComparison)} of airplane cruising",
            FLIGHT_PER_HOUR,
            "✈️",
            Comparison.Category.TRAVEL,
            "per hour"
        ))
        
        // Dental X-ray equivalent (per hour)
        val dentalPerHour = hourlyDose / DENTAL_XRAY
        comparisons.add(Comparison(
            "≈ ${formatNumber(dentalPerHour)} dental X-rays/hour",
            DENTAL_XRAY,
            "🦷",
            Comparison.Category.MEDICAL,
            "per hour"
        ))
        
        // Time to reach various doses
        if (hourlyDose > 0.001f) {
            val hoursToChestXray = CHEST_XRAY / hourlyDose
            val hoursToDentalXray = DENTAL_XRAY / hourlyDose
            val hoursToUSAnnual = US_AVG_ANNUAL / hourlyDose
            
            // Pick the most relevant time comparison
            when {
                hoursToDentalXray < 1 -> {
                    comparisons.add(Comparison(
                        "1 dental X-ray equivalent in ${formatMinutes(hoursToDentalXray * 60)}",
                        DENTAL_XRAY,
                        "⏱️",
                        Comparison.Category.MEDICAL,
                        "total"
                    ))
                }
                hoursToChestXray < 24 -> {
                    comparisons.add(Comparison(
                        "1 chest X-ray equivalent in ${formatHours(hoursToChestXray)}",
                        CHEST_XRAY,
                        "🫁",
                        Comparison.Category.MEDICAL,
                        "total"
                    ))
                }
                hoursToUSAnnual < 8760 -> {
                    comparisons.add(Comparison(
                        "US yearly average in ${formatDays(hoursToUSAnnual / 24)}",
                        US_AVG_ANNUAL,
                        "🇺🇸",
                        Comparison.Category.NATURAL_BACKGROUND,
                        "per year"
                    ))
                }
            }
        }
        
        // Annual projection
        val annualPercent = (yearlyDose / US_AVG_ANNUAL) * 100
        comparisons.add(Comparison(
            "≈ ${formatPercent(annualPercent / 100)} of US annual average",
            US_AVG_ANNUAL,
            "📅",
            Comparison.Category.NATURAL_BACKGROUND,
            "per year"
        ))
        
        // Safety context for elevated readings
        if (hourlyDose > 1.0f) {
            val hoursToPublicLimit = NRC_PUBLIC_ANNUAL / hourlyDose
            comparisons.add(Comparison(
                "NRC public limit in ${formatHours(hoursToPublicLimit)}",
                NRC_PUBLIC_ANNUAL,
                "⚖️",
                Comparison.Category.SAFETY_LIMIT,
                "per year"
            ))
        }
        
        if (hourlyDose > 5.0f) {
            val hoursToWorkerLimit = NRC_WORKER_ANNUAL / hourlyDose
            comparisons.add(Comparison(
                "Worker limit in ${formatDays(hoursToWorkerLimit / 24)}",
                NRC_WORKER_ANNUAL,
                "👷",
                Comparison.Category.OCCUPATIONAL,
                "per year"
            ))
        }
        
        return comparisons
    }
    
    /**
     * Get a single-line summary for compact display
     */
    fun getQuickSummary(uSvPerHour: Float): String {
        val bananasPerHour = uSvPerHour / BANANA_DOSE
        val flightPercent = (uSvPerHour / FLIGHT_PER_HOUR) * 100
        
        return when {
            uSvPerHour < 0.03f -> "🌿 Very low — below typical background"
            uSvPerHour < 0.08f -> "🏠 Normal background — ${formatNumber(bananasPerHour)} 🍌/h"
            uSvPerHour < 0.2f -> "🏠 Typical background — ${formatNumber(bananasPerHour)} 🍌/h"
            uSvPerHour < 0.5f -> "🏔️ Elevated — like high altitude, ${formatNumber(bananasPerHour)} 🍌/h"
            uSvPerHour < 1.0f -> "✈️ Like flying — ${formatPercent(flightPercent / 100)} of cruising"
            uSvPerHour < 5.0f -> "⚠️ Elevated — ${formatNumber(bananasPerHour)} 🍌/h equivalent"
            uSvPerHour < 20f -> "⚠️ High — limit exposure time"
            uSvPerHour < 100f -> "🚨 Very high — minimize time in area"
            else -> "🚨 Extremely high — leave area immediately"
        }
    }
    
    /**
     * Get safety status with color coding
     */
    data class SafetyStatus(
        val level: Level,
        val shortDescription: String,
        val recommendation: String,
        val colorHex: String
    ) {
        enum class Level { SAFE, CAUTION, WARNING, DANGER, EMERGENCY }
    }
    
    fun getSafetyStatus(uSvPerHour: Float): SafetyStatus {
        return when {
            uSvPerHour < 0.5f -> SafetyStatus(
                SafetyStatus.Level.SAFE,
                "Safe",
                "Normal background levels. No precautions needed.",
                "69F0AE"  // pro_green
            )
            uSvPerHour < 2.0f -> SafetyStatus(
                SafetyStatus.Level.CAUTION,
                "Elevated",
                "Above normal but safe for extended periods.",
                "FFD740"  // pro_amber
            )
            uSvPerHour < 10f -> SafetyStatus(
                SafetyStatus.Level.WARNING,
                "High",
                "Limit exposure. Safe for short periods.",
                "FF9800"  // orange
            )
            uSvPerHour < 100f -> SafetyStatus(
                SafetyStatus.Level.DANGER,
                "Very High",
                "Minimize time in area. Consider leaving.",
                "FF5252"  // pro_red
            )
            else -> SafetyStatus(
                SafetyStatus.Level.EMERGENCY,
                "Extreme",
                "Leave area immediately. Seek clean air.",
                "FF0000"  // bright red
            )
        }
    }
    
    // Formatting helpers
    private fun formatNumber(value: Float): String {
        return when {
            value < 0.01f -> String.format("%.3f", value)
            value < 0.1f -> String.format("%.2f", value)
            value < 10f -> String.format("%.1f", value)
            value < 1000f -> String.format("%.0f", value)
            else -> String.format("%.1fk", value / 1000)
        }
    }
    
    private fun formatPercent(ratio: Float): String {
        val percent = ratio * 100
        return when {
            percent < 1f -> String.format("%.1f%%", percent)
            percent < 10f -> String.format("%.0f%%", percent)
            percent < 100f -> String.format("%.0f%%", percent)
            else -> String.format("%.0f×", ratio)
        }
    }
    
    private fun formatMinutes(mins: Float): String {
        return when {
            mins < 1f -> "< 1 min"
            mins < 60f -> String.format("%.0f min", mins)
            else -> formatHours(mins / 60)
        }
    }
    
    private fun formatHours(hours: Float): String {
        return when {
            hours < 1f -> formatMinutes(hours * 60)
            hours < 24f -> String.format("%.1f hours", hours)
            else -> formatDays(hours / 24)
        }
    }
    
    private fun formatDays(days: Float): String {
        return when {
            days < 1f -> formatHours(days * 24)
            days < 30f -> String.format("%.1f days", days)
            days < 365f -> String.format("%.1f months", days / 30)
            else -> String.format("%.1f years", days / 365)
        }
    }
}
