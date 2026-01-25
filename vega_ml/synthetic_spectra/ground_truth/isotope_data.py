"""
Isotope Ground Truth Database

Contains gamma emission data for ~100 commonly encountered isotopes including:
- Natural background / primordial / cosmogenic
- U-238, Th-232, U-235 decay chain daughters
- Calibration/check sources and industrial isotopes
- Medical isotopes
- Reactor/fallout isotopes
- Activation products

Each isotope entry contains:
- Isotope identifier (e.g., "Cs-137")
- Half-life
- Primary gamma lines with energies (keV) and emission probabilities (%)
- Category/source type

Data sourced from ENSDF (Evaluated Nuclear Structure Data File) via NNDC.
"""

from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple
from enum import Enum


class IsotopeCategory(Enum):
    """Categories for isotope sources."""
    NATURAL_BACKGROUND = "natural_background"
    PRIMORDIAL = "primordial"
    COSMOGENIC = "cosmogenic"
    U238_CHAIN = "u238_chain"
    TH232_CHAIN = "th232_chain"
    U235_CHAIN = "u235_chain"
    CALIBRATION = "calibration"
    INDUSTRIAL = "industrial"
    MEDICAL = "medical"
    REACTOR_FALLOUT = "reactor_fallout"
    ACTIVATION = "activation"


@dataclass
class GammaLine:
    """A single gamma emission line."""
    energy_kev: float  # Energy in keV
    intensity: float  # Emission probability as fraction (0-1)
    uncertainty_kev: float = 0.0  # Energy uncertainty
    uncertainty_intensity: float = 0.0  # Intensity uncertainty


@dataclass
class Isotope:
    """Complete isotope data with gamma emissions."""
    name: str  # e.g., "Cs-137"
    atomic_number: int
    mass_number: int
    half_life_seconds: float  # Half-life in seconds
    gamma_lines: List[GammaLine]
    category: IsotopeCategory
    parent: Optional[str] = None  # Parent isotope in decay chain
    daughters: List[str] = field(default_factory=list)
    decay_mode: str = "beta-"  # Primary decay mode
    notes: str = ""
    
    @property
    def symbol(self) -> str:
        """Get element symbol from name."""
        return self.name.split("-")[0]
    
    @property
    def full_name(self) -> str:
        """Get full isotope identifier."""
        return f"{self.symbol}-{self.mass_number}"


# Time constants for half-life calculations
SECOND = 1.0
MINUTE = 60.0
HOUR = 3600.0
DAY = 86400.0
YEAR = 365.25 * DAY
STABLE = float('inf')


# =============================================================================
# ISOTOPE DATABASE
# =============================================================================

ISOTOPE_DATABASE: Dict[str, Isotope] = {}

def _add_isotope(isotope: Isotope):
    """Helper to add isotope to database."""
    ISOTOPE_DATABASE[isotope.name] = isotope


# -----------------------------------------------------------------------------
# NATURAL BACKGROUND / PRIMORDIAL / COSMOGENIC
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="K-40",
    atomic_number=19,
    mass_number=40,
    half_life_seconds=1.248e9 * YEAR,
    category=IsotopeCategory.PRIMORDIAL,
    decay_mode="beta-/EC",
    gamma_lines=[
        GammaLine(energy_kev=1460.83, intensity=0.1066),  # Primary gamma
    ],
    notes="Abundant in soil, rocks, food (bananas), building materials"
))

_add_isotope(Isotope(
    name="Be-7",
    atomic_number=4,
    mass_number=7,
    half_life_seconds=53.22 * DAY,
    category=IsotopeCategory.COSMOGENIC,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=477.6, intensity=0.1044),
    ],
    notes="Cosmogenic, produced in atmosphere"
))

_add_isotope(Isotope(
    name="C-14",
    atomic_number=6,
    mass_number=14,
    half_life_seconds=5730 * YEAR,
    category=IsotopeCategory.COSMOGENIC,
    decay_mode="beta-",
    gamma_lines=[],  # Pure beta emitter, no gamma
    notes="Cosmogenic, pure beta emitter (no direct gamma)"
))

_add_isotope(Isotope(
    name="Na-22",
    atomic_number=11,
    mass_number=22,
    half_life_seconds=2.6018 * YEAR,
    category=IsotopeCategory.COSMOGENIC,
    decay_mode="beta+/EC",
    gamma_lines=[
        GammaLine(energy_kev=1274.53, intensity=0.9994),
        GammaLine(energy_kev=511.0, intensity=1.798),  # Annihilation (2x 90%)
    ],
    notes="Cosmogenic, also common check source"
))

# -----------------------------------------------------------------------------
# U-238 DECAY CHAIN
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="U-238",
    atomic_number=92,
    mass_number=238,
    half_life_seconds=4.468e9 * YEAR,
    category=IsotopeCategory.PRIMORDIAL,
    decay_mode="alpha",
    daughters=["Th-234"],
    gamma_lines=[
        GammaLine(energy_kev=49.55, intensity=0.000064),  # Weak gamma
    ],
    notes="Parent of U-238 chain, mostly alpha emitter"
))

