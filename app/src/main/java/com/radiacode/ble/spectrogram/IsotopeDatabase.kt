package com.radiacode.ble.spectrogram

import android.graphics.Color

/**
 * Comprehensive isotope database with metadata for all 82 isotopes
 * supported by the Vega 2D identification model.
 * 
 * Information includes:
 * - Full chemical name
 * - Category (calibration, medical, natural, etc.)
 * - Primary gamma energy
 * - Half-life
 * - Decay chain membership
 */
object IsotopeDatabase {
    
    /**
     * Isotope category with display name and color
     */
    enum class Category(val displayName: String, val color: Int) {
        CALIBRATION("Calibration", Color.parseColor("#00E5FF")),      // Cyan
        MEDICAL("Medical", Color.parseColor("#E040FB")),              // Magenta
        INDUSTRIAL("Industrial", Color.parseColor("#FF9100")),        // Orange
        NATURAL("Natural", Color.parseColor("#69F0AE")),              // Green
        U238_CHAIN("U-238 Chain", Color.parseColor("#FFD600")),       // Yellow
        TH232_CHAIN("Th-232 Chain", Color.parseColor("#FFAB40")),     // Amber
        U235_CHAIN("U-235 Chain", Color.parseColor("#FF6E40")),       // Deep Orange
        REACTOR_FALLOUT("Fallout", Color.parseColor("#FF5252")),      // Red
        ACTIVATION("Activation", Color.parseColor("#448AFF")),        // Blue
        COSMOGENIC("Cosmogenic", Color.parseColor("#7C4DFF"))         // Purple
    }
    
    /**
     * Complete isotope information
     */
    data class IsotopeInfo(
        val name: String,
        val fullName: String,
        val category: Category,
        val primaryGammaKeV: Float,
        val halfLife: String,
        val decayChain: String? = null,
        val gammaLines: List<Pair<Float, Float>> = emptyList()  // (energy keV, branching ratio)
    )
    
