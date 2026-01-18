package com.radiacode.ble

/**
 * Isotope Encyclopedia - Educational information about detected isotopes.
 * Provides half-life, common sources, health info, and decay chain data.
 */
object IsotopeEncyclopedia {

    /**
     * Complete information about an isotope
     */
    data class IsotopeInfo(
        val symbol: String,           // e.g., "Cs-137"
        val fullName: String,         // e.g., "Cesium-137"
        val atomicNumber: Int,
        val massNumber: Int,
        val halfLife: String,         // Human-readable, e.g., "30.17 years"
        val halfLifeSeconds: Double,  // For calculations
        val category: Category,
        val decayMode: String,        // e.g., "β⁻ decay"
        val gammaEnergies: List<Float>, // keV
        val description: String,
        val commonSources: List<String>,
        val healthInfo: String,
        val safetyGuidance: String,
        val decayChain: List<DecayStep>,
        val emoji: String,            // Visual identifier
        val colorHex: String          // For UI display
    )
    
    data class DecayStep(
        val parent: String,
        val daughter: String,
        val mode: String,
        val energy: String
    )
    
    enum class Category(val displayName: String, val colorHex: String) {
        NATURAL("Natural/Background", "69F0AE"),       // Green
        MEDICAL("Medical", "00E5FF"),                   // Cyan
        INDUSTRIAL("Industrial", "FFD740"),             // Amber
        FISSION("Fission Product", "FF5252"),           // Red
        COSMOGENIC("Cosmogenic", "E040FB")              // Magenta
    }
    
    /**
     * Get information about a specific isotope
     */
    fun getIsotopeInfo(symbol: String): IsotopeInfo? {
        return isotopeDatabase[symbol.uppercase().replace(" ", "-")]
    }
    
    /**
     * Get all isotopes in a category
     */
    fun getIsotopesByCategory(category: Category): List<IsotopeInfo> {
        return isotopeDatabase.values.filter { it.category == category }
    }
    
    /**
     * Search isotopes by keyword
     */
    fun searchIsotopes(query: String): List<IsotopeInfo> {
        val lowerQuery = query.lowercase()
        return isotopeDatabase.values.filter { isotope ->
            isotope.symbol.lowercase().contains(lowerQuery) ||
            isotope.fullName.lowercase().contains(lowerQuery) ||
            isotope.commonSources.any { it.lowercase().contains(lowerQuery) }
        }
    }
    
    // ========== Isotope Database ==========
    
