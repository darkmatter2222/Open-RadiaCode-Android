package com.radiacode.ble

import kotlin.math.exp
import kotlin.math.max

/**
 * Library of common radioactive isotopes with their characteristic gamma energies.
 * Used for isotope identification from gamma spectra.
 * 
 * Energy values in keV are from standard nuclear data tables.
 * Peak windows are energy ± tolerance for ROI analysis.
 */
object IsotopeLibrary {
    
    /**
     * Represents a characteristic gamma emission line.
     */
    data class GammaLine(
        /** Energy in keV */
        val energyKeV: Float,
        /** Branching ratio / emission probability (0-1) */
        val intensity: Float,
        /** Whether this is a primary diagnostic peak */
        val isPrimary: Boolean = false
    )
    
    /**
     * Represents a radioactive isotope with its gamma signature.
     */
    data class Isotope(
        /** Isotope identifier (e.g., "Cs-137", "K-40") */
        val id: String,
        /** Display name */
        val name: String,
        /** Category for grouping */
        val category: Category,
        /** Characteristic gamma lines */
        val gammaLines: List<GammaLine>,
        /** Half-life in seconds (for display) */
        val halfLifeSeconds: Double? = null,
        /** Parent isotope if this is part of a decay chain */
        val parentChain: String? = null,
        /** Description for user display */
        val description: String = "",
        /** Whether enabled by default */
        val enabledByDefault: Boolean = true
    ) {
        /** Primary gamma line (highest intensity marked as primary, or highest intensity overall) */
        val primaryLine: GammaLine
            get() = gammaLines.firstOrNull { it.isPrimary } 
                ?: gammaLines.maxByOrNull { it.intensity } 
                ?: gammaLines.first()
    }
    
    enum class Category(val displayName: String) {
        NATURAL("Natural/Background"),
        MEDICAL("Medical"),
        INDUSTRIAL("Industrial"),
        FISSION("Fission Products"),
        CALIBRATION("Calibration Sources")
    }
    