_add_isotope(Isotope(
    name="Th-234",
    atomic_number=90,
    mass_number=234,
    half_life_seconds=24.10 * DAY,
    category=IsotopeCategory.U238_CHAIN,
    parent="U-238",
    daughters=["Pa-234m"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=63.29, intensity=0.0484),
        GammaLine(energy_kev=92.38, intensity=0.0274),
        GammaLine(energy_kev=92.80, intensity=0.0271),
    ],
))

_add_isotope(Isotope(
    name="Pa-234m",
    atomic_number=91,
    mass_number=234,
    half_life_seconds=1.17 * MINUTE,
    category=IsotopeCategory.U238_CHAIN,
    parent="Th-234",
    daughters=["U-234"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=766.36, intensity=0.00294),
        GammaLine(energy_kev=1001.03, intensity=0.00842),
    ],
))

_add_isotope(Isotope(
    name="U-234",
    atomic_number=92,
    mass_number=234,
    half_life_seconds=2.455e5 * YEAR,
    category=IsotopeCategory.U238_CHAIN,
    parent="Pa-234m",
    daughters=["Th-230"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=53.20, intensity=0.00123),
    ],
))

_add_isotope(Isotope(
    name="Th-230",
    atomic_number=90,
    mass_number=230,
    half_life_seconds=7.538e4 * YEAR,
    category=IsotopeCategory.U238_CHAIN,
    parent="U-234",
    daughters=["Ra-226"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=67.67, intensity=0.00377),
        GammaLine(energy_kev=143.87, intensity=0.00055),
    ],
))

_add_isotope(Isotope(
    name="Ra-226",
    atomic_number=88,
    mass_number=226,
    half_life_seconds=1600 * YEAR,
    category=IsotopeCategory.U238_CHAIN,
    parent="Th-230",
    daughters=["Rn-222"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=186.21, intensity=0.0359),
    ],
    notes="Important marker for U-238 chain"
))

_add_isotope(Isotope(
    name="Rn-222",
    atomic_number=86,
    mass_number=222,
    half_life_seconds=3.8235 * DAY,
    category=IsotopeCategory.U238_CHAIN,
    parent="Ra-226",
    daughters=["Po-218"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=510.0, intensity=0.00076),
    ],
    notes="Radon gas, major indoor radiation source"
))

_add_isotope(Isotope(
    name="Po-218",
    atomic_number=84,
    mass_number=218,
    half_life_seconds=3.098 * MINUTE,
    category=IsotopeCategory.U238_CHAIN,
    parent="Rn-222",
    daughters=["Pb-214"],
    decay_mode="alpha",
    gamma_lines=[],  # Essentially no gamma
))

_add_isotope(Isotope(
    name="Pb-214",
    atomic_number=82,
    mass_number=214,
    half_life_seconds=26.8 * MINUTE,
    category=IsotopeCategory.U238_CHAIN,
    parent="Po-218",
    daughters=["Bi-214"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=241.98, intensity=0.0743),
        GammaLine(energy_kev=295.22, intensity=0.1842),
        GammaLine(energy_kev=351.93, intensity=0.3560),
    ],
    notes="Key radon daughter indicator"
))

_add_isotope(Isotope(
    name="Bi-214",
    atomic_number=83,
    mass_number=214,
    half_life_seconds=19.9 * MINUTE,
    category=IsotopeCategory.U238_CHAIN,
    parent="Pb-214",
    daughters=["Po-214"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=609.31, intensity=0.4549),
        GammaLine(energy_kev=768.36, intensity=0.0489),
        GammaLine(energy_kev=1120.29, intensity=0.1492),
        GammaLine(energy_kev=1238.11, intensity=0.0579),
        GammaLine(energy_kev=1377.67, intensity=0.0400),
        GammaLine(energy_kev=1764.49, intensity=0.1531),
        GammaLine(energy_kev=2204.21, intensity=0.0508),
    ],
    notes="Key radon daughter indicator with many gamma lines"
))

_add_isotope(Isotope(
    name="Po-214",
    atomic_number=84,
    mass_number=214,
    half_life_seconds=164.3e-6,  # 164 microseconds
    category=IsotopeCategory.U238_CHAIN,
    parent="Bi-214",
    daughters=["Pb-210"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=799.7, intensity=0.000104),
    ],
))

_add_isotope(Isotope(
    name="Pb-210",
    atomic_number=82,
    mass_number=210,
    half_life_seconds=22.2 * YEAR,
    category=IsotopeCategory.U238_CHAIN,
    parent="Po-214",
    daughters=["Bi-210"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=46.54, intensity=0.0425),
    ],
))

_add_isotope(Isotope(
    name="Bi-210",
    atomic_number=83,
    mass_number=210,
    half_life_seconds=5.013 * DAY,
    category=IsotopeCategory.U238_CHAIN,
    parent="Pb-210",
    daughters=["Po-210"],
    decay_mode="beta-",
    gamma_lines=[],  # Pure beta emitter
))

