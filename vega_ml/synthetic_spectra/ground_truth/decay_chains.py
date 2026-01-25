"""
Decay Chain Definitions

Defines radioactive decay chains and their relationships, including:
- U-238 decay chain (Uranium series)
- Th-232 decay chain (Thorium series)
- U-235 decay chain (Actinium series)

Also includes chain signatures - groups of isotopes that commonly
appear together and indicate parent isotopes.
"""

from dataclasses import dataclass, field
from typing import List, Dict, Set, Optional, Tuple
from .isotope_data import ISOTOPE_DATABASE, Isotope


@dataclass
class DecayChainMember:
    """A member of a decay chain with branching ratio."""
    isotope_name: str
    branching_ratio: float = 1.0  # Fraction of decays following this path
    decay_mode: str = ""


@dataclass
class DecayChain:
    """Complete decay chain definition."""
    name: str
    parent: str
    members: List[DecayChainMember]
    description: str = ""
    
    def get_member_names(self) -> List[str]:
        """Get list of all member isotope names."""
        return [m.isotope_name for m in self.members]
    
    def get_gamma_emitters(self) -> List[str]:
        """Get members that have significant gamma emissions."""
        emitters = []
        for member in self.members:
            iso = ISOTOPE_DATABASE.get(member.isotope_name)
            if iso and len(iso.gamma_lines) > 0:
                # Check if any line has significant intensity
                if any(line.intensity > 0.01 for line in iso.gamma_lines):
                    emitters.append(member.isotope_name)
        return emitters


@dataclass
class ChainSignature:
    """
    Signature pattern of isotopes that indicate presence of a parent.
    
    When these daughter isotopes appear together in a spectrum,
    it strongly indicates the presence of the parent isotope
    (even if parent has weak/no gamma emissions).
    """
    name: str
    parent_chain: str  # Name of the decay chain
    inferred_parent: str  # Parent isotope that is indicated
    required_daughters: Set[str]  # Must see all of these
    optional_daughters: Set[str] = field(default_factory=set)  # May also see
    description: str = ""


# =============================================================================
# DECAY CHAINS
# =============================================================================

DECAY_CHAINS: Dict[str, DecayChain] = {}

# U-238 DECAY CHAIN (Uranium Series)
# U-238 -> Th-234 -> Pa-234m -> U-234 -> Th-230 -> Ra-226 -> Rn-222 ->
# Po-218 -> Pb-214 -> Bi-214 -> Po-214 -> Pb-210 -> Bi-210 -> Po-210 -> Pb-206

DECAY_CHAINS["U-238"] = DecayChain(
    name="U-238 Decay Chain (Uranium Series)",
    parent="U-238",
    description="14 step decay chain ending at stable Pb-206",
    members=[
        DecayChainMember("U-238", decay_mode="alpha"),
        DecayChainMember("Th-234", decay_mode="beta-"),
        DecayChainMember("Pa-234m", branching_ratio=0.998, decay_mode="beta-"),
        DecayChainMember("U-234", decay_mode="alpha"),
        DecayChainMember("Th-230", decay_mode="alpha"),
        DecayChainMember("Ra-226", decay_mode="alpha"),
        DecayChainMember("Rn-222", decay_mode="alpha"),
        DecayChainMember("Po-218", decay_mode="alpha"),
        DecayChainMember("Pb-214", decay_mode="beta-"),
        DecayChainMember("Bi-214", branching_ratio=0.9998, decay_mode="beta-"),
        DecayChainMember("Po-214", decay_mode="alpha"),
        DecayChainMember("Pb-210", decay_mode="beta-"),
        DecayChainMember("Bi-210", decay_mode="beta-"),
        DecayChainMember("Po-210", decay_mode="alpha"),
    ]
)

# TH-232 DECAY CHAIN (Thorium Series)
# Th-232 -> Ra-228 -> Ac-228 -> Th-228 -> Ra-224 -> Rn-220 ->
# Po-216 -> Pb-212 -> Bi-212 -> (Tl-208 or Po-212) -> Pb-208

