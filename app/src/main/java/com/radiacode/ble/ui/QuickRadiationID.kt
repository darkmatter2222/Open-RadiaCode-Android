package com.radiacode.ble.ui

import android.content.Context
import android.location.Location
import com.radiacode.ble.IsotopeEncyclopedia

/**
 * "What Am I Looking At?" Quick ID
 * 
 * Provides instant context-aware explanations of radiation readings
 * based on location type, radiation level, and detected isotopes.
 */
class QuickRadiationID(private val context: Context) {

    // Use the singleton directly
    private val encyclopedia = IsotopeEncyclopedia

    /**
     * Get a quick explanation of the current radiation reading.
     */
    fun identify(
        doseRate: Float,
        countRate: Float,
        location: Location? = null,
        locationType: LocationType? = null,
        detectedIsotope: String? = null,
        temperature: Float? = null
    ): QuickIDResult {
        val level = determineRadiationLevel(doseRate)
        val sources = identifyPossibleSources(doseRate, locationType, detectedIsotope)
        val explanation = generateExplanation(doseRate, level, locationType, detectedIsotope)
        val recommendations = getRecommendations(level, locationType)
        val funFacts = getFunFacts(doseRate, locationType)
        
        return QuickIDResult(
            level = level,
            headline = getHeadline(level, doseRate, locationType),
            explanation = explanation,
            possibleSources = sources,
            recommendations = recommendations,
            funFacts = funFacts,
            isotopeInfo = detectedIsotope?.let { encyclopedia.getIsotopeInfo(it) },
            comparisonText = getComparisonText(doseRate),
            safetyEmoji = level.emoji,
            confidenceLevel = calculateConfidence(doseRate, locationType, detectedIsotope)
        )
    }

    private fun determineRadiationLevel(doseRate: Float): RadiationLevel {
        return when {
            doseRate < 0.1f -> RadiationLevel.VERY_LOW
            doseRate < 0.3f -> RadiationLevel.NORMAL
            doseRate < 0.5f -> RadiationLevel.SLIGHTLY_ELEVATED
            doseRate < 2.5f -> RadiationLevel.ELEVATED
            doseRate < 10f -> RadiationLevel.HIGH
            doseRate < 100f -> RadiationLevel.VERY_HIGH
            else -> RadiationLevel.EXTREME
        }
    }

    private fun getHeadline(level: RadiationLevel, doseRate: Float, locationType: LocationType?): String {
        return when (level) {
            RadiationLevel.VERY_LOW -> "Ultra-low background radiation"
            RadiationLevel.NORMAL -> "Normal background radiation"
            RadiationLevel.SLIGHTLY_ELEVATED -> when (locationType) {
                LocationType.AIRPORT -> "Typical airport security area"
                LocationType.HOSPITAL -> "Normal hospital radiation levels"
                LocationType.GRANITE_BUILDING -> "Granite building - naturally elevated"
                LocationType.AIRPLANE -> "Normal in-flight cosmic radiation"
                LocationType.MOUNTAIN -> "Higher altitude = more cosmic rays"
                else -> "Slightly above average background"
            }
            RadiationLevel.ELEVATED -> "Elevated radiation detected"
            RadiationLevel.HIGH -> "⚠️ High radiation levels"
            RadiationLevel.VERY_HIGH -> "🚨 Very high radiation!"
            RadiationLevel.EXTREME -> "☢️ DANGEROUS RADIATION LEVELS"
        }
    }

    private fun generateExplanation(
        doseRate: Float,
        level: RadiationLevel,
        locationType: LocationType?,
        detectedIsotope: String?
    ): String {
        val base = when (level) {
            RadiationLevel.VERY_LOW -> 
                "This is an exceptionally low reading, below typical natural background. " +
                "You might be in a well-shielded area or a region with low natural radioactivity."
            
            RadiationLevel.NORMAL -> 
                "This reading is within the normal range of natural background radiation. " +
                "You're exposed to similar levels simply by existing on Earth."
            
            RadiationLevel.SLIGHTLY_ELEVATED -> 
                "This is slightly above average background but still considered safe. " +
                "Many everyday locations have readings like this."
            
            RadiationLevel.ELEVATED -> 
                "This is noticeably elevated compared to typical background. " +
                "While not immediately dangerous, you may want to identify the source."
            
            RadiationLevel.HIGH -> 
                "This is significantly higher than normal background radiation. " +
                "You should investigate the source and consider limiting your time here."
            
            RadiationLevel.VERY_HIGH -> 
                "This level of radiation is concerning. " +
                "Unless you're in a known radiation area (like a medical facility), you should leave."
            
            RadiationLevel.EXTREME -> 
                "This is an extremely dangerous level of radiation. " +
                "Leave the area immediately and report to authorities."
        }
        
        val locationContext = when (locationType) {
            LocationType.AIRPORT -> 
                "\n\nAirport security areas often have slightly elevated readings due to X-ray machines and cosmic ray contributions at higher elevations."
            LocationType.HOSPITAL -> 
                "\n\nHospitals use various radiation sources for medical imaging and treatment. Some areas are restricted for good reason."
            LocationType.GRANITE_BUILDING -> 
                "\n\nGranite naturally contains small amounts of uranium and thorium, leading to elevated readings in buildings with granite countertops or facades."
            LocationType.AIRPLANE -> 
                "\n\nAt cruising altitude, you're above much of the atmosphere's protective layer, so cosmic radiation is significantly higher. This is normal for air travel."
            LocationType.MOUNTAIN -> 
                "\n\nHigher altitudes mean less atmospheric shielding from cosmic rays. The increase is proportional to elevation."
            LocationType.BEACH -> 
                "\n\nSome beaches (especially with dark sand) contain monazite, a naturally radioactive mineral."
            LocationType.NUCLEAR_SITE -> 
                "\n\n⚠️ You're near a known nuclear facility. Elevated readings may be expected but should be monitored carefully."
            else -> ""
        }
        
        val isotopeContext = detectedIsotope?.let {
            val info = encyclopedia.getIsotopeInfo(it)
            "\n\n🔬 Detected: ${info?.fullName ?: it}\n${info?.commonSources?.firstOrNull() ?: ""}"
        } ?: ""
        
        return base + locationContext + isotopeContext
    }