_add_isotope(Isotope(
    name="Po-210",
    atomic_number=84,
    mass_number=210,
    half_life_seconds=138.376 * DAY,
    category=IsotopeCategory.U238_CHAIN,
    parent="Bi-210",
    daughters=["Pb-206"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=803.1, intensity=0.0000122),
    ],
    notes="End of U-238 chain before stable Pb-206"
))

# -----------------------------------------------------------------------------
# TH-232 DECAY CHAIN
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="Th-232",
    atomic_number=90,
    mass_number=232,
    half_life_seconds=1.405e10 * YEAR,
    category=IsotopeCategory.PRIMORDIAL,
    daughters=["Ra-228"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=63.81, intensity=0.000263),
    ],
    notes="Parent of Th-232 chain"
))

_add_isotope(Isotope(
    name="Ra-228",
    atomic_number=88,
    mass_number=228,
    half_life_seconds=5.75 * YEAR,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Th-232",
    daughters=["Ac-228"],
    decay_mode="beta-",
    gamma_lines=[],  # Pure beta emitter
))

_add_isotope(Isotope(
    name="Ac-228",
    atomic_number=89,
    mass_number=228,
    half_life_seconds=6.15 * HOUR,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Ra-228",
    daughters=["Th-228"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=129.07, intensity=0.0242),
        GammaLine(energy_kev=338.32, intensity=0.1127),
        GammaLine(energy_kev=463.00, intensity=0.0440),
        GammaLine(energy_kev=794.95, intensity=0.0425),
        GammaLine(energy_kev=911.20, intensity=0.2580),
        GammaLine(energy_kev=968.97, intensity=0.1580),
        GammaLine(energy_kev=1588.19, intensity=0.0324),
    ],
    notes="Strong gamma emitter in Th-232 chain"
))

_add_isotope(Isotope(
    name="Th-228",
    atomic_number=90,
    mass_number=228,
    half_life_seconds=1.9116 * YEAR,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Ac-228",
    daughters=["Ra-224"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=84.37, intensity=0.0122),
        GammaLine(energy_kev=215.98, intensity=0.00247),
    ],
))

_add_isotope(Isotope(
    name="Ra-224",
    atomic_number=88,
    mass_number=224,
    half_life_seconds=3.66 * DAY,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Th-228",
    daughters=["Rn-220"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=240.99, intensity=0.0410),
    ],
))

_add_isotope(Isotope(
    name="Rn-220",
    atomic_number=86,
    mass_number=220,
    half_life_seconds=55.6,  # 55.6 seconds
    category=IsotopeCategory.TH232_CHAIN,
    parent="Ra-224",
    daughters=["Po-216"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=549.73, intensity=0.00114),
    ],
    notes="Thoron gas"
))

_add_isotope(Isotope(
    name="Po-216",
    atomic_number=84,
    mass_number=216,
    half_life_seconds=0.145,  # 145 milliseconds
    category=IsotopeCategory.TH232_CHAIN,
    parent="Rn-220",
    daughters=["Pb-212"],
    decay_mode="alpha",
    gamma_lines=[],  # No significant gamma
))

_add_isotope(Isotope(
    name="Pb-212",
    atomic_number=82,
    mass_number=212,
    half_life_seconds=10.64 * HOUR,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Po-216",
    daughters=["Bi-212"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=238.63, intensity=0.436),
        GammaLine(energy_kev=300.09, intensity=0.0319),
    ],
    notes="Key thoron daughter indicator"
))

_add_isotope(Isotope(
    name="Bi-212",
    atomic_number=83,
    mass_number=212,
    half_life_seconds=60.55 * MINUTE,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Pb-212",
    daughters=["Tl-208", "Po-212"],  # Branches
    decay_mode="beta-/alpha",
    gamma_lines=[
        GammaLine(energy_kev=727.33, intensity=0.0658),
        GammaLine(energy_kev=785.37, intensity=0.0111),
        GammaLine(energy_kev=1620.50, intensity=0.0149),
    ],
))

_add_isotope(Isotope(
    name="Tl-208",
    atomic_number=81,
    mass_number=208,
    half_life_seconds=3.053 * MINUTE,
    category=IsotopeCategory.TH232_CHAIN,
    parent="Bi-212",
    daughters=["Pb-208"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=277.37, intensity=0.0664),
        GammaLine(energy_kev=510.77, intensity=0.225),
        GammaLine(energy_kev=583.19, intensity=0.8450),  # Signature line
        GammaLine(energy_kev=860.56, intensity=0.1265),
        GammaLine(energy_kev=2614.51, intensity=0.9979),  # Highest energy common gamma
    ],
    notes="Key thoron indicator with 2614 keV line"
))

_add_isotope(Isotope(
    name="Po-212",
    atomic_number=84,
    mass_number=212,
    half_life_seconds=299e-9,  # 299 nanoseconds
    category=IsotopeCategory.TH232_CHAIN,
    parent="Bi-212",
    daughters=["Pb-208"],
    decay_mode="alpha",
    gamma_lines=[],  # No gamma, pure alpha
))