    private val isotopeDatabase = mapOf(
        // Natural/Background Isotopes
        "K-40" to IsotopeInfo(
            symbol = "K-40",
            fullName = "Potassium-40",
            atomicNumber = 19,
            massNumber = 40,
            halfLife = "1.25 billion years",
            halfLifeSeconds = 3.94e16,
            category = Category.NATURAL,
            decayMode = "β⁻ (89.3%), EC/β⁺ (10.7%)",
            gammaEnergies = listOf(1460.8f),
            description = "A naturally occurring radioactive isotope of potassium. Present in all living things and many minerals. One of the primary sources of natural background radiation.",
            commonSources = listOf(
                "Bananas and other potassium-rich foods",
                "Human body (~4,400 Bq in average adult)",
                "Granite and volcanic rocks",
                "Fertilizers (potash)",
                "Salt substitutes"
            ),
            healthInfo = "K-40 is essential for life and cannot be avoided. The body maintains a constant level regardless of intake. Internal dose is approximately 0.17 mSv/year.",
            safetyGuidance = "No special precautions needed. This is a normal part of your body's radioactivity.",
            decayChain = listOf(
                DecayStep("K-40", "Ca-40", "β⁻ decay", "1.31 MeV"),
                DecayStep("K-40", "Ar-40", "EC", "1.46 MeV γ")
            ),
            emoji = "🍌",
            colorHex = "FFD740"
        ),
        
        "TH-232" to IsotopeInfo(
            symbol = "Th-232",
            fullName = "Thorium-232",
            atomicNumber = 90,
            massNumber = 232,
            halfLife = "14.05 billion years",
            halfLifeSeconds = 4.43e17,
            category = Category.NATURAL,
            decayMode = "α decay",
            gammaEnergies = listOf(238.6f, 583.2f, 911.2f, 2614.5f),
            description = "A primordial radioactive element found in soil and rocks. Head of the thorium decay series, producing radon-220 (thoron) as a decay product.",
            commonSources = listOf(
                "Soil and rocks (average 6 ppm)",
                "Granite and monazite sands",
                "Gas mantles (historical)",
                "Welding electrodes",
                "Optical glass and ceramics"
            ),
            healthInfo = "External exposure risk is low. Main concern is inhalation of dust containing thorium or its decay products (especially radon-220).",
            safetyGuidance = "Avoid creating or inhaling dust from thorium-containing materials. Well-ventilated areas minimize radon buildup.",
            decayChain = listOf(
                DecayStep("Th-232", "Ra-228", "α", "4.0 MeV"),
                DecayStep("Ra-228", "Ac-228", "β⁻", "0.046 MeV"),
                DecayStep("Ac-228", "Th-228", "β⁻", "2.1 MeV"),
                DecayStep("...", "Rn-220", "...", "thoron"),
                DecayStep("...", "Pb-208", "...", "stable")
            ),
            emoji = "🪨",
            colorHex = "8D6E63"
        ),
        
        "U-238" to IsotopeInfo(
            symbol = "U-238",
            fullName = "Uranium-238",
            atomicNumber = 92,
            massNumber = 238,
            halfLife = "4.47 billion years",
            halfLifeSeconds = 1.41e17,
            category = Category.NATURAL,
            decayMode = "α decay",
            gammaEnergies = listOf(49.6f, 63.3f, 92.4f, 92.8f),
            description = "The most common isotope of uranium (99.3% of natural uranium). Primordial element present since Earth's formation. Parent of the uranium decay series.",
            commonSources = listOf(
                "Soil (average 2-4 ppm)",
                "Granite and phosphate rocks",
                "Ocean water (3.3 ppb)",
                "Depleted uranium products",
                "Uranium glass (vaseline glass)"
            ),
            healthInfo = "Chemical toxicity is primary concern (heavy metal poisoning). Radiological hazard mainly from decay chain products, especially radon-222.",
            safetyGuidance = "Avoid ingestion. Radon ventilation important in basements. Handle uranium glass/ceramics normally.",
            decayChain = listOf(
                DecayStep("U-238", "Th-234", "α", "4.2 MeV"),
                DecayStep("Th-234", "Pa-234", "β⁻", "0.27 MeV"),
                DecayStep("...", "Ra-226", "...", "radium"),
                DecayStep("Ra-226", "Rn-222", "α", "radon"),
                DecayStep("...", "Pb-206", "...", "stable")
            ),
            emoji = "⚛️",
            colorHex = "4CAF50"
        ),
        
        "RA-226" to IsotopeInfo(
            symbol = "Ra-226",
            fullName = "Radium-226",
            atomicNumber = 88,
            massNumber = 226,
            halfLife = "1,600 years",
            halfLifeSeconds = 5.05e10,
            category = Category.NATURAL,
            decayMode = "α decay",
            gammaEnergies = listOf(186.2f, 262.3f, 295.2f, 351.9f, 609.3f),
            description = "A highly radioactive alkaline earth metal. Historically used in luminescent paints. Produces radon-222 gas as a decay product.",
            commonSources = listOf(
                "Uranium ore tailings",
                "Antique luminous watches/clocks",
                "Some mineral springs",
                "Phosphogypsum (fertilizer byproduct)",
                "Old medical equipment"
            ),
            healthInfo = "Bone-seeking element. Historical 'radium girls' cases showed dangers of ingestion. External exposure from intact sources is manageable.",
            safetyGuidance = "Do not open antique radium items. Maintain distance. Never ingest or inhale radium-containing materials.",
            decayChain = listOf(
                DecayStep("Ra-226", "Rn-222", "α", "4.78 MeV"),
                DecayStep("Rn-222", "Po-218", "α", "5.49 MeV"),
                DecayStep("...", "Pb-210", "...", "..."),
                DecayStep("Pb-210", "Bi-210", "β⁻", "..."),
                DecayStep("Bi-210", "Po-210", "β⁻", "..."),
                DecayStep("Po-210", "Pb-206", "α", "stable")
            ),
            emoji = "☢️",
            colorHex = "7CB342"
        ),
        
        // Medical Isotopes
        "TC-99M" to IsotopeInfo(
            symbol = "Tc-99m",
            fullName = "Technetium-99m",
            atomicNumber = 43,
            massNumber = 99,
            halfLife = "6.01 hours",
            halfLifeSeconds = 21636.0,
            category = Category.MEDICAL,
            decayMode = "Isomeric transition (γ)",
            gammaEnergies = listOf(140.5f),
            description = "The most widely used medical radioisotope. The 'm' stands for metastable. Used in over 30 million diagnostic procedures annually worldwide.",
            commonSources = listOf(
                "Nuclear medicine departments",
                "Medical imaging facilities",
                "Hospital nuclear pharmacies",
                "Recent medical scan patients"
            ),
            healthInfo = "Very short half-life and pure gamma emission make it ideal for imaging. Patient dose from typical scan: 1-10 mSv.",
            safetyGuidance = "Standard distance and time precautions around patients. Decays to negligible levels within 24 hours.",
            decayChain = listOf(
                DecayStep("Tc-99m", "Tc-99", "IT", "140 keV γ"),
                DecayStep("Tc-99", "Ru-99", "β⁻", "293 keV (211,000 years)")
            ),
            emoji = "🏥",
            colorHex = "00BCD4"
        ),
        
        "I-131" to IsotopeInfo(
            symbol = "I-131",
            fullName = "Iodine-131",
            atomicNumber = 53,
            massNumber = 131,
            halfLife = "8.02 days",
            halfLifeSeconds = 693100.0,
            category = Category.MEDICAL,
            decayMode = "β⁻ decay",
            gammaEnergies = listOf(364.5f, 637.0f, 284.3f, 80.2f),
            description = "Used for thyroid treatments and imaging. Also a significant fission product released in nuclear accidents (Chernobyl, Fukushima).",
            commonSources = listOf(
                "Thyroid cancer treatment patients",
                "Hyperthyroidism treatment",
                "Nuclear medicine departments",
                "Nuclear accident fallout (historical)"
            ),
            healthInfo = "Concentrates in thyroid gland. Therapeutic uses exploit this for cancer treatment. Fetal/child exposure particularly concerning.",
            safetyGuidance = "Treated patients may need isolation. Potassium iodide can block thyroid uptake in emergencies.",
            decayChain = listOf(
                DecayStep("I-131", "Xe-131", "β⁻", "606 keV max"),
                DecayStep("Xe-131", "stable", "-", "-")
            ),
            emoji = "🦋",  // Thyroid-shaped
            colorHex = "9C27B0"
        ),
        
        "F-18" to IsotopeInfo(
            symbol = "F-18",
            fullName = "Fluorine-18",
            atomicNumber = 9,
            massNumber = 18,
            halfLife = "109.8 minutes",
            halfLifeSeconds = 6588.0,
            category = Category.MEDICAL,
            decayMode = "β⁺ decay (97%), EC (3%)",
            gammaEnergies = listOf(511.0f), // Annihilation photons
            description = "Used in PET (Positron Emission Tomography) scans, most commonly as FDG (fluorodeoxyglucose). Detects cancer and other metabolically active conditions.",
            commonSources = listOf(
                "PET scan facilities",
                "Cyclotron production sites",
                "Recent PET scan patients",
                "Radiopharmacies"
            ),
            healthInfo = "Short half-life limits exposure duration. Typical PET scan dose: 5-10 mSv. Beta-plus decay produces 511 keV gamma pairs.",
            safetyGuidance = "Standard shielding for PET facilities. Patient precautions typically 2-6 hours post-injection.",
            decayChain = listOf(
                DecayStep("F-18", "O-18", "β⁺", "633 keV max"),
                DecayStep("e⁺ + e⁻", "2γ", "annihilation", "511 keV each")
            ),
            emoji = "🔬",
            colorHex = "03A9F4"
        ),
        
        // Industrial Isotopes
        "CO-60" to IsotopeInfo(
            symbol = "Co-60",
            fullName = "Cobalt-60",
            atomicNumber = 27,
            massNumber = 60,
            halfLife = "5.27 years",
            halfLifeSeconds = 1.66e8,
            category = Category.INDUSTRIAL,
            decayMode = "β⁻ decay",
            gammaEnergies = listOf(1173.2f, 1332.5f),
            description = "Widely used in industrial radiography, cancer treatment (Gamma Knife), and food irradiation. Very energetic gamma emitter.",
            commonSources = listOf(
                "Industrial radiography sources",
                "Radiation therapy equipment",
                "Food irradiation facilities",
                "Sterilization equipment",
                "Level gauges in industry"
            ),
            healthInfo = "High-energy gammas are deeply penetrating. Significant source can cause acute radiation syndrome. Several accidental exposure incidents historically.",
            safetyGuidance = "Sources must be properly shielded. Never approach unmarked metal objects. 10-foot rule for unknown sources.",
            decayChain = listOf(
                DecayStep("Co-60", "Ni-60*", "β⁻", "318 keV max"),
                DecayStep("Ni-60*", "Ni-60", "γ", "1173 + 1333 keV")
            ),
            emoji = "🏭",
            colorHex = "FF5722"
        ),
        
        "AM-241" to IsotopeInfo(
            symbol = "Am-241",
            fullName = "Americium-241",
            atomicNumber = 95,
            massNumber = 241,
            halfLife = "432.2 years",
            halfLifeSeconds = 1.36e10,
            category = Category.INDUSTRIAL,
            decayMode = "α decay",
            gammaEnergies = listOf(59.5f, 26.3f),
            description = "Found in most household smoke detectors. Alpha emitter with low-energy gamma. Most common transuranic element in everyday life.",
            commonSources = listOf(
                "Ionization smoke detectors",
                "Industrial moisture/density gauges",
                "Well-logging equipment",
                "Static eliminators (historical)"
            ),
            healthInfo = "Intact smoke detector poses negligible risk. Ingestion/inhalation of americium is serious due to alpha emission. Single detector contains ~1 μCi.",
            safetyGuidance = "Don't open smoke detectors. Dispose of old detectors properly. If source is damaged, avoid inhaling particles.",
            decayChain = listOf(
                DecayStep("Am-241", "Np-237", "α", "5.49 MeV"),
                DecayStep("Np-237", "Pa-233", "α", "2.14 million years"),
                DecayStep("...", "U-233", "...", "..."),
                DecayStep("...", "Bi-209", "...", "stable")
            ),
            emoji = "🔥",  // Smoke detector
            colorHex = "FF9800"
        ),
        
        "IR-192" to IsotopeInfo(
            symbol = "Ir-192",
            fullName = "Iridium-192",
            atomicNumber = 77,
            massNumber = 192,
            halfLife = "73.8 days",
            halfLifeSeconds = 6.38e6,
            category = Category.INDUSTRIAL,
            decayMode = "β⁻ (95.2%), EC (4.8%)",
            gammaEnergies = listOf(316.5f, 468.1f, 308.5f, 295.9f, 604.4f),
            description = "Primary industrial radiography source for pipeline and weld inspection. High specific activity makes it ideal for portable applications.",
            commonSources = listOf(
                "Pipeline inspection equipment",
                "Weld radiography devices",
                "Brachytherapy (cancer treatment)",
                "Industrial NDT equipment"
            ),
            healthInfo = "Multiple energetic gammas. Lost/orphan sources have caused fatalities (Goiânia-type incidents). Highly dangerous if unshielded.",
            safetyGuidance = "Never approach unmarked radiography equipment. Sources must be in shielded containers. Report suspicious metal cylinders.",
            decayChain = listOf(
                DecayStep("Ir-192", "Pt-192", "β⁻", "675 keV max"),
                DecayStep("Ir-192", "Os-192", "EC", "...")
            ),
            emoji = "🔧",
            colorHex = "607D8B"
        ),
        
        "BA-133" to IsotopeInfo(
            symbol = "Ba-133",
            fullName = "Barium-133",
            atomicNumber = 56,
            massNumber = 133,
            halfLife = "10.51 years",
            halfLifeSeconds = 3.31e8,
            category = Category.INDUSTRIAL,
            decayMode = "EC (electron capture)",
            gammaEnergies = listOf(356.0f, 81.0f, 276.4f, 302.9f, 383.9f),
            description = "Used for calibration of gamma spectrometers and as a reference source. Multiple gamma lines make it ideal for energy calibration.",
            commonSources = listOf(
                "Calibration sources",
                "Research laboratories",
                "Nuclear teaching sets",
                "Instrument calibration"
            ),
            healthInfo = "Sealed calibration sources pose minimal external risk. Primary hazard is if source integrity is compromised.",
            safetyGuidance = "Store in designated location. Check source integrity periodically. Standard sealed source handling.",
            decayChain = listOf(
                DecayStep("Ba-133", "Cs-133", "EC", "517 keV"),
                DecayStep("Cs-133", "stable", "-", "-")
            ),
            emoji = "📊",
            colorHex = "795548"
        ),
        
        "EU-152" to IsotopeInfo(
            symbol = "Eu-152",
            fullName = "Europium-152",
            atomicNumber = 63,
            massNumber = 152,
            halfLife = "13.52 years",
            halfLifeSeconds = 4.27e8,
            category = Category.INDUSTRIAL,
            decayMode = "EC (72.1%), β⁻ (27.9%)",
            gammaEnergies = listOf(121.8f, 344.3f, 1408.0f, 778.9f, 964.1f, 1112.1f),
            description = "Excellent calibration source with many gamma lines spanning wide energy range. Also used in nuclear activation analysis.",
            commonSources = listOf(
                "Calibration sources",
                "Research laboratories",
                "Activation analysis",
                "Educational sets"
            ),
            healthInfo = "Multiple gamma energies from low to high. Sealed sources are safe with normal handling.",
            safetyGuidance = "Standard sealed source protocols. Good for detector calibration due to wide energy coverage.",
            decayChain = listOf(
                DecayStep("Eu-152", "Sm-152", "EC", "..."),
                DecayStep("Eu-152", "Gd-152", "β⁻", "...")
            ),
            emoji = "🎓",
            colorHex = "9E9E9E"
        ),
        
        // Fission Products
        "CS-137" to IsotopeInfo(
            symbol = "Cs-137",
            fullName = "Cesium-137",
            atomicNumber = 55,
            massNumber = 137,
            halfLife = "30.17 years",
            halfLifeSeconds = 9.52e8,
            category = Category.FISSION,
            decayMode = "β⁻ decay",
            gammaEnergies = listOf(661.7f),
            description = "Major fission product from nuclear reactors and weapons. The characteristic 662 keV gamma is highly recognizable. Responsible for long-term contamination at Chernobyl/Fukushima.",
            commonSources = listOf(
                "Nuclear accident contamination",
                "Nuclear weapons fallout",
                "Industrial radiography (less common now)",
                "Medical teletherapy (legacy)",
                "Calibration sources"
            ),
            healthInfo = "Behaves like potassium in body (muscle-seeking). Long biological half-life (~70 days). Whole-body exposure from contamination.",
            safetyGuidance = "Prussian blue can reduce biological half-life if ingested. Avoid contaminated food/water. Sealed sources are manageable.",
            decayChain = listOf(
                DecayStep("Cs-137", "Ba-137m", "β⁻", "512 keV max"),
                DecayStep("Ba-137m", "Ba-137", "IT", "662 keV γ (94.6%)")
            ),
            emoji = "☢️",
            colorHex = "F44336"
        ),
        
        "CS-134" to IsotopeInfo(
            symbol = "Cs-134",
            fullName = "Cesium-134",
            atomicNumber = 55,
            massNumber = 134,
            halfLife = "2.07 years",
            halfLifeSeconds = 6.52e7,
            category = Category.FISSION,
            decayMode = "β⁻ decay",
            gammaEnergies = listOf(604.7f, 795.9f, 569.3f, 801.9f),
            description = "Fission product often seen alongside Cs-137. Ratio of Cs-134/Cs-137 can date nuclear contamination events.",
            commonSources = listOf(
                "Nuclear reactor operations",
                "Nuclear accident contamination",
                "Spent nuclear fuel"
            ),
            healthInfo = "Similar biological behavior to Cs-137. Shorter half-life means it decays faster from environment.",
            safetyGuidance = "Same precautions as Cs-137. Presence indicates relatively recent fission event.",
            decayChain = listOf(
                DecayStep("Cs-134", "Ba-134", "β⁻", "658 keV max"),
                DecayStep("Ba-134", "stable", "-", "-")
            ),
            emoji = "⚛️",
            colorHex = "E91E63"
        ),
        
        "NA-22" to IsotopeInfo(
            symbol = "Na-22",
            fullName = "Sodium-22",
            atomicNumber = 11,
            massNumber = 22,
            halfLife = "2.60 years",
            halfLifeSeconds = 8.21e7,
            category = Category.FISSION,
            decayMode = "β⁺ decay (90.3%), EC (9.7%)",
            gammaEnergies = listOf(1274.5f, 511.0f),
            description = "Positron emitter used for PET calibration. Also produced in nuclear reactions. The 511 keV annihilation peak is a signature.",
            commonSources = listOf(
                "PET calibration sources",
                "Research laboratories",
                "Cosmic ray spallation products",
                "Calibration sources"
            ),
            healthInfo = "Positron emission produces 511 keV annihilation photons. Sealed sources pose manageable external hazard.",
            safetyGuidance = "Standard sealed source handling. Used safely in many laboratories.",
            decayChain = listOf(
                DecayStep("Na-22", "Ne-22*", "β⁺/EC", "545 keV max"),
                DecayStep("Ne-22*", "Ne-22", "γ", "1275 keV"),
                DecayStep("e⁺ + e⁻", "2γ", "annihilation", "511 keV")
            ),
            emoji = "✨",
            colorHex = "FF9800"
        )
    )
    
    /**
     * Get all isotopes in the database
     */
    fun getAllIsotopes(): List<IsotopeInfo> = isotopeDatabase.values.toList()
    
    /**
     * Get quick facts for display
     */
    fun getQuickFacts(symbol: String): String? {
        val info = getIsotopeInfo(symbol) ?: return null
        return buildString {
            append("${info.emoji} ${info.fullName}\n")
            append("Half-life: ${info.halfLife}\n")
            append("Category: ${info.category.displayName}\n")
            append("Main γ: ${info.gammaEnergies.firstOrNull()?.let { String.format("%.1f keV", it) } ?: "N/A"}")
        }
    }
}
