"""
Analyze uranium detection in inference data.
"""
import json
import numpy as np

# Read the inference request
with open(r'c:\Users\ryans\source\repos\RadiaCodeAndroidDataCollection\vega_ml\analyzer\out\last_inference_request_20260125_121145.json', encoding='utf-8-sig') as f:
    data = json.load(f)

# Get the 2D spectrum (60 time intervals x 1023 channels)
spectrum_2d = data['json']['spectrum']
print(f'Spectrum shape: {len(spectrum_2d)} time intervals x {len(spectrum_2d[0])} channels')

# Sum across time to get total counts per channel
spectrum_sum = np.sum(spectrum_2d, axis=0)

# Energy conversion functions
e_min, e_max = 20.0, 3000.0
num_channels = 1023

def channel_to_energy(channel):
    return e_min + channel * (e_max - e_min) / num_channels

def energy_to_channel(energy_kev):
    channel = int((energy_kev - e_min) / (e_max - e_min) * num_channels)
    return max(0, min(num_channels - 1, channel))

# Examine key channels for uranium
print()
print('=== SPECTRUM DATA AT URANIUM-RELATED CHANNELS ===')
print()

# Channel ranges to examine
regions = [
    ('U-238/U-234 region (ch 8-15, ~40-65 keV)', range(8, 16)),
    ('U-235 143 keV region (ch 40-45)', range(40, 46)),
    ('U-235 163 keV region (ch 47-52)', range(47, 53)),
    ('U-235/Ra-226 186 keV region (ch 54-60)', range(54, 61)),
    ('U-235 205 keV region (ch 61-66)', range(61, 67)),
    ('Pb-214 242 keV region (ch 74-79)', range(74, 80)),
    ('Pb-214 295 keV region (ch 92-97)', range(92, 98)),
    ('Pb-214 352 keV region (ch 111-116)', range(111, 117)),
]

for name, channels in regions:
    print(f'{name}:')
    values = [spectrum_sum[ch] for ch in channels]
    print(f'  Channels: {list(channels)}')
    print(f'  Values:   {[f"{v:.4f}" for v in values]}')
    print(f'  Sum: {sum(values):.4f}, Max: {max(values):.4f}')
    print()

# Now look at the response predictions
print('=== INFERENCE PREDICTIONS FOR URANIUM ISOTOPES ===')
print()

with open(r'c:\Users\ryans\source\repos\RadiaCodeAndroidDataCollection\vega_ml\analyzer\out\last_inference_response_20260125_121145.json', encoding='utf-8-sig') as f:
    response = json.load(f)

isotope_names = response['json']['isotope_names']
probabilities = response['json']['probabilities']

# Find uranium-related isotopes
uranium_isotopes = ['U-234', 'U-235', 'U-238', 'Th-234', 'Ra-226', 'Pb-214', 'Bi-214', 'Pb-210']
print(f'{"Isotope":<12} {"Probability":>15} {"Index":>8}')
print('-' * 40)
for iso in uranium_isotopes:
    if iso in isotope_names:
        idx = isotope_names.index(iso)
        prob = probabilities[idx]
        print(f'{iso:<12} {prob:>15.6f} ({prob*100:.2f}%) {idx:>8}')
    else:
        print(f'{iso:<12} NOT IN MODEL')

# Print top 10 predictions
print()
print('=== TOP 10 PREDICTIONS ===')
sorted_indices = np.argsort(probabilities)[::-1]
for i, idx in enumerate(sorted_indices[:10]):
    print(f'{i+1}. {isotope_names[idx]:<12}: {probabilities[idx]*100:.2f}%')

# Now analyze what the model is seeing - look at the spectrum peaks
print()
print('=== SPECTRUM PEAK ANALYSIS (channels with highest summed counts) ===')
sorted_channels = np.argsort(spectrum_sum)[::-1][:30]
print(f'{"Rank":<6} {"Channel":<10} {"Energy (keV)":<15} {"Sum Value":<15}')
print('-' * 50)
for i, ch in enumerate(sorted_channels):
    print(f'{i+1:<6} {ch:<10} {channel_to_energy(ch):<15.1f} {spectrum_sum[ch]:<15.4f}')

# Key analysis of uranium signature comparison
print()
print('='*70)
print('=== CRITICAL ANALYSIS: URANIUM GAMMA LINE COMPARISON ===')
print('='*70)
print()