# -----------------------------------------------------------------------------
# U-235 DECAY CHAIN
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="U-235",
    atomic_number=92,
    mass_number=235,
    half_life_seconds=7.04e8 * YEAR,
    category=IsotopeCategory.PRIMORDIAL,
    daughters=["Th-231"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=143.76, intensity=0.1096),
        GammaLine(energy_kev=163.33, intensity=0.0508),
        GammaLine(energy_kev=185.72, intensity=0.5720),  # Primary line
        GammaLine(energy_kev=205.31, intensity=0.0503),
    ],
    notes="Fissile uranium isotope"
))

_add_isotope(Isotope(
    name="Th-231",
    atomic_number=90,
    mass_number=231,
    half_life_seconds=25.52 * HOUR,
    category=IsotopeCategory.U235_CHAIN,
    parent="U-235",
    daughters=["Pa-231"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=25.64, intensity=0.145),
        GammaLine(energy_kev=84.21, intensity=0.066),
    ],
))

_add_isotope(Isotope(
    name="Pa-231",
    atomic_number=91,
    mass_number=231,
    half_life_seconds=3.276e4 * YEAR,
    category=IsotopeCategory.U235_CHAIN,
    parent="Th-231",
    daughters=["Ac-227"],
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=27.36, intensity=0.093),
        GammaLine(energy_kev=283.67, intensity=0.0177),
        GammaLine(energy_kev=300.07, intensity=0.0234),
        GammaLine(energy_kev=302.67, intensity=0.0227),
    ],
))

_add_isotope(Isotope(
    name="Ac-227",
    atomic_number=89,
    mass_number=227,
    half_life_seconds=21.772 * YEAR,
    category=IsotopeCategory.U235_CHAIN,
    parent="Pa-231",
    daughters=["Th-227", "Fr-223"],
    decay_mode="beta-/alpha",
    gamma_lines=[],  # Very weak gamma
))

_add_isotope(Isotope(
    name="Pb-211",
    atomic_number=82,
    mass_number=211,
    half_life_seconds=36.1 * MINUTE,
    category=IsotopeCategory.U235_CHAIN,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=404.85, intensity=0.0376),
        GammaLine(energy_kev=427.09, intensity=0.0180),
        GammaLine(energy_kev=832.01, intensity=0.0351),
    ],
))

_add_isotope(Isotope(
    name="Bi-211",
    atomic_number=83,
    mass_number=211,
    half_life_seconds=2.14 * MINUTE,
    category=IsotopeCategory.U235_CHAIN,
    parent="Pb-211",
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=351.06, intensity=0.1295),
    ],
))

_add_isotope(Isotope(
    name="Tl-207",
    atomic_number=81,
    mass_number=207,
    half_life_seconds=4.77 * MINUTE,
    category=IsotopeCategory.U235_CHAIN,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=897.80, intensity=0.00261),
    ],
))

# -----------------------------------------------------------------------------
# CALIBRATION / CHECK SOURCES
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="Am-241",
    atomic_number=95,
    mass_number=241,
    half_life_seconds=432.2 * YEAR,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=26.34, intensity=0.024),
        GammaLine(energy_kev=59.54, intensity=0.3592),  # Primary calibration line
    ],
    notes="Common smoke detector source and calibration standard"
))

_add_isotope(Isotope(
    name="Cs-137",
    atomic_number=55,
    mass_number=137,
    half_life_seconds=30.08 * YEAR,
    category=IsotopeCategory.CALIBRATION,
    daughters=["Ba-137m"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=661.66, intensity=0.8499),  # Via Ba-137m
    ],
    notes="Primary calibration standard, also fallout isotope"
))

_add_isotope(Isotope(
    name="Ba-137m",
    atomic_number=56,
    mass_number=137,
    half_life_seconds=2.552 * MINUTE,
    category=IsotopeCategory.CALIBRATION,
    parent="Cs-137",
    decay_mode="IT",  # Isomeric transition
    gamma_lines=[
        GammaLine(energy_kev=661.66, intensity=0.8999),
    ],
    notes="Metastable state from Cs-137 decay"
))

_add_isotope(Isotope(
    name="Co-60",
    atomic_number=27,
    mass_number=60,
    half_life_seconds=5.2714 * YEAR,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=1173.23, intensity=0.9985),
        GammaLine(energy_kev=1332.49, intensity=0.9998),
    ],
    notes="Industrial radiography, calibration source"
))

_add_isotope(Isotope(
    name="Ba-133",
    atomic_number=56,
    mass_number=133,
    half_life_seconds=10.551 * YEAR,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=53.16, intensity=0.0214),
        GammaLine(energy_kev=79.61, intensity=0.0265),
        GammaLine(energy_kev=80.99, intensity=0.329),
        GammaLine(energy_kev=276.40, intensity=0.0716),
        GammaLine(energy_kev=302.85, intensity=0.1834),
        GammaLine(energy_kev=356.01, intensity=0.6205),
        GammaLine(energy_kev=383.85, intensity=0.0894),
    ],
    notes="Multi-line calibration source"
))