    private fun identifyPossibleSources(
        doseRate: Float,
        locationType: LocationType?,
        detectedIsotope: String?
    ): List<PossibleSource> {
        val sources = mutableListOf<PossibleSource>()
        
        // Add location-based sources
        when (locationType) {
            LocationType.AIRPORT -> {
                sources.add(PossibleSource("X-ray scanners", "Security scanning equipment", 0.7f))
                sources.add(PossibleSource("Cosmic radiation", "Elevated due to altitude", 0.6f))
            }
            LocationType.HOSPITAL -> {
                sources.add(PossibleSource("Medical imaging", "CT, X-ray, nuclear medicine", 0.8f))
                sources.add(PossibleSource("Radiation therapy", "Cancer treatment areas", 0.5f))
            }
            LocationType.GRANITE_BUILDING -> {
                sources.add(PossibleSource("Natural granite", "Contains U-238, Th-232", 0.9f))
            }
            LocationType.AIRPLANE -> {
                sources.add(PossibleSource("Cosmic radiation", "At cruising altitude", 0.95f))
            }
            LocationType.MOUNTAIN -> {
                sources.add(PossibleSource("Cosmic radiation", "Less atmospheric shielding", 0.8f))
                sources.add(PossibleSource("Natural uranium", "Mountain rock deposits", 0.4f))
            }
            LocationType.BEACH -> {
                sources.add(PossibleSource("Monazite sand", "Naturally radioactive mineral", 0.6f))
            }
            LocationType.NUCLEAR_SITE -> {
                sources.add(PossibleSource("Nuclear facility", "Various fission products", 0.9f))
            }
            else -> {}
        }
        
        // Add isotope-based sources
        detectedIsotope?.let { isotope ->
            val info = encyclopedia.getIsotopeInfo(isotope)
            info?.commonSources?.forEach { source: String ->
                sources.add(PossibleSource(isotope, source, 0.8f))
            }
        }
        
        // Add general background sources
        if (doseRate < 0.3f) {
            sources.add(PossibleSource("Natural background", "Cosmic rays, radon, K-40 in body", 0.9f))
        }
        
        // Add dose-dependent sources
        if (doseRate > 2f && detectedIsotope == null) {
            sources.add(PossibleSource("Unknown radioactive material", "Consider professional survey", 0.5f))
        }
        
        return sources.sortedByDescending { it.likelihood }
    }

    private fun getRecommendations(level: RadiationLevel, locationType: LocationType?): List<String> {
        return when (level) {
            RadiationLevel.VERY_LOW, RadiationLevel.NORMAL -> listOf(
                "No action needed - this is completely safe",
                "Continue normal activities"
            )
            RadiationLevel.SLIGHTLY_ELEVATED -> listOf(
                "This level is still safe for extended periods",
                "If unexpected, consider identifying the source out of curiosity"
            )
            RadiationLevel.ELEVATED -> listOf(
                "Try to identify the radiation source",
                "Consider reducing time in this area if prolonged",
                "Note: Many natural and man-made sources can cause this level"
            )
            RadiationLevel.HIGH -> listOf(
                "⚠️ Limit your time in this area",
                "Identify and maintain distance from the source",
                "If unexpected, consider reporting to facility management"
            )
            RadiationLevel.VERY_HIGH -> listOf(
                "🚨 Leave the area unless you have a specific reason to stay",
                "Do not touch or handle potential sources",
                "Report to appropriate authorities if in a public area"
            )
            RadiationLevel.EXTREME -> listOf(
                "☢️ EVACUATE IMMEDIATELY",
                "Move away from the source as quickly as possible",
                "Call emergency services if this is unexpected",
                "Seek medical evaluation if exposed for any length of time"
            )
        }
    }