    // Complete database of all 82 isotopes
    private val database: Map<String, IsotopeInfo> = mapOf(
        // ══════════════════════════════════════════════════════════════════
        // CALIBRATION SOURCES
        // ══════════════════════════════════════════════════════════════════
        "Am-241" to IsotopeInfo(
            name = "Am-241",
            fullName = "Americium-241",
            category = Category.CALIBRATION,
            primaryGammaKeV = 59.5f,
            halfLife = "432.2 y",
            gammaLines = listOf(59.5f to 0.359f)
        ),
        "Ba-133" to IsotopeInfo(
            name = "Ba-133",
            fullName = "Barium-133",
            category = Category.CALIBRATION,
            primaryGammaKeV = 356.0f,
            halfLife = "10.5 y",
            gammaLines = listOf(356.0f to 0.623f, 81.0f to 0.329f, 302.9f to 0.183f)
        ),
        "Bi-207" to IsotopeInfo(
            name = "Bi-207",
            fullName = "Bismuth-207",
            category = Category.CALIBRATION,
            primaryGammaKeV = 569.7f,
            halfLife = "31.6 y",
            gammaLines = listOf(569.7f to 0.978f, 1063.7f to 0.745f)
        ),
        "Co-57" to IsotopeInfo(
            name = "Co-57",
            fullName = "Cobalt-57",
            category = Category.CALIBRATION,
            primaryGammaKeV = 122.1f,
            halfLife = "271.8 d",
            gammaLines = listOf(122.1f to 0.856f, 136.5f to 0.107f)
        ),
        "Co-60" to IsotopeInfo(
            name = "Co-60",
            fullName = "Cobalt-60",
            category = Category.CALIBRATION,
            primaryGammaKeV = 1173.2f,
            halfLife = "5.27 y",
            gammaLines = listOf(1173.2f to 0.999f, 1332.5f to 0.9998f)
        ),
        "Cs-137" to IsotopeInfo(
            name = "Cs-137",
            fullName = "Cesium-137",
            category = Category.CALIBRATION,
            primaryGammaKeV = 661.7f,
            halfLife = "30.17 y",
            gammaLines = listOf(661.7f to 0.851f)
        ),
        "Eu-152" to IsotopeInfo(
            name = "Eu-152",
            fullName = "Europium-152",
            category = Category.CALIBRATION,
            primaryGammaKeV = 121.8f,
            halfLife = "13.5 y",
            gammaLines = listOf(121.8f to 0.284f, 344.3f to 0.265f, 1408.0f to 0.210f)
        ),
        "Eu-154" to IsotopeInfo(
            name = "Eu-154",
            fullName = "Europium-154",
            category = Category.CALIBRATION,
            primaryGammaKeV = 123.1f,
            halfLife = "8.59 y",
            gammaLines = listOf(123.1f to 0.406f, 1274.4f to 0.350f)
        ),
        "Ge-68" to IsotopeInfo(
            name = "Ge-68",
            fullName = "Germanium-68",
            category = Category.CALIBRATION,
            primaryGammaKeV = 511.0f,
            halfLife = "270.8 d",
            gammaLines = listOf(511.0f to 1.78f)  // Annihilation
        ),
        "Mn-54" to IsotopeInfo(
            name = "Mn-54",
            fullName = "Manganese-54",
            category = Category.CALIBRATION,
            primaryGammaKeV = 834.8f,
            halfLife = "312.2 d",
            gammaLines = listOf(834.8f to 0.9998f)
        ),
        "Na-22" to IsotopeInfo(
            name = "Na-22",
            fullName = "Sodium-22",
            category = Category.CALIBRATION,
            primaryGammaKeV = 1274.5f,
            halfLife = "2.60 y",
            gammaLines = listOf(511.0f to 1.798f, 1274.5f to 0.999f)
        ),
        "Sr-85" to IsotopeInfo(
            name = "Sr-85",
            fullName = "Strontium-85",
            category = Category.CALIBRATION,
            primaryGammaKeV = 514.0f,
            halfLife = "64.8 d",
            gammaLines = listOf(514.0f to 0.96f)
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // MEDICAL ISOTOPES
        // ══════════════════════════════════════════════════════════════════
        "Au-198" to IsotopeInfo(
            name = "Au-198",
            fullName = "Gold-198",
            category = Category.MEDICAL,
            primaryGammaKeV = 411.8f,
            halfLife = "2.69 d"
        ),
        "Cu-64" to IsotopeInfo(
            name = "Cu-64",
            fullName = "Copper-64",
            category = Category.MEDICAL,
            primaryGammaKeV = 1345.8f,
            halfLife = "12.7 h"
        ),
        "F-18" to IsotopeInfo(
            name = "F-18",
            fullName = "Fluorine-18 (FDG)",
            category = Category.MEDICAL,
            primaryGammaKeV = 511.0f,
            halfLife = "109.8 min",
            gammaLines = listOf(511.0f to 1.934f)
        ),
        "Ga-67" to IsotopeInfo(
            name = "Ga-67",
            fullName = "Gallium-67",
            category = Category.MEDICAL,
            primaryGammaKeV = 93.3f,
            halfLife = "3.26 d",
            gammaLines = listOf(93.3f to 0.388f, 184.6f to 0.212f, 300.2f to 0.166f)
        ),
        "Ga-68" to IsotopeInfo(
            name = "Ga-68",
            fullName = "Gallium-68",
            category = Category.MEDICAL,
            primaryGammaKeV = 511.0f,
            halfLife = "67.7 min",
            gammaLines = listOf(511.0f to 1.78f)
        ),
        "I-123" to IsotopeInfo(
            name = "I-123",
            fullName = "Iodine-123",
            category = Category.MEDICAL,
            primaryGammaKeV = 159.0f,
            halfLife = "13.2 h",
            gammaLines = listOf(159.0f to 0.833f)
        ),
        "I-125" to IsotopeInfo(
            name = "I-125",
            fullName = "Iodine-125",
            category = Category.MEDICAL,
            primaryGammaKeV = 35.5f,
            halfLife = "59.4 d"
        ),
        "I-131" to IsotopeInfo(
            name = "I-131",
            fullName = "Iodine-131",
            category = Category.MEDICAL,
            primaryGammaKeV = 364.5f,
            halfLife = "8.02 d",
            gammaLines = listOf(364.5f to 0.817f, 637.0f to 0.072f)
        ),
        "In-111" to IsotopeInfo(
            name = "In-111",
            fullName = "Indium-111",
            category = Category.MEDICAL,
            primaryGammaKeV = 171.3f,
            halfLife = "2.80 d",
            gammaLines = listOf(171.3f to 0.906f, 245.4f to 0.941f)
        ),
        "Lu-177" to IsotopeInfo(
            name = "Lu-177",
            fullName = "Lutetium-177",
            category = Category.MEDICAL,
            primaryGammaKeV = 208.4f,
            halfLife = "6.65 d"
        ),
        "Mo-99" to IsotopeInfo(
            name = "Mo-99",
            fullName = "Molybdenum-99",
            category = Category.MEDICAL,
            primaryGammaKeV = 140.5f,
            halfLife = "65.9 h",
            gammaLines = listOf(140.5f to 0.894f, 739.5f to 0.121f)
        ),
        "Tc-99m" to IsotopeInfo(
            name = "Tc-99m",
            fullName = "Technetium-99m",
            category = Category.MEDICAL,
            primaryGammaKeV = 140.5f,
            halfLife = "6.01 h",
            gammaLines = listOf(140.5f to 0.890f)
        ),
        "Tl-201" to IsotopeInfo(
            name = "Tl-201",
            fullName = "Thallium-201",
            category = Category.MEDICAL,
            primaryGammaKeV = 167.4f,
            halfLife = "3.04 d"
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // INDUSTRIAL SOURCES
        // ══════════════════════════════════════════════════════════════════
        "Cd-109" to IsotopeInfo(
            name = "Cd-109",
            fullName = "Cadmium-109",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 88.0f,
            halfLife = "462.6 d"
        ),
        "Hg-203" to IsotopeInfo(
            name = "Hg-203",
            fullName = "Mercury-203",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 279.2f,
            halfLife = "46.6 d"
        ),
        "Ir-192" to IsotopeInfo(
            name = "Ir-192",
            fullName = "Iridium-192",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 316.5f,
            halfLife = "73.8 d",
            gammaLines = listOf(316.5f to 0.829f, 468.1f to 0.478f, 604.4f to 0.082f)
        ),
        "Np-237" to IsotopeInfo(
            name = "Np-237",
            fullName = "Neptunium-237",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 86.5f,
            halfLife = "2.14 My"
        ),
        "Pu-239" to IsotopeInfo(
            name = "Pu-239",
            fullName = "Plutonium-239",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 413.7f,
            halfLife = "24,110 y"
        ),
        "Se-75" to IsotopeInfo(
            name = "Se-75",
            fullName = "Selenium-75",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 264.7f,
            halfLife = "119.8 d",
            gammaLines = listOf(264.7f to 0.589f, 279.5f to 0.250f)
        ),
        "Zn-65" to IsotopeInfo(
            name = "Zn-65",
            fullName = "Zinc-65",
            category = Category.INDUSTRIAL,
            primaryGammaKeV = 1115.5f,
            halfLife = "243.9 d"
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // NATURAL BACKGROUND
        // ══════════════════════════════════════════════════════════════════
        "K-40" to IsotopeInfo(
            name = "K-40",
            fullName = "Potassium-40",
            category = Category.NATURAL,
            primaryGammaKeV = 1460.8f,
            halfLife = "1.25 By",
            gammaLines = listOf(1460.8f to 0.107f)
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // URANIUM-238 DECAY CHAIN
        // ══════════════════════════════════════════════════════════════════
        "U-238" to IsotopeInfo(
            name = "U-238",
            fullName = "Uranium-238",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 49.6f,
            halfLife = "4.47 By",
            decayChain = "U-238"
        ),
        "Th-234" to IsotopeInfo(
            name = "Th-234",
            fullName = "Thorium-234",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 63.3f,
            halfLife = "24.1 d",
            decayChain = "U-238",
            gammaLines = listOf(63.3f to 0.038f, 92.4f to 0.027f)
        ),
        "Pa-234m" to IsotopeInfo(
            name = "Pa-234m",
            fullName = "Protactinium-234m",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 1001.0f,
            halfLife = "1.17 min",
            decayChain = "U-238"
        ),
        "Pa-233" to IsotopeInfo(
            name = "Pa-233",
            fullName = "Protactinium-233",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 311.9f,
            halfLife = "27.0 d",
            decayChain = "U-238"
        ),
        "Ra-226" to IsotopeInfo(
            name = "Ra-226",
            fullName = "Radium-226",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 186.2f,
            halfLife = "1600 y",
            decayChain = "U-238",
            gammaLines = listOf(186.2f to 0.036f)
        ),
        "Rn-222" to IsotopeInfo(
            name = "Rn-222",
            fullName = "Radon-222",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 0f,  // Alpha emitter
            halfLife = "3.82 d",
            decayChain = "U-238"
        ),
        "Pb-214" to IsotopeInfo(
            name = "Pb-214",
            fullName = "Lead-214",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 351.9f,
            halfLife = "26.8 min",
            decayChain = "U-238",
            gammaLines = listOf(351.9f to 0.371f, 295.2f to 0.192f, 242.0f to 0.074f)
        ),
        "Bi-214" to IsotopeInfo(
            name = "Bi-214",
            fullName = "Bismuth-214",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 609.3f,
            halfLife = "19.9 min",
            decayChain = "U-238",
            gammaLines = listOf(609.3f to 0.461f, 1120.3f to 0.150f, 1764.5f to 0.153f)
        ),
        "Pb-210" to IsotopeInfo(
            name = "Pb-210",
            fullName = "Lead-210",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 46.5f,
            halfLife = "22.3 y",
            decayChain = "U-238",
            gammaLines = listOf(46.5f to 0.042f)
        ),
        "Bi-210" to IsotopeInfo(
            name = "Bi-210",
            fullName = "Bismuth-210",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 0f,  // Beta emitter
            halfLife = "5.01 d",
            decayChain = "U-238"
        ),
        "Po-210" to IsotopeInfo(
            name = "Po-210",
            fullName = "Polonium-210",
            category = Category.U238_CHAIN,
            primaryGammaKeV = 803.1f,
            halfLife = "138.4 d",
            decayChain = "U-238"
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // THORIUM-232 DECAY CHAIN
        // ══════════════════════════════════════════════════════════════════
        "Th-232" to IsotopeInfo(
            name = "Th-232",
            fullName = "Thorium-232",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 0f,  // Weak gamma
            halfLife = "14.0 By",
            decayChain = "Th-232"
        ),
        "Ra-228" to IsotopeInfo(
            name = "Ra-228",
            fullName = "Radium-228",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 0f,
            halfLife = "5.75 y",
            decayChain = "Th-232"
        ),
        "Ac-228" to IsotopeInfo(
            name = "Ac-228",
            fullName = "Actinium-228",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 911.2f,
            halfLife = "6.15 h",
            decayChain = "Th-232",
            gammaLines = listOf(911.2f to 0.258f, 338.3f to 0.113f, 969.0f to 0.158f)
        ),
        "Th-228" to IsotopeInfo(
            name = "Th-228",
            fullName = "Thorium-228",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 84.4f,
            halfLife = "1.91 y",
            decayChain = "Th-232"
        ),
        "Ra-224" to IsotopeInfo(
            name = "Ra-224",
            fullName = "Radium-224",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 241.0f,
            halfLife = "3.66 d",
            decayChain = "Th-232"
        ),
        "Rn-220" to IsotopeInfo(
            name = "Rn-220",
            fullName = "Radon-220 (Thoron)",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 549.7f,
            halfLife = "55.6 s",
            decayChain = "Th-232"
        ),
        "Pb-212" to IsotopeInfo(
            name = "Pb-212",
            fullName = "Lead-212",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 238.6f,
            halfLife = "10.64 h",
            decayChain = "Th-232",
            gammaLines = listOf(238.6f to 0.433f)
        ),
        "Bi-212" to IsotopeInfo(
            name = "Bi-212",
            fullName = "Bismuth-212",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 727.3f,
            halfLife = "60.6 min",
            decayChain = "Th-232",
            gammaLines = listOf(727.3f to 0.067f)
        ),
        "Tl-208" to IsotopeInfo(
            name = "Tl-208",
            fullName = "Thallium-208",
            category = Category.TH232_CHAIN,
            primaryGammaKeV = 583.2f,
            halfLife = "3.05 min",
            decayChain = "Th-232",
            gammaLines = listOf(583.2f to 0.845f, 2614.5f to 0.359f)
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // URANIUM-235 DECAY CHAIN
        // ══════════════════════════════════════════════════════════════════
        "U-235" to IsotopeInfo(
            name = "U-235",
            fullName = "Uranium-235",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 185.7f,
            halfLife = "703.8 My",
            decayChain = "U-235"
        ),
        "Th-227" to IsotopeInfo(
            name = "Th-227",
            fullName = "Thorium-227",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 236.0f,
            halfLife = "18.7 d",
            decayChain = "U-235"
        ),
        "Pa-231" to IsotopeInfo(
            name = "Pa-231",
            fullName = "Protactinium-231",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 283.7f,
            halfLife = "32,760 y",
            decayChain = "U-235"
        ),
        "Ac-227" to IsotopeInfo(
            name = "Ac-227",
            fullName = "Actinium-227",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 236.0f,
            halfLife = "21.8 y",
            decayChain = "U-235"
        ),
        "Ac-225" to IsotopeInfo(
            name = "Ac-225",
            fullName = "Actinium-225",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 99.9f,
            halfLife = "9.92 d",
            decayChain = "U-235"
        ),
        "Ra-223" to IsotopeInfo(
            name = "Ra-223",
            fullName = "Radium-223",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 269.5f,
            halfLife = "11.4 d",
            decayChain = "U-235"
        ),
        "Rn-219" to IsotopeInfo(
            name = "Rn-219",
            fullName = "Radon-219",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 271.2f,
            halfLife = "3.96 s",
            decayChain = "U-235"
        ),
        "Pb-211" to IsotopeInfo(
            name = "Pb-211",
            fullName = "Lead-211",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 404.9f,
            halfLife = "36.1 min",
            decayChain = "U-235"
        ),
        "Bi-211" to IsotopeInfo(
            name = "Bi-211",
            fullName = "Bismuth-211",
            category = Category.U235_CHAIN,
            primaryGammaKeV = 351.1f,
            halfLife = "2.14 min",
            decayChain = "U-235"
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // REACTOR FALLOUT / FISSION PRODUCTS
        // ══════════════════════════════════════════════════════════════════
        "Ce-141" to IsotopeInfo(
            name = "Ce-141",
            fullName = "Cerium-141",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 145.4f,
            halfLife = "32.5 d"
        ),
        "Ce-144" to IsotopeInfo(
            name = "Ce-144",
            fullName = "Cerium-144",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 133.5f,
            halfLife = "284.9 d"
        ),
        "Cs-134" to IsotopeInfo(
            name = "Cs-134",
            fullName = "Cesium-134",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 604.7f,
            halfLife = "2.06 y",
            gammaLines = listOf(604.7f to 0.976f, 795.9f to 0.854f)
        ),
        "Eu-155" to IsotopeInfo(
            name = "Eu-155",
            fullName = "Europium-155",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 86.5f,
            halfLife = "4.76 y",
            gammaLines = listOf(86.5f to 0.307f, 105.3f to 0.211f)
        ),
        "Kr-85" to IsotopeInfo(
            name = "Kr-85",
            fullName = "Krypton-85",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 514.0f,
            halfLife = "10.76 y"
        ),
        "La-140" to IsotopeInfo(
            name = "La-140",
            fullName = "Lanthanum-140",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 1596.2f,
            halfLife = "1.68 d"
        ),
        "Nb-95" to IsotopeInfo(
            name = "Nb-95",
            fullName = "Niobium-95",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 765.8f,
            halfLife = "35.0 d"
        ),
        "Ru-103" to IsotopeInfo(
            name = "Ru-103",
            fullName = "Ruthenium-103",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 497.1f,
            halfLife = "39.3 d"
        ),
        "Ru-106" to IsotopeInfo(
            name = "Ru-106",
            fullName = "Ruthenium-106",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 511.9f,
            halfLife = "373.6 d",
            gammaLines = listOf(511.9f to 0.205f, 621.9f to 0.099f)
        ),
        "Sb-125" to IsotopeInfo(
            name = "Sb-125",
            fullName = "Antimony-125",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 427.9f,
            halfLife = "2.76 y"
        ),
        "Sr-90" to IsotopeInfo(
            name = "Sr-90",
            fullName = "Strontium-90",
            category = Category.REACTOR_FALLOUT,
            primaryGammaKeV = 0f,  // Beta emitter
            halfLife = "28.8 y"
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // ACTIVATION PRODUCTS
        // ══════════════════════════════════════════════════════════════════
        "Ag-110m" to IsotopeInfo(
            name = "Ag-110m",
            fullName = "Silver-110m",
            category = Category.ACTIVATION,
            primaryGammaKeV = 657.8f,
            halfLife = "249.8 d"
        ),
        "Ce-139" to IsotopeInfo(
            name = "Ce-139",
            fullName = "Cerium-139",
            category = Category.ACTIVATION,
            primaryGammaKeV = 165.9f,
            halfLife = "137.6 d"
        ),
        "Co-58" to IsotopeInfo(
            name = "Co-58",
            fullName = "Cobalt-58",
            category = Category.ACTIVATION,
            primaryGammaKeV = 810.8f,
            halfLife = "70.9 d"
        ),
        "Cr-51" to IsotopeInfo(
            name = "Cr-51",
            fullName = "Chromium-51",
            category = Category.ACTIVATION,
            primaryGammaKeV = 320.1f,
            halfLife = "27.7 d"
        ),
        "Fe-55" to IsotopeInfo(
            name = "Fe-55",
            fullName = "Iron-55",
            category = Category.ACTIVATION,
            primaryGammaKeV = 5.9f,  // X-ray
            halfLife = "2.74 y"
        ),
        "Fe-59" to IsotopeInfo(
            name = "Fe-59",
            fullName = "Iron-59",
            category = Category.ACTIVATION,
            primaryGammaKeV = 1099.3f,
            halfLife = "44.5 d"
        ),
        "Hf-175" to IsotopeInfo(
            name = "Hf-175",
            fullName = "Hafnium-175",
            category = Category.ACTIVATION,
            primaryGammaKeV = 343.4f,
            halfLife = "70.0 d"
        ),
        "Hf-181" to IsotopeInfo(
            name = "Hf-181",
            fullName = "Hafnium-181",
            category = Category.ACTIVATION,
            primaryGammaKeV = 482.2f,
            halfLife = "42.4 d"
        ),
        "Na-24" to IsotopeInfo(
            name = "Na-24",
            fullName = "Sodium-24",
            category = Category.ACTIVATION,
            primaryGammaKeV = 1368.6f,
            halfLife = "15.0 h",
            gammaLines = listOf(1368.6f to 1.0f, 2754.0f to 0.999f)
        ),
        "Rb-86" to IsotopeInfo(
            name = "Rb-86",
            fullName = "Rubidium-86",
            category = Category.ACTIVATION,
            primaryGammaKeV = 1076.6f,
            halfLife = "18.6 d"
        ),
        "Sb-124" to IsotopeInfo(
            name = "Sb-124",
            fullName = "Antimony-124",
            category = Category.ACTIVATION,
            primaryGammaKeV = 602.7f,
            halfLife = "60.2 d"
        ),
        "Sc-46" to IsotopeInfo(
            name = "Sc-46",
            fullName = "Scandium-46",
            category = Category.ACTIVATION,
            primaryGammaKeV = 889.3f,
            halfLife = "83.8 d"
        ),
        
        // ══════════════════════════════════════════════════════════════════
        // COSMOGENIC
        // ══════════════════════════════════════════════════════════════════
        "Be-7" to IsotopeInfo(
            name = "Be-7",
            fullName = "Beryllium-7",
            category = Category.COSMOGENIC,
            primaryGammaKeV = 477.6f,
            halfLife = "53.2 d",
            gammaLines = listOf(477.6f to 0.104f)
        ),
        "C-14" to IsotopeInfo(
            name = "C-14",
            fullName = "Carbon-14",
            category = Category.COSMOGENIC,
            primaryGammaKeV = 0f,  // Beta only
            halfLife = "5730 y"
        ),
        "H-3" to IsotopeInfo(
            name = "H-3",
            fullName = "Tritium",
            category = Category.COSMOGENIC,
            primaryGammaKeV = 0f,  // Beta only
            halfLife = "12.3 y"
        )
    )
    
    // Default info for unknown isotopes
    private val defaultInfo = IsotopeInfo(
        name = "Unknown",
        fullName = "Unknown Isotope",
        category = Category.NATURAL,
        primaryGammaKeV = 0f,
        halfLife = "N/A"
    )
    
    /**
     * Get isotope information by name
     */
    fun getInfo(name: String): IsotopeInfo {
        return database[name] ?: defaultInfo.copy(name = name, fullName = name)
    }
    
    /**
     * Get all isotopes in a specific category
     */
    fun getByCategory(category: Category): List<IsotopeInfo> {
        return database.values.filter { it.category == category }
    }
    
    /**
     * Get all isotopes in a specific decay chain
     */
    fun getChainMembers(chainName: String): List<String> {
        return database.values
            .filter { it.decayChain == chainName }
            .map { it.name }
    }
}