_add_isotope(Isotope(
    name="Cd-109",
    atomic_number=48,
    mass_number=109,
    half_life_seconds=461.4 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=88.03, intensity=0.0364),
    ],
))

_add_isotope(Isotope(
    name="Eu-152",
    atomic_number=63,
    mass_number=152,
    half_life_seconds=13.537 * YEAR,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC/beta-",
    gamma_lines=[
        GammaLine(energy_kev=121.78, intensity=0.2837),
        GammaLine(energy_kev=244.70, intensity=0.0753),
        GammaLine(energy_kev=344.28, intensity=0.2658),
        GammaLine(energy_kev=411.12, intensity=0.0224),
        GammaLine(energy_kev=443.96, intensity=0.0312),
        GammaLine(energy_kev=778.90, intensity=0.1297),
        GammaLine(energy_kev=867.38, intensity=0.0423),
        GammaLine(energy_kev=964.08, intensity=0.1463),
        GammaLine(energy_kev=1085.87, intensity=0.1013),
        GammaLine(energy_kev=1112.07, intensity=0.1354),
        GammaLine(energy_kev=1408.01, intensity=0.2085),
    ],
    notes="Multi-line calibration standard spanning wide energy range"
))

_add_isotope(Isotope(
    name="Eu-154",
    atomic_number=63,
    mass_number=154,
    half_life_seconds=8.593 * YEAR,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=123.07, intensity=0.4040),
        GammaLine(energy_kev=247.93, intensity=0.0689),
        GammaLine(energy_kev=591.76, intensity=0.0495),
        GammaLine(energy_kev=723.30, intensity=0.2005),
        GammaLine(energy_kev=756.80, intensity=0.0453),
        GammaLine(energy_kev=873.19, intensity=0.1220),
        GammaLine(energy_kev=996.29, intensity=0.1048),
        GammaLine(energy_kev=1004.76, intensity=0.1792),
        GammaLine(energy_kev=1274.43, intensity=0.3489),
    ],
))

_add_isotope(Isotope(
    name="Mn-54",
    atomic_number=25,
    mass_number=54,
    half_life_seconds=312.2 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=834.85, intensity=0.99976),
    ],
))

_add_isotope(Isotope(
    name="Zn-65",
    atomic_number=30,
    mass_number=65,
    half_life_seconds=243.93 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC/beta+",
    gamma_lines=[
        GammaLine(energy_kev=511.0, intensity=0.0284),  # Annihilation
        GammaLine(energy_kev=1115.55, intensity=0.5004),
    ],
))

_add_isotope(Isotope(
    name="Co-57",
    atomic_number=27,
    mass_number=57,
    half_life_seconds=271.74 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=14.41, intensity=0.0916),
        GammaLine(energy_kev=122.06, intensity=0.8560),
        GammaLine(energy_kev=136.47, intensity=0.1068),
    ],
))

_add_isotope(Isotope(
    name="Sr-85",
    atomic_number=38,
    mass_number=85,
    half_life_seconds=64.84 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=514.0, intensity=0.96),
    ],
))

_add_isotope(Isotope(
    name="Y-88",
    atomic_number=39,
    mass_number=88,
    half_life_seconds=106.627 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC/beta+",
    gamma_lines=[
        GammaLine(energy_kev=898.04, intensity=0.937),
        GammaLine(energy_kev=1836.06, intensity=0.9921),
    ],
))

_add_isotope(Isotope(
    name="Ce-139",
    atomic_number=58,
    mass_number=139,
    half_life_seconds=137.641 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=165.86, intensity=0.7990),
    ],
))

_add_isotope(Isotope(
    name="Sn-113",
    atomic_number=50,
    mass_number=113,
    half_life_seconds=115.09 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=391.70, intensity=0.6497),
    ],
))

_add_isotope(Isotope(
    name="Hg-203",
    atomic_number=80,
    mass_number=203,
    half_life_seconds=46.595 * DAY,
    category=IsotopeCategory.CALIBRATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=279.20, intensity=0.8146),
    ],
))

_add_isotope(Isotope(
    name="Se-75",
    atomic_number=34,
    mass_number=75,
    half_life_seconds=119.78 * DAY,
    category=IsotopeCategory.INDUSTRIAL,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=121.12, intensity=0.172),
        GammaLine(energy_kev=136.0, intensity=0.585),
        GammaLine(energy_kev=264.66, intensity=0.589),
        GammaLine(energy_kev=279.54, intensity=0.252),
        GammaLine(energy_kev=400.66, intensity=0.1141),
    ],
))