    private fun getFunFacts(doseRate: Float, locationType: LocationType?): List<String> {
        val facts = mutableListOf<String>()
        
        // Dose-based facts
        when {
            doseRate < 0.1f -> {
                facts.add("🏔️ Some underground salt mines have radiation this low!")
                facts.add("🛡️ You're well-shielded - cosmic rays have trouble reaching you here")
            }
            doseRate < 0.3f -> {
                facts.add("🍌 You'd need to eat ~100 bananas to match one hour at this level")
                facts.add("🌍 This is what most of Earth's surface experiences")
            }
            doseRate < 1f -> {
                facts.add("✈️ Similar to what pilots experience at cruising altitude")
                facts.add("🏛️ The US Capitol building has elevated readings from its granite")
            }
            doseRate < 5f -> {
                facts.add("🏥 Medical technicians may work in similar environments daily")
                facts.add("⚛️ Marie Curie's notebooks are still too radioactive to handle safely")
            }
            else -> {
                facts.add("☢️ The Chernobyl control room peaked at ~300 Sv/h during the disaster")
                facts.add("🚀 Astronauts on the ISS receive ~150-200 µSv/day")
            }
        }
        
        // Location-based facts
        when (locationType) {
            LocationType.AIRPLANE -> {
                facts.add("✈️ A transatlantic flight exposes you to ~40-100 µSv")
                facts.add("👨‍✈️ Pilots have one of the highest occupational radiation exposures")
            }
            LocationType.BEACH -> {
                facts.add("🏖️ Guarapari, Brazil has beaches with 175 µSv/h - 100x normal!")
            }
            LocationType.MOUNTAIN -> {
                facts.add("🏔️ Denver, Colorado has 2x the cosmic radiation of sea level")
            }
            else -> {}
        }
        
        return facts.shuffled().take(2)
    }

    private fun getComparisonText(doseRate: Float): String {
        val annualDose = doseRate * 24 * 365 / 1000  // mSv/year at this rate
        
        return when {
            annualDose < 1 -> "At this rate: %.1f mSv/year (well below 2.4 mSv natural average)".format(annualDose)
            annualDose < 2.4 -> "At this rate: %.1f mSv/year (below natural average)".format(annualDose)
            annualDose < 5 -> "At this rate: %.1f mSv/year (within safe public limit)".format(annualDose)
            annualDose < 20 -> "At this rate: %.1f mSv/year (within occupational limit)".format(annualDose)
            annualDose < 100 -> "At this rate: %.1f mSv/year (above occupational limits)".format(annualDose)
            else -> "At this rate: %.0f mSv/year (significantly above safety limits!)".format(annualDose)
        }
    }

    private fun calculateConfidence(
        doseRate: Float,
        locationType: LocationType?,
        detectedIsotope: String?
    ): Float {
        var confidence = 0.5f
        
        // Location context increases confidence
        if (locationType != null) confidence += 0.2f
        
        // Detected isotope increases confidence
        if (detectedIsotope != null) confidence += 0.25f
        
        // Very normal readings are high confidence
        if (doseRate in 0.05f..0.3f) confidence += 0.1f
        
        return confidence.coerceIn(0f, 1f)
    }

    // Data classes
    data class QuickIDResult(
        val level: RadiationLevel,
        val headline: String,
        val explanation: String,
        val possibleSources: List<PossibleSource>,
        val recommendations: List<String>,
        val funFacts: List<String>,
        val isotopeInfo: IsotopeEncyclopedia.IsotopeInfo?,
        val comparisonText: String,
        val safetyEmoji: String,
        val confidenceLevel: Float
    )

    data class PossibleSource(
        val name: String,
        val description: String,
        val likelihood: Float  // 0-1
    )

    enum class RadiationLevel(val emoji: String, val colorName: String) {
        VERY_LOW("✨", "cyan"),
        NORMAL("✓", "green"),
        SLIGHTLY_ELEVATED("ℹ️", "yellow"),
        ELEVATED("⚠️", "orange"),
        HIGH("⚠️", "orange"),
        VERY_HIGH("🚨", "red"),
        EXTREME("☢️", "red")
    }

    enum class LocationType(val displayName: String) {
        UNKNOWN("Unknown"),
        OUTDOORS("Outdoors"),
        INDOORS("Indoors"),
        AIRPORT("Airport"),
        HOSPITAL("Hospital/Medical"),
        GRANITE_BUILDING("Granite Building"),
        AIRPLANE("Airplane"),
        MOUNTAIN("Mountain/High Altitude"),
        BEACH("Beach"),
        BASEMENT("Basement"),
        NUCLEAR_SITE("Nuclear Facility Area"),
        INDUSTRIAL("Industrial Area"),
        LABORATORY("Laboratory")
    }
}