DECAY_CHAINS["Th-232"] = DecayChain(
    name="Th-232 Decay Chain (Thorium Series)",
    parent="Th-232",
    description="10+ step decay chain ending at stable Pb-208",
    members=[
        DecayChainMember("Th-232", decay_mode="alpha"),
        DecayChainMember("Ra-228", decay_mode="beta-"),
        DecayChainMember("Ac-228", decay_mode="beta-"),
        DecayChainMember("Th-228", decay_mode="alpha"),
        DecayChainMember("Ra-224", decay_mode="alpha"),
        DecayChainMember("Rn-220", decay_mode="alpha"),
        DecayChainMember("Po-216", decay_mode="alpha"),
        DecayChainMember("Pb-212", decay_mode="beta-"),
        DecayChainMember("Bi-212", decay_mode="beta-/alpha"),
        DecayChainMember("Tl-208", branching_ratio=0.3594, decay_mode="beta-"),
        DecayChainMember("Po-212", branching_ratio=0.6406, decay_mode="alpha"),
    ]
)

# U-235 DECAY CHAIN (Actinium Series)
# U-235 -> Th-231 -> Pa-231 -> Ac-227 -> (complex branching) -> Pb-207

DECAY_CHAINS["U-235"] = DecayChain(
    name="U-235 Decay Chain (Actinium Series)",
    parent="U-235",
    description="11+ step decay chain ending at stable Pb-207",
    members=[
        DecayChainMember("U-235", decay_mode="alpha"),
        DecayChainMember("Th-231", decay_mode="beta-"),
        DecayChainMember("Pa-231", decay_mode="alpha"),
        DecayChainMember("Ac-227", decay_mode="beta-/alpha"),
        DecayChainMember("Pb-211", decay_mode="beta-"),
        DecayChainMember("Bi-211", decay_mode="alpha"),
        DecayChainMember("Tl-207", decay_mode="beta-"),
    ]
)

# Cs-137 -> Ba-137m (simple 2-step)
DECAY_CHAINS["Cs-137"] = DecayChain(
    name="Cs-137 Decay",
    parent="Cs-137",
    description="Cs-137 beta decay to Ba-137m metastable state",
    members=[
        DecayChainMember("Cs-137", decay_mode="beta-"),
        DecayChainMember("Ba-137m", decay_mode="IT"),
    ]
)


# =============================================================================
# CHAIN SIGNATURES
# =============================================================================

CHAIN_SIGNATURES: Dict[str, ChainSignature] = {}

# Radon-222 progeny (from U-238 chain via Ra-226)
# Seeing Pb-214 + Bi-214 together indicates radon presence
CHAIN_SIGNATURES["Rn-222_progeny"] = ChainSignature(
    name="Radon-222 Progeny",
    parent_chain="U-238",
    inferred_parent="Rn-222",
    required_daughters={"Pb-214", "Bi-214"},
    optional_daughters={"Po-214"},
    description="Pb-214 + Bi-214 indicates airborne Rn-222 (radon) daughters"
)

# Extended U-238 chain indicator
CHAIN_SIGNATURES["Ra-226_equilibrium"] = ChainSignature(
    name="Ra-226 Secular Equilibrium",
    parent_chain="U-238",
    inferred_parent="Ra-226",
    required_daughters={"Pb-214", "Bi-214"},
    optional_daughters={"Rn-222", "Po-214", "Pb-210"},
    description="Indicates Ra-226 or U-238 in secular equilibrium"
)

# Thoron progeny (from Th-232 chain)
# Seeing Pb-212 + Bi-212 + Tl-208 indicates thoron/thorium
CHAIN_SIGNATURES["Rn-220_progeny"] = ChainSignature(
    name="Thoron (Rn-220) Progeny",
    parent_chain="Th-232",
    inferred_parent="Rn-220",
    required_daughters={"Pb-212", "Bi-212"},
    optional_daughters={"Tl-208", "Po-212"},
    description="Pb-212 + Bi-212 indicates Rn-220 (thoron) daughters"
)