_add_isotope(Isotope(
    name="Ir-192",
    atomic_number=77,
    mass_number=192,
    half_life_seconds=73.829 * DAY,
    category=IsotopeCategory.INDUSTRIAL,
    decay_mode="beta-/EC",
    gamma_lines=[
        GammaLine(energy_kev=295.96, intensity=0.2872),
        GammaLine(energy_kev=308.46, intensity=0.2970),
        GammaLine(energy_kev=316.51, intensity=0.8286),
        GammaLine(energy_kev=468.07, intensity=0.4781),
        GammaLine(energy_kev=604.41, intensity=0.0823),
        GammaLine(energy_kev=612.46, intensity=0.0534),
    ],
    notes="Industrial radiography source"
))

# -----------------------------------------------------------------------------
# MEDICAL ISOTOPES
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="Tc-99m",
    atomic_number=43,
    mass_number=99,
    half_life_seconds=6.01 * HOUR,
    category=IsotopeCategory.MEDICAL,
    decay_mode="IT",
    gamma_lines=[
        GammaLine(energy_kev=140.51, intensity=0.8906),
    ],
    notes="Most common medical imaging isotope"
))

_add_isotope(Isotope(
    name="I-131",
    atomic_number=53,
    mass_number=131,
    half_life_seconds=8.0252 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=80.19, intensity=0.0262),
        GammaLine(energy_kev=284.31, intensity=0.0614),
        GammaLine(energy_kev=364.49, intensity=0.8170),
        GammaLine(energy_kev=636.99, intensity=0.0717),
        GammaLine(energy_kev=722.91, intensity=0.0177),
    ],
    notes="Thyroid treatment and imaging"
))

_add_isotope(Isotope(
    name="I-123",
    atomic_number=53,
    mass_number=123,
    half_life_seconds=13.2235 * HOUR,
    category=IsotopeCategory.MEDICAL,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=158.97, intensity=0.833),
        GammaLine(energy_kev=528.96, intensity=0.0139),
    ],
    notes="Thyroid imaging"
))

_add_isotope(Isotope(
    name="F-18",
    atomic_number=9,
    mass_number=18,
    half_life_seconds=109.77 * MINUTE,
    category=IsotopeCategory.MEDICAL,
    decay_mode="beta+",
    gamma_lines=[
        GammaLine(energy_kev=511.0, intensity=1.9346),  # Annihilation
    ],
    notes="PET imaging (FDG)"
))

_add_isotope(Isotope(
    name="Ga-67",
    atomic_number=31,
    mass_number=67,
    half_life_seconds=3.2617 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=93.31, intensity=0.3881),
        GammaLine(energy_kev=184.58, intensity=0.2141),
        GammaLine(energy_kev=300.22, intensity=0.1664),
        GammaLine(energy_kev=393.53, intensity=0.0456),
    ],
    notes="Tumor/infection imaging"
))

_add_isotope(Isotope(
    name="In-111",
    atomic_number=49,
    mass_number=111,
    half_life_seconds=2.8047 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=171.28, intensity=0.9066),
        GammaLine(energy_kev=245.35, intensity=0.9409),
    ],
    notes="White blood cell imaging"
))

_add_isotope(Isotope(
    name="Tl-201",
    atomic_number=81,
    mass_number=201,
    half_life_seconds=3.0421 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=68.89, intensity=0.266),  # Mercury X-rays
        GammaLine(energy_kev=70.82, intensity=0.447),
        GammaLine(energy_kev=80.19, intensity=0.205),
        GammaLine(energy_kev=135.34, intensity=0.0256),
        GammaLine(energy_kev=167.43, intensity=0.100),
    ],
    notes="Cardiac imaging"
))

_add_isotope(Isotope(
    name="Lu-177",
    atomic_number=71,
    mass_number=177,
    half_life_seconds=6.647 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=112.95, intensity=0.0617),
        GammaLine(energy_kev=208.37, intensity=0.1036),
    ],
    notes="Targeted radionuclide therapy"
))

_add_isotope(Isotope(
    name="Sm-153",
    atomic_number=62,
    mass_number=153,
    half_life_seconds=46.50 * HOUR,
    category=IsotopeCategory.MEDICAL,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=69.67, intensity=0.0485),
        GammaLine(energy_kev=103.18, intensity=0.2925),
    ],
    notes="Bone pain palliation"
))

_add_isotope(Isotope(
    name="Xe-133",
    atomic_number=54,
    mass_number=133,
    half_life_seconds=5.2475 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=81.0, intensity=0.370),
    ],
    notes="Lung ventilation imaging"
))

_add_isotope(Isotope(
    name="Ra-223",
    atomic_number=88,
    mass_number=223,
    half_life_seconds=11.43 * DAY,
    category=IsotopeCategory.MEDICAL,
    decay_mode="alpha",
    gamma_lines=[
        GammaLine(energy_kev=144.23, intensity=0.0334),
        GammaLine(energy_kev=154.21, intensity=0.0566),
        GammaLine(energy_kev=269.46, intensity=0.1370),
        GammaLine(energy_kev=323.87, intensity=0.0398),
    ],
    notes="Bone metastasis therapy (Xofigo)"
))

