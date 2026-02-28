"""Fine-grained search around the best parameters found."""
import numpy as np
import torch
import sys
sys.path.insert(0, 'C:/Users/ryans/source/repos/RadiaCodeAndroidDataCollection')

from vega_ml.training.vega.model_2d import Vega2DModel, Vega2DConfig
from vega_ml.synthetic_spectra.ground_truth.isotope_data import ISOTOPE_DATABASE

# Load model
ckpt = torch.load('vega_ml/models/vega_2d_v3_best.pt', map_location='cpu', weights_only=False)
model = Vega2DModel(config=Vega2DConfig(num_channels=1023, num_time_intervals=300, num_isotopes=82))
model.load_state_dict(ckpt['model_state_dict'])
model.eval()

isotope_names = sorted(ISOTOPE_DATABASE.keys())[:82]

tests = [
    ('Uranium', 'vega_ml/client_app_device_data/Uranium-300-1023-rc110-0cm.csv', ['U-238','Pb-214','Bi-214','Ra-226']),
    ('Thorium', 'vega_ml/client_app_device_data/Thorium-300-1023-rc110-0cm.csv', ['Th-232','Ac-228','Pb-212','Bi-212']),
    ('Radium', 'vega_ml/client_app_device_data/Radium-300-1023-rc110-0cm.csv', ['Ra-226','Pb-214','Bi-214']),
]

data_cache = {name: np.genfromtxt(path, delimiter=',', skip_header=1, dtype=np.float32) for name, path, _ in tests}

TRAIN_E_MIN = 20.0
TRAIN_E_MAX = 3000.0
TRAIN_CH_WIDTH = (TRAIN_E_MAX - TRAIN_E_MIN) / 1024

def get_training_energies():
    raw_channels = np.arange(1, 1024, dtype=np.float64)
    return TRAIN_E_MIN + (raw_channels + 0.5) * TRAIN_CH_WIDTH

def get_device_energies(a0, a1, a2, n_ch=1023):
    channels = np.arange(n_ch, dtype=np.float64)
    return a0 + a1 * channels + a2 * channels**2

def translate_with_offset(device_counts, a0, a1, a2, energy_offset=0.0):
    device_energies = get_device_energies(a0, a1, a2, len(device_counts)) + energy_offset
    training_energies = get_training_energies()
    return np.interp(training_energies, device_energies, device_counts, left=0.0, right=0.0)

def translate_spectrogram_with_offset(spectrogram, a0, a1, a2, energy_offset=0.0):
    return np.array([translate_with_offset(row, a0, a1, a2, energy_offset) for row in spectrogram])

def infer(data):
    data = data.astype(np.float32)
    if data.max() > 0:
        data = data / data.max()
    x = torch.from_numpy(data).unsqueeze(0).unsqueeze(0)
    with torch.no_grad():
        logits, _ = model(x)
        return torch.sigmoid(logits).squeeze().numpy()

def test_params(a0, a1, a2, offset, topk=5):
    total_hits = 0
    total_expected = 0
    results = {}
    for name, path, expected in tests:
        raw = data_cache[name]
        translated = translate_spectrogram_with_offset(raw, a0, a1, a2, offset)
        probs = infer(translated)
        topk_isotopes = set(isotope_names[i] for i in np.argsort(probs)[::-1][:topk])
        hits = len(set(expected) & topk_isotopes)
        total_hits += hits
        total_expected += len(expected)
        results[name] = (hits, len(expected), topk_isotopes)
    return total_hits, total_expected, results

# Best so far: a1=2.40, offset=-5.0
orig_a0, orig_a2 = 3.5093544, 4.0645464e-4

print("="*70)
print("FINE-GRAINED SEARCH around a1=2.40, offset=-5.0")
print("="*70)

best = (9, orig_a0, 2.40, orig_a2, -5.0, {})

# Fine search around best parameters
print("\n1. Fine a1 and offset search:")
for a1 in np.linspace(2.35, 2.45, 21):
    for offset in np.linspace(-10, 0, 21):
        hits, total, results = test_params(orig_a0, a1, orig_a2, offset)
        if hits > best[0]:
            best = (hits, orig_a0, a1, orig_a2, offset, results)
            print(f"  NEW BEST: a1={a1:.4f}, offset={offset:.2f} -> {hits}/{total}")

# Try with different a0 values
print("\n2. Fine a0 + a1 + offset search:")
for a0 in np.linspace(0, 10, 11):
    for a1 in np.linspace(2.38, 2.42, 9):
        for offset in np.linspace(-8, -2, 7):
            hits, total, results = test_params(a0, a1, orig_a2, offset)
            if hits > best[0]:
                best = (hits, a0, a1, orig_a2, offset, results)
                print(f"  NEW BEST: a0={a0:.2f}, a1={a1:.4f}, offset={offset:.2f} -> {hits}/{total}")

# Try with different a2 values
print("\n3. Search a2 with best a1/offset:")
best_a0, best_a1, best_offset = best[1], best[2], best[4]
for a2_mult in np.linspace(0.8, 1.2, 21):
    a2 = orig_a2 * a2_mult
    hits, total, results = test_params(best_a0, best_a1, a2, best_offset)
    if hits > best[0]:
        best = (hits, best_a0, best_a1, a2, best_offset, results)
        print(f"  NEW BEST: a2_mult={a2_mult:.2f} (a2={a2:.8f}) -> {hits}/{total}")

print(f"\n{'='*70}")
print(f"BEST RESULT: {best[0]}/11")
print(f"  a0={best[1]:.6f}")
print(f"  a1={best[2]:.6f}")
print(f"  a2={best[3]:.10f}")
print(f"  offset={best[4]:.2f} keV")
print("="*70)

# Show detailed results for best
if best[5]:
    print("\nDetailed results:")
    for name, (hits, total, topk) in best[5].items():
        print(f"  {name}: {hits}/{total} - top5: {topk}")
