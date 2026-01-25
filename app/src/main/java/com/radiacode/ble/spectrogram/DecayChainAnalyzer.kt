package com.radiacode.ble.spectrogram

/**
 * Decay Chain Analyzer
 * 
 * Analyzes detected isotopes to identify decay chain patterns and infer
 * parent isotopes from detected daughter products.
 * 
 * Based on the three major natural decay chains:
 * - Uranium-238 series
 * - Thorium-232 series
 * - Uranium-235 series (Actinium series)
 * 
 * And recognizes common signatures:
 * - Reactor fallout (Cs-137 + Cs-134)
 * - NORM materials (K-40 + Ra-226 + Th-232)
 * - Radon progeny (Pb-214 + Bi-214)
 */
object DecayChainAnalyzer {

    /**
     * Result of decay chain analysis
     */
    data class ChainResult(
        val chainName: String,
        val inferredParent: String?,
        val chainMembers: List<String>,
        val detectedMembers: Set<String>,
        val confidence: Float,
        val explanation: String
    )
    
    /**
     * Chain signature for pattern matching
     */
    private data class ChainSignature(
        val name: String,
        val displayName: String,
        val requiredIsotopes: Set<String>,
        val optionalIsotopes: Set<String>,
        val inferredParent: String?,
        val allMembers: List<String>,
        val explanationTemplate: String
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CHAIN DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val chainSignatures = listOf(
        // Radon-222 progeny (atmospheric radon)
        ChainSignature(
            name = "Rn-222 Progeny",
            displayName = "Radon-222 Daughters",
            requiredIsotopes = setOf("Pb-214", "Bi-214"),
            optionalIsotopes = setOf("Pb-210"),
            inferredParent = "Rn-222 (Radon Gas)",
            allMembers = listOf("Rn-222", "Pb-214", "Bi-214", "Pb-210"),
            explanationTemplate = "Pb-214 and Bi-214 are short-lived daughters of Rn-222 (radon gas). " +
                    "This signature indicates atmospheric radon, commonly found in indoor environments " +
                    "and areas with uranium-bearing geology."
        ),
        
        // Full U-238 decay chain in equilibrium
        ChainSignature(
            name = "U-238 Chain",
            displayName = "Uranium-238 Decay Chain",
            requiredIsotopes = setOf("Ra-226", "Pb-214", "Bi-214"),
            optionalIsotopes = setOf("U-238", "Th-234", "Pb-210"),
            inferredParent = "U-238 (Uranium)",
            allMembers = listOf("U-238", "Th-234", "Ra-226", "Rn-222", "Pb-214", "Bi-214", "Pb-210"),
            explanationTemplate = "Detection of Ra-226 with its progeny (Pb-214, Bi-214) indicates " +
                    "uranium-bearing material in secular equilibrium. The entire decay chain is present, " +
                    "suggesting geological material, ore, or NORM contamination."
        ),
        
        // Thorium-232 decay chain
        ChainSignature(
            name = "Th-232 Chain",
            displayName = "Thorium-232 Decay Chain",
            requiredIsotopes = setOf("Ac-228", "Pb-212", "Bi-212"),
            optionalIsotopes = setOf("Th-232", "Tl-208", "Ra-224"),
            inferredParent = "Th-232 (Thorium)",
            allMembers = listOf("Th-232", "Ac-228", "Ra-224", "Rn-220", "Pb-212", "Bi-212", "Tl-208"),
            explanationTemplate = "The Ac-228, Pb-212, Bi-212 signature indicates thorium-bearing material. " +
                    "Tl-208's distinctive 2614 keV gamma line is often visible. Common sources include " +
                    "monazite sand, gas mantles, and certain ceramics."
        ),
        
        // Thoron (Rn-220) daughters only
        ChainSignature(
            name = "Rn-220 Progeny",
            displayName = "Thoron Daughters",
            requiredIsotopes = setOf("Pb-212", "Bi-212"),
            optionalIsotopes = setOf("Tl-208"),
            inferredParent = "Rn-220 (Thoron Gas)",
            allMembers = listOf("Rn-220", "Pb-212", "Bi-212", "Tl-208"),
            explanationTemplate = "Pb-212 and Bi-212 without their parent Ac-228 suggests airborne thoron " +
                    "(Rn-220) daughters. Thoron has a short half-life (55 s), so its progeny deposit " +
                    "near the source."
        ),
        
        // Reactor fallout signature
        ChainSignature(
            name = "Reactor Fallout",
            displayName = "Reactor Fallout Signature",
            requiredIsotopes = setOf("Cs-137", "Cs-134"),
            optionalIsotopes = setOf("I-131", "Ru-106", "Ce-144"),
            inferredParent = null,
            allMembers = listOf("Cs-137", "Cs-134", "I-131", "Ru-106", "Sr-90"),
            explanationTemplate = "The Cs-137/Cs-134 combination is the fingerprint of reactor-origin " +
                    "material. Cs-134 is ONLY produced in reactors, so its presence alongside Cs-137 " +
                    "confirms nuclear reactor or accident origin (not weapons fallout)."
        ),
        
        // NORM (Naturally Occurring Radioactive Material)
        ChainSignature(
            name = "NORM",
            displayName = "NORM Material",
            requiredIsotopes = setOf("K-40"),
            optionalIsotopes = setOf("Ra-226", "Th-232", "U-238", "Pb-214", "Bi-214", "Ac-228"),
            inferredParent = null,
            allMembers = listOf("K-40", "Ra-226", "Th-232", "U-238"),
            explanationTemplate = "Multiple naturally-occurring isotopes detected. K-40 is present in all " +
                    "potassium-bearing materials. Combined with radium and/or thorium daughters, this " +
                    "indicates rocks, soil, building materials, or mineral samples."
        ),
        
        // U-235 chain (less common)
        ChainSignature(
            name = "U-235 Chain",
            displayName = "Actinium Series (U-235)",
            requiredIsotopes = setOf("Pa-231", "Ac-227"),
            optionalIsotopes = setOf("U-235", "Ra-223", "Bi-211"),
            inferredParent = "U-235 (Uranium-235)",
            allMembers = listOf("U-235", "Pa-231", "Ac-227", "Ra-223", "Rn-219", "Bi-211"),
            explanationTemplate = "Detection of Pa-231 and/or Ac-227 indicates the U-235 decay chain. " +
                    "U-235 is less abundant than U-238 (0.7% natural abundance), so this chain is " +
                    "less commonly observed unless uranium is enriched."
        ),
        
        // Medical isotope signature
        ChainSignature(
            name = "Medical Source",
            displayName = "Medical Isotope Signature",
            requiredIsotopes = setOf("Tc-99m"),
            optionalIsotopes = setOf("Mo-99", "I-131", "F-18", "Ga-67"),
            inferredParent = null,
            allMembers = listOf("Tc-99m", "Mo-99", "I-131", "F-18", "I-123", "Ga-67", "In-111"),
            explanationTemplate = "Detection of short-lived medical isotopes indicates recent nuclear " +
                    "medicine procedures or proximity to a medical facility. Tc-99m (6h half-life) is " +
                    "the most widely used diagnostic isotope."
        )
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze detected isotopes for decay chain patterns
     */
    fun analyze(detectedIsotopes: Set<String>): List<ChainResult> {
        if (detectedIsotopes.isEmpty()) return emptyList()
        
        val results = mutableListOf<ChainResult>()
        
        for (signature in chainSignatures) {
            val matchedRequired = signature.requiredIsotopes.intersect(detectedIsotopes)
            val matchedOptional = signature.optionalIsotopes.intersect(detectedIsotopes)
            
            // Check if enough required isotopes are present
            val requiredRatio = matchedRequired.size.toFloat() / signature.requiredIsotopes.size
            
            if (requiredRatio >= 0.5f) {  // At least half of required isotopes
                // Calculate confidence
                val totalPossible = signature.requiredIsotopes.size + signature.optionalIsotopes.size
                val totalMatched = matchedRequired.size + matchedOptional.size
                val confidence = (requiredRatio * 0.7f) + (totalMatched.toFloat() / totalPossible * 0.3f)
                
                results.add(ChainResult(
                    chainName = signature.displayName,
                    inferredParent = if (requiredRatio >= 0.8f) signature.inferredParent else null,
                    chainMembers = signature.allMembers,
                    detectedMembers = matchedRequired + matchedOptional,
                    confidence = confidence.coerceIn(0f, 1f),
                    explanation = signature.explanationTemplate
                ))
            }
        }
        
        // Sort by confidence and remove duplicates/overlapping chains
        return results
            .sortedByDescending { it.confidence }
            .take(3)  // Return top 3 matches
    }
    
    /**
     * Get simplified decay chain for a specific parent
     */
    fun getDecayChain(parent: String): List<String> {
        return when (parent) {
            "U-238" -> listOf("U-238", "Th-234", "Pa-234m", "Ra-226", "Rn-222", "Pb-214", "Bi-214", "Pb-210", "Po-210", "Pb-206")
            "Th-232" -> listOf("Th-232", "Ra-228", "Ac-228", "Th-228", "Ra-224", "Rn-220", "Pb-212", "Bi-212", "Tl-208", "Pb-208")
            "U-235" -> listOf("U-235", "Th-231", "Pa-231", "Ac-227", "Th-227", "Ra-223", "Rn-219", "Pb-211", "Bi-211", "Pb-207")
            else -> emptyList()
        }
    }
    
    /**
     * Check if a specific isotope is part of a decay chain
     */
    fun isChainMember(isotope: String): Boolean {
        return chainSignatures.any { 
            it.requiredIsotopes.contains(isotope) || 
            it.optionalIsotopes.contains(isotope) ||
            it.allMembers.contains(isotope)
        }
    }
    
    /**
     * Get the parent chain for an isotope
     */
    fun getParentChain(isotope: String): String? {
        return IsotopeDatabase.getInfo(isotope).decayChain
    }
    
    /**
     * Infer possible parent isotopes from detected daughters
     */
    fun inferParents(detectedIsotopes: Set<String>): List<Pair<String, Float>> {
        val parents = mutableListOf<Pair<String, Float>>()
        
        // Check for Rn-222 (radon) from its daughters
        if (detectedIsotopes.contains("Pb-214") && detectedIsotopes.contains("Bi-214")) {
            parents.add("Rn-222" to 0.9f)
        }
        
        // Check for Ra-226 from its presence or daughters
        if (detectedIsotopes.contains("Ra-226") || 
            (detectedIsotopes.contains("Pb-214") && detectedIsotopes.contains("Bi-214"))) {
            if (!detectedIsotopes.contains("U-238")) {
                parents.add("Ra-226" to 0.7f)
            }
        }
        
        // Check for U-238 from multiple chain members
        val u238Members = setOf("U-238", "Th-234", "Ra-226", "Pb-214", "Bi-214", "Pb-210")
        val u238Matches = u238Members.intersect(detectedIsotopes)
        if (u238Matches.size >= 3) {
            parents.add("U-238" to 0.8f)
        }
        
        // Check for Th-232 from chain members
        val th232Members = setOf("Th-232", "Ac-228", "Pb-212", "Bi-212", "Tl-208")
        val th232Matches = th232Members.intersect(detectedIsotopes)
        if (th232Matches.size >= 2) {
            parents.add("Th-232" to 0.8f)
        }
        
        // Check for Rn-220 (thoron) from daughters
        if (detectedIsotopes.contains("Pb-212") && detectedIsotopes.contains("Bi-212") &&
            !detectedIsotopes.contains("Ac-228")) {
            parents.add("Rn-220" to 0.7f)
        }
        
        return parents.sortedByDescending { it.second }
    }
}