# -----------------------------------------------------------------------------
# REACTOR / FALLOUT ISOTOPES
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="Cs-134",
    atomic_number=55,
    mass_number=134,
    half_life_seconds=2.0652 * YEAR,
    category=IsotopeCategory.REACTOR_FALLOUT,
    decay_mode="beta-/EC",
    gamma_lines=[
        GammaLine(energy_kev=475.36, intensity=0.0149),
        GammaLine(energy_kev=563.25, intensity=0.0836),
        GammaLine(energy_kev=569.33, intensity=0.1538),
        GammaLine(energy_kev=604.72, intensity=0.9762),
        GammaLine(energy_kev=795.86, intensity=0.8546),
        GammaLine(energy_kev=801.95, intensity=0.0873),
        GammaLine(energy_kev=1167.97, intensity=0.0180),
        GammaLine(energy_kev=1365.19, intensity=0.0303),
    ],
    notes="Reactor activation/fallout indicator"
))

_add_isotope(Isotope(
    name="Ru-106",
    atomic_number=44,
    mass_number=106,
    half_life_seconds=373.59 * DAY,
    category=IsotopeCategory.REACTOR_FALLOUT,
    daughters=["Rh-106"],
    decay_mode="beta-",
    gamma_lines=[],  # Pure beta, gammas from Rh-106
    notes="Fission product, gammas from Rh-106 daughter"
))

_add_isotope(Isotope(
    name="Rh-106",
    atomic_number=45,
    mass_number=106,
    half_life_seconds=29.80,  # 29.8 seconds
    category=IsotopeCategory.REACTOR_FALLOUT,
    parent="Ru-106",
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=511.85, intensity=0.2040),
        GammaLine(energy_kev=621.93, intensity=0.0993),
        GammaLine(energy_kev=1050.47, intensity=0.0156),
    ],
))

_add_isotope(Isotope(
    name="Ce-144",
    atomic_number=58,
    mass_number=144,
    half_life_seconds=284.91 * DAY,
    category=IsotopeCategory.REACTOR_FALLOUT,
    daughters=["Pr-144"],
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=80.12, intensity=0.0136),
        GammaLine(energy_kev=133.52, intensity=0.1109),
    ],
))

_add_isotope(Isotope(
    name="Pr-144",
    atomic_number=59,
    mass_number=144,
    half_life_seconds=17.28 * MINUTE,
    category=IsotopeCategory.REACTOR_FALLOUT,
    parent="Ce-144",
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=696.51, intensity=0.0134),
        GammaLine(energy_kev=1489.16, intensity=0.00284),
        GammaLine(energy_kev=2185.66, intensity=0.00694),
    ],
))

_add_isotope(Isotope(
    name="Sb-125",
    atomic_number=51,
    mass_number=125,
    half_life_seconds=2.7586 * YEAR,
    category=IsotopeCategory.REACTOR_FALLOUT,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=176.31, intensity=0.0685),
        GammaLine(energy_kev=380.45, intensity=0.0152),
        GammaLine(energy_kev=427.87, intensity=0.2956),
        GammaLine(energy_kev=463.36, intensity=0.1048),
        GammaLine(energy_kev=600.60, intensity=0.1776),
        GammaLine(energy_kev=606.71, intensity=0.0502),
        GammaLine(energy_kev=635.95, intensity=0.1132),
        GammaLine(energy_kev=671.44, intensity=0.0180),
    ],
))

_add_isotope(Isotope(
    name="Co-58",
    atomic_number=27,
    mass_number=58,
    half_life_seconds=70.86 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="EC/beta+",
    gamma_lines=[
        GammaLine(energy_kev=511.0, intensity=0.300),  # Annihilation
        GammaLine(energy_kev=810.76, intensity=0.9945),
    ],
    notes="Activation product in nuclear reactors"
))

_add_isotope(Isotope(
    name="Sr-90",
    atomic_number=38,
    mass_number=90,
    half_life_seconds=28.79 * YEAR,
    category=IsotopeCategory.REACTOR_FALLOUT,
    daughters=["Y-90"],
    decay_mode="beta-",
    gamma_lines=[],  # Pure beta emitter
    notes="Major fallout isotope, pure beta (Y-90 daughter also beta)"
))

_add_isotope(Isotope(
    name="Y-90",
    atomic_number=39,
    mass_number=90,
    half_life_seconds=64.00 * HOUR,
    category=IsotopeCategory.REACTOR_FALLOUT,
    parent="Sr-90",
    decay_mode="beta-",
    gamma_lines=[],  # Essentially pure beta, bremsstrahlung only
    notes="Sr-90 daughter, produces bremsstrahlung continuum"
))

_add_isotope(Isotope(
    name="I-129",
    atomic_number=53,
    mass_number=129,
    half_life_seconds=1.57e7 * YEAR,
    category=IsotopeCategory.REACTOR_FALLOUT,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=39.58, intensity=0.0751),
    ],
    notes="Long-lived fission product"
))