# Compare training data definition vs expected energies
print('ISOTOPE DATABASE GAMMA LINES VS EXPECTED:')
print()
print('U-238 in isotope_data.py:')
print('  - 49.55 keV @ intensity 0.000064 (0.0064%)')
print('  ^^^ This is EXTREMELY weak - essentially undetectable!')
print()
print('U-235 in isotope_data.py:')
print('  - 143.76 keV @ intensity 0.1096 (10.96%)')
print('  - 163.33 keV @ intensity 0.0508 (5.08%)')
print('  - 185.72 keV @ intensity 0.5720 (57.20%) <- PRIMARY LINE')
print('  - 205.31 keV @ intensity 0.0503 (5.03%)')
print()

# Check actual spectrum at U-235 186 keV line
u235_primary_channel = energy_to_channel(185.72)
print(f'U-235 primary line (185.72 keV) -> Channel {u235_primary_channel}')
print(f'Spectrum value at channel {u235_primary_channel}: {spectrum_sum[u235_primary_channel]:.4f}')

# Check neighborhood
print(f'Spectrum values around 186 keV (channels 54-60): {[f"{spectrum_sum[ch]:.2f}" for ch in range(54, 61)]}')

# Compare to the nearby peaks at 80 keV region (channels 18-26)
print()
print('Compare to dominant signal (80 keV region):')
print(f'Channels 18-26 values: {[f"{spectrum_sum[ch]:.2f}" for ch in range(18, 27)]}')

print()
print('='*70)
print('=== ROOT CAUSE ANALYSIS ===')
print('='*70)
print("""
PROBLEM IDENTIFIED:

1. U-238 ISSUE:
   - The isotope_data.py defines U-238 with ONLY ONE gamma line:
     49.55 keV @ 0.000064 (0.0064% branching ratio)
   - This is essentially INVISIBLE to the detector
   - U-238 is primarily an ALPHA emitter with almost no direct gamma
   - Real U-238 detection relies on DAUGHTERS (Th-234, Pa-234m, Pb-214, Bi-214)

2. INFERENCE FILE REFERENCE (vega_portable_inference.py):
   - Lists U-238 gamma as: [(49.6, 0.064), (113.5, 0.017)]
   - The 0.064 vs 0.000064 is a HUGE discrepancy (1000x different)!
   - 113.5 keV line is NOT in isotope_data.py

3. MODEL CONFUSION:
   - The model sees strong peaks at 80-100 keV (channels 18-26)
   - This could be from Th-234 daughters or other sources
   - Without proper U-238 chain daughter association, detection fails

4. WHY U-235 at 51.7% but U-238 at 0.007%:
   - U-235 has a STRONG 185.72 keV line (57% branching ratio)
   - The spectrum shows moderate activity at channels 54-60
   - U-238's single weak gamma is essentially undetectable
   - The model correctly identifies U-235 because it has visible signatures

RECOMMENDATIONS:
1. For U-238 detection, train on the DECAY CHAIN daughters:
   - Pb-214 (241.98, 295.22, 351.93 keV)
   - Bi-214 (609.31, 1120.29, 1764.49 keV)
   
2. Or add X-ray fluorescence lines that are actually measurable for uranium

3. The training data generator should include secular equilibrium daughter
   products when generating U-238 samples
""")

# Compare spectrum at key diagnostic energies
print()
print('=== SPECTRUM VALUES AT KEY DIAGNOSTIC ENERGIES ===')
diagnostic_energies = [
    ('U-238 weak gamma', 49.55, 'U-238'),
    ('Th-234 primary', 63.29, 'Th-234'),
    ('U-235 143 keV', 143.76, 'U-235'),
    ('U-235 PRIMARY 186 keV', 185.72, 'U-235'),
    ('Ra-226 186 keV', 186.21, 'Ra-226'),
    ('Pb-214 242 keV', 241.98, 'Pb-214'),
    ('Pb-214 295 keV', 295.22, 'Pb-214'),
    ('Pb-214 352 keV', 351.93, 'Pb-214'),
    ('Bi-214 609 keV', 609.31, 'Bi-214'),
    ('Cs-137 662 keV', 661.7, 'Cs-137'),
]

print(f'{"Description":<25} {"Energy":<12} {"Channel":<10} {"Spectrum":<12} {"Source":<10}')
print('-' * 75)
for desc, energy, source in diagnostic_energies:
    ch = energy_to_channel(energy)
    val = spectrum_sum[ch]
    print(f'{desc:<25} {energy:<12.2f} {ch:<10} {val:<12.4f} {source:<10}')