    /**
     * All known isotopes in the library.
     * Starting with 15 common isotopes encountered in the wild.
     */
    val ALL_ISOTOPES: List<Isotope> = listOf(
        // ========== NATURAL/BACKGROUND ==========
        Isotope(
            id = "K-40",
            name = "Potassium-40",
            category = Category.NATURAL,
            gammaLines = listOf(
                GammaLine(1460.8f, 0.1067f, isPrimary = true)
            ),
            halfLifeSeconds = 4.0e16, // 1.25 billion years
            description = "Natural background. Found in food, soil, building materials.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "Th-232",
            name = "Thorium-232 Chain",
            category = Category.NATURAL,
            gammaLines = listOf(
                // Tl-208 (daughter) - most diagnostic
                GammaLine(2614.5f, 0.358f, isPrimary = true),
                GammaLine(583.2f, 0.304f, isPrimary = true),
                // Ac-228 (daughter)
                GammaLine(911.2f, 0.266f),
                GammaLine(969.0f, 0.163f),
                // Pb-212 (daughter)
                GammaLine(238.6f, 0.436f)
            ),
            halfLifeSeconds = 4.4e17, // 14 billion years
            description = "Natural thorium decay chain. Often in soil, rocks, ceramics.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "U-238",
            name = "Uranium-238 Chain",
            category = Category.NATURAL,
            gammaLines = listOf(
                // Bi-214 (daughter) - most diagnostic
                GammaLine(609.3f, 0.461f, isPrimary = true),
                GammaLine(1120.3f, 0.150f),
                GammaLine(1764.5f, 0.153f),
                // Pb-214 (daughter)
                GammaLine(295.2f, 0.192f),
                GammaLine(351.9f, 0.371f)
            ),
            halfLifeSeconds = 1.4e17, // 4.5 billion years
            description = "Natural uranium decay chain. Radon daughters (Bi-214, Pb-214).",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "Ra-226",
            name = "Radium-226",
            category = Category.NATURAL,
            gammaLines = listOf(
                GammaLine(186.2f, 0.036f, isPrimary = true),
                // Also shows U-238 chain daughters
                GammaLine(609.3f, 0.461f), // Bi-214
                GammaLine(351.9f, 0.371f)  // Pb-214
            ),
            halfLifeSeconds = 5.05e10, // 1600 years
            parentChain = "U-238",
            description = "Part of U-238 chain. Found in old luminous dials, medical sources.",
            enabledByDefault = true
        ),
        
        // ========== INDUSTRIAL ==========
        Isotope(
            id = "Cs-137",
            name = "Cesium-137",
            category = Category.INDUSTRIAL,
            gammaLines = listOf(
                GammaLine(661.7f, 0.851f, isPrimary = true)
            ),
            halfLifeSeconds = 9.47e8, // 30 years
            description = "Industrial/medical sources. Fission product. Nuclear accidents.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "Co-60",
            name = "Cobalt-60",
            category = Category.INDUSTRIAL,
            gammaLines = listOf(
                GammaLine(1173.2f, 0.999f, isPrimary = true),
                GammaLine(1332.5f, 0.9998f, isPrimary = true)
            ),
            halfLifeSeconds = 1.66e8, // 5.27 years
            description = "Industrial radiography, food irradiation, medical therapy.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "Am-241",
            name = "Americium-241",
            category = Category.INDUSTRIAL,
            gammaLines = listOf(
                GammaLine(59.5f, 0.359f, isPrimary = true)
            ),
            halfLifeSeconds = 1.36e10, // 432 years
            description = "Smoke detectors, industrial gauges. Low energy gamma.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "Ir-192",
            name = "Iridium-192",
            category = Category.INDUSTRIAL,
            gammaLines = listOf(
                GammaLine(316.5f, 0.828f, isPrimary = true),
                GammaLine(468.1f, 0.478f),
                GammaLine(308.5f, 0.300f),
                GammaLine(295.9f, 0.287f)
            ),
            halfLifeSeconds = 6.39e6, // 74 days
            description = "Industrial radiography. High activity source.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "Ba-133",
            name = "Barium-133",
            category = Category.INDUSTRIAL,
            gammaLines = listOf(
                GammaLine(356.0f, 0.621f, isPrimary = true),
                GammaLine(81.0f, 0.341f),
                GammaLine(302.9f, 0.183f),
                GammaLine(383.8f, 0.089f)
            ),
            halfLifeSeconds = 3.3e8, // 10.5 years
            description = "Calibration source. Well counter standards.",
            enabledByDefault = true
        ),
        
        // ========== MEDICAL ==========
        Isotope(
            id = "Tc-99m",
            name = "Technetium-99m",
            category = Category.MEDICAL,
            gammaLines = listOf(
                GammaLine(140.5f, 0.890f, isPrimary = true)
            ),
            halfLifeSeconds = 21672.0, // 6 hours
            description = "Most common medical isotope. Nuclear medicine imaging.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "I-131",
            name = "Iodine-131",
            category = Category.MEDICAL,
            gammaLines = listOf(
                GammaLine(364.5f, 0.817f, isPrimary = true),
                GammaLine(637.0f, 0.072f),
                GammaLine(284.3f, 0.061f)
            ),
            halfLifeSeconds = 6.95e5, // 8 days
            description = "Thyroid therapy/imaging. Fission product.",
            enabledByDefault = true
        ),
        
        Isotope(
            id = "F-18",
            name = "Fluorine-18 (FDG)",
            category = Category.MEDICAL,
            gammaLines = listOf(
                GammaLine(511.0f, 1.94f, isPrimary = true) // Annihilation
            ),
            halfLifeSeconds = 6584.0, // 110 minutes
            description = "PET imaging (FDG). Positron emitter - 511 keV annihilation.",
            enabledByDefault = true
        ),
        
        // ========== FISSION PRODUCTS ==========
        Isotope(
            id = "Cs-134",
            name = "Cesium-134",
            category = Category.FISSION,
            gammaLines = listOf(
                GammaLine(604.7f, 0.976f, isPrimary = true),
                GammaLine(795.9f, 0.855f, isPrimary = true),
                GammaLine(569.3f, 0.154f)
            ),
            halfLifeSeconds = 6.5e7, // 2.06 years
            description = "Fission product. Nuclear accidents (with Cs-137).",
            enabledByDefault = false // Less common
        ),
        
        // ========== CALIBRATION ==========
        Isotope(
            id = "Na-22",
            name = "Sodium-22",
            category = Category.CALIBRATION,
            gammaLines = listOf(
                GammaLine(1274.5f, 0.999f, isPrimary = true),
                GammaLine(511.0f, 1.80f) // Annihilation
            ),
            halfLifeSeconds = 8.21e7, // 2.6 years
            description = "Calibration source. Positron emitter.",
            enabledByDefault = false
        ),
        
        Isotope(
            id = "Eu-152",
            name = "Europium-152",
            category = Category.CALIBRATION,
            gammaLines = listOf(
                GammaLine(344.3f, 0.266f, isPrimary = true),
                GammaLine(121.8f, 0.286f),
                GammaLine(1408.0f, 0.210f),
                GammaLine(1112.1f, 0.136f),
                GammaLine(778.9f, 0.129f),
                GammaLine(964.0f, 0.146f)
            ),
            halfLifeSeconds = 4.27e8, // 13.5 years
            description = "Multi-line calibration source.",
            enabledByDefault = false
        )
    )
    
    /** Map for quick lookup by ID */
    val ISOTOPE_MAP: Map<String, Isotope> = ALL_ISOTOPES.associateBy { it.id }
    
    /** Default enabled isotopes */
    val DEFAULT_ENABLED: Set<String> = ALL_ISOTOPES.filter { it.enabledByDefault }.map { it.id }.toSet()
    
    /** Isotopes grouped by category */
    val BY_CATEGORY: Map<Category, List<Isotope>> = ALL_ISOTOPES.groupBy { it.category }
    
    /**
     * Get isotope by ID, or null if not found.
     */
    fun get(id: String): Isotope? = ISOTOPE_MAP[id]
    
    /**
     * Get all isotopes in a category.
     */
    fun getByCategory(category: Category): List<Isotope> = BY_CATEGORY[category] ?: emptyList()
}