# -----------------------------------------------------------------------------
# ACTIVATION PRODUCTS
# -----------------------------------------------------------------------------

_add_isotope(Isotope(
    name="Fe-59",
    atomic_number=26,
    mass_number=59,
    half_life_seconds=44.495 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=142.65, intensity=0.0102),
        GammaLine(energy_kev=192.35, intensity=0.0303),
        GammaLine(energy_kev=1099.25, intensity=0.5650),
        GammaLine(energy_kev=1291.60, intensity=0.4320),
    ],
))

_add_isotope(Isotope(
    name="Cr-51",
    atomic_number=24,
    mass_number=51,
    half_life_seconds=27.7025 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="EC",
    gamma_lines=[
        GammaLine(energy_kev=320.08, intensity=0.0991),
    ],
))

_add_isotope(Isotope(
    name="Ta-182",
    atomic_number=73,
    mass_number=182,
    half_life_seconds=114.43 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=67.75, intensity=0.4130),
        GammaLine(energy_kev=100.11, intensity=0.1410),
        GammaLine(energy_kev=152.43, intensity=0.0693),
        GammaLine(energy_kev=179.39, intensity=0.0310),
        GammaLine(energy_kev=222.11, intensity=0.0749),
        GammaLine(energy_kev=1121.30, intensity=0.3490),
        GammaLine(energy_kev=1189.05, intensity=0.1623),
        GammaLine(energy_kev=1221.41, intensity=0.2700),
        GammaLine(energy_kev=1231.02, intensity=0.1144),
    ],
))

_add_isotope(Isotope(
    name="Sc-46",
    atomic_number=21,
    mass_number=46,
    half_life_seconds=83.79 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=889.28, intensity=0.99984),
        GammaLine(energy_kev=1120.55, intensity=0.99987),
    ],
))

_add_isotope(Isotope(
    name="Au-198",
    atomic_number=79,
    mass_number=198,
    half_life_seconds=2.6941 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=411.80, intensity=0.9562),
        GammaLine(energy_kev=675.88, intensity=0.0084),
    ],
))

_add_isotope(Isotope(
    name="Ag-110m",
    atomic_number=47,
    mass_number=110,
    half_life_seconds=249.83 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=446.81, intensity=0.0366),
        GammaLine(energy_kev=620.36, intensity=0.0278),
        GammaLine(energy_kev=657.76, intensity=0.9476),
        GammaLine(energy_kev=677.62, intensity=0.1067),
        GammaLine(energy_kev=687.01, intensity=0.0642),
        GammaLine(energy_kev=706.68, intensity=0.1664),
        GammaLine(energy_kev=744.28, intensity=0.0466),
        GammaLine(energy_kev=763.94, intensity=0.2226),
        GammaLine(energy_kev=818.03, intensity=0.0730),
        GammaLine(energy_kev=884.68, intensity=0.7500),
        GammaLine(energy_kev=937.49, intensity=0.3491),
        GammaLine(energy_kev=1384.29, intensity=0.2510),
        GammaLine(energy_kev=1475.79, intensity=0.0399),
        GammaLine(energy_kev=1505.03, intensity=0.1331),
    ],
))

_add_isotope(Isotope(
    name="Hf-181",
    atomic_number=72,
    mass_number=181,
    half_life_seconds=42.39 * DAY,
    category=IsotopeCategory.ACTIVATION,
    decay_mode="beta-",
    gamma_lines=[
        GammaLine(energy_kev=133.02, intensity=0.433),
        GammaLine(energy_kev=136.26, intensity=0.0585),
        GammaLine(energy_kev=345.93, intensity=0.1512),
        GammaLine(energy_kev=482.18, intensity=0.8050),
    ],
))


# =============================================================================
# HELPER FUNCTIONS
# =============================================================================

def get_isotope(name: str) -> Optional[Isotope]:
    """Get an isotope by name (e.g., 'Cs-137')."""
    return ISOTOPE_DATABASE.get(name)


def get_isotopes_by_category(category: IsotopeCategory) -> List[Isotope]:
    """Get all isotopes in a given category."""
    return [iso for iso in ISOTOPE_DATABASE.values() if iso.category == category]


def get_all_isotopes() -> List[Isotope]:
    """Get all isotopes in the database."""
    return list(ISOTOPE_DATABASE.values())


def get_isotope_names() -> List[str]:
    """Get list of all isotope names."""
    return list(ISOTOPE_DATABASE.keys())


def get_isotopes_with_gamma_in_range(
    min_energy_kev: float, 
    max_energy_kev: float
) -> List[Tuple[Isotope, GammaLine]]:
    """Get isotopes with gamma lines in a specific energy range."""
    results = []
    for isotope in ISOTOPE_DATABASE.values():
        for line in isotope.gamma_lines:
            if min_energy_kev <= line.energy_kev <= max_energy_kev:
                results.append((isotope, line))
    return results


# Number of isotopes in database
print(f"Isotope database loaded: {len(ISOTOPE_DATABASE)} isotopes")