# Th-232 chain indicator (Ac-228 is key)
CHAIN_SIGNATURES["Th-232_equilibrium"] = ChainSignature(
    name="Th-232 Secular Equilibrium",
    parent_chain="Th-232",
    inferred_parent="Th-232",
    required_daughters={"Ac-228", "Pb-212", "Tl-208"},
    optional_daughters={"Bi-212", "Ra-224"},
    description="Ac-228 + Pb-212 + Tl-208 indicates Th-232 chain in equilibrium"
)

# U-235 presence (direct gamma)
CHAIN_SIGNATURES["U-235_direct"] = ChainSignature(
    name="U-235 Direct",
    parent_chain="U-235",
    inferred_parent="U-235",
    required_daughters={"U-235"},  # U-235 has direct 185.7 keV line
    optional_daughters={"Th-231", "Pa-231"},
    description="U-235 directly visible via 185.7 keV line"
)


# =============================================================================
# HELPER FUNCTIONS
# =============================================================================

def get_decay_chain(name: str) -> Optional[DecayChain]:
    """Get a decay chain by parent isotope name."""
    return DECAY_CHAINS.get(name)


def get_chain_daughters(parent: str, include_parent: bool = True) -> List[str]:
    """
    Get all daughter isotopes in a decay chain.
    
    Args:
        parent: Parent isotope name (e.g., "U-238")
        include_parent: Whether to include the parent in the list
    
    Returns:
        List of isotope names in the chain
    """
    chain = DECAY_CHAINS.get(parent)
    if chain is None:
        return [parent] if include_parent else []
    
    daughters = chain.get_member_names()
    if not include_parent and daughters and daughters[0] == parent:
        daughters = daughters[1:]
    return daughters


def infer_parent_from_daughters(
    detected_isotopes: Set[str]
) -> List[Tuple[str, ChainSignature, float]]:
    """
    Given a set of detected isotopes, infer possible parent isotopes.
    
    Args:
        detected_isotopes: Set of isotope names detected in spectrum
    
    Returns:
        List of (parent_name, signature, confidence) tuples
        Confidence is fraction of required daughters detected (1.0 = all)
    """
    results = []
    
    for sig_name, signature in CHAIN_SIGNATURES.items():
        required_found = detected_isotopes & signature.required_daughters
        if len(required_found) > 0:
            confidence = len(required_found) / len(signature.required_daughters)
            optional_found = detected_isotopes & signature.optional_daughters
            # Boost confidence slightly if optional daughters also found
            if len(signature.optional_daughters) > 0:
                bonus = 0.1 * len(optional_found) / len(signature.optional_daughters)
                confidence = min(1.0, confidence + bonus)
            
            results.append((signature.inferred_parent, signature, confidence))
    
    # Sort by confidence (highest first)
    results.sort(key=lambda x: x[2], reverse=True)
    return results


def get_equilibrium_ratios(chain_name: str) -> Dict[str, float]:
    """
    Get secular equilibrium activity ratios for a decay chain.
    
    In secular equilibrium, all daughter activities equal the parent activity.
    This returns relative activity fractions (all 1.0 for secular equilibrium).
    
    For non-equilibrium, this can be modified to return time-dependent ratios.
    """
    chain = DECAY_CHAINS.get(chain_name)
    if chain is None:
        return {}
    
    # In secular equilibrium, all activities are equal
    return {m.isotope_name: 1.0 for m in chain.members}


def get_visible_chain_gammas(
    chain_name: str,
    min_intensity: float = 0.01
) -> Dict[str, List[Tuple[float, float]]]:
    """
    Get all visible gamma lines from a decay chain.
    
    Args:
        chain_name: Name of the decay chain parent
        min_intensity: Minimum emission intensity to include
    
    Returns:
        Dict mapping isotope name to list of (energy_keV, intensity) tuples
    """
    chain = DECAY_CHAINS.get(chain_name)
    if chain is None:
        return {}
    
    result = {}
    for member in chain.members:
        iso = ISOTOPE_DATABASE.get(member.isotope_name)
        if iso:
            lines = [
                (line.energy_kev, line.intensity * member.branching_ratio)
                for line in iso.gamma_lines
                if line.intensity >= min_intensity
            ]
            if lines:
                result[member.isotope_name] = lines
    
    return result
