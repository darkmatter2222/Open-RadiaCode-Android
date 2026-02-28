"""Analyze gamma line overlaps between true and false positive isotopes."""
from vega_ml.synthetic_spectra.ground_truth.isotope_data import ISOTOPE_DATABASE

# True isotopes in samples
true_isotopes = ['U-238', 'Pb-214', 'Bi-214', 'Ra-226', 'Th-232', 'Ac-228', 'Pb-212', 'Bi-212']
# False positives appearing
false_positives = ['Ba-133', 'Cd-109', 'Ga-67']

print('TRUE ISOTOPES (should be detected):')
print('='*60)
for iso in true_isotopes:
    if iso in ISOTOPE_DATABASE:
        isotope = ISOTOPE_DATABASE[iso]
        energies = sorted([line.energy_kev for line in isotope.gamma_lines])
        print(f'{iso:10s}: {[f"{e:.1f}" for e in energies]}')
    else:
        print(f'{iso:10s}: NOT IN DATABASE')

print()
print('FALSE POSITIVES (should NOT be detected):')
print('='*60)
for iso in false_positives:
    if iso in ISOTOPE_DATABASE:
        isotope = ISOTOPE_DATABASE[iso]
        energies = sorted([line.energy_kev for line in isotope.gamma_lines])
        print(f'{iso:10s}: {[f"{e:.1f}" for e in energies]}')
    else:
        print(f'{iso:10s}: NOT IN DATABASE')

print()
print('OVERLAP ANALYSIS (lines within 15 keV):')
print('='*60)
# Get all true isotope energies
true_energies = []
for iso in true_isotopes:
    if iso in ISOTOPE_DATABASE:
        for line in ISOTOPE_DATABASE[iso].gamma_lines:
            true_energies.append((line.energy_kev, iso))
true_energies.sort()

# Check for close matches with false positives
for fp_iso in false_positives:
    if fp_iso in ISOTOPE_DATABASE:
        print(f'\n{fp_iso} lines that overlap with true isotopes:')
        found = False
        for line in ISOTOPE_DATABASE[fp_iso].gamma_lines:
            fp_e = line.energy_kev
            for true_e, true_iso in true_energies:
                if abs(fp_e - true_e) < 15:
                    print(f'  {fp_iso} {fp_e:.1f} keV  <->  {true_iso} {true_e:.1f} keV (diff={fp_e-true_e:+.1f})')
                    found = True
        if not found:
            print('  (none)')
