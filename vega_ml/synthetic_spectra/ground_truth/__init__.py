"""
Ground Truth Module

Contains isotope data, decay chains, and chain signatures for
synthetic spectra generation.
"""

from .isotope_data import (
    ISOTOPE_DATABASE,
    Isotope,
    GammaLine,
    IsotopeCategory,
    get_isotope,
    get_all_isotopes,
    get_isotope_names,
    get_isotopes_by_category,
    get_isotopes_with_gamma_in_range,
    SECOND, MINUTE, HOUR, DAY, YEAR, STABLE
)

from .decay_chains import (
    DECAY_CHAINS,
    CHAIN_SIGNATURES,
    DecayChain,
    ChainSignature,
    get_decay_chain,
    get_chain_daughters,
    infer_parent_from_daughters,
)
