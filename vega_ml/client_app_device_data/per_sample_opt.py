"""Optimize parameters separately for each sample to understand limits."""
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

orig_a0, orig_a1, orig_a2 = 3.5093544, 2.3624456, 4.0645464e-4

print("="*70)
print("PER-SAMPLE OPTIMIZATION")
print("Find best parameters for EACH sample independently")
print("="*70)

for name, path, expected in tests:
    raw = np.genfromtxt(path, delimiter=',', skip_header=1, dtype=np.float32)
    print(f"\n{name}:")
    print(f"  Expected: {expected}")
    
    best_hits = 0
    best_params = None
    
    # Broad search
    for a1 in np.linspace(2.30, 2.50, 11):
        for offset in np.linspace(-15, 15, 31):
            translated = translate_spectrogram_with_offset(raw, orig_a0, a1, orig_a2, offset)
            probs = infer(translated)
            sorted_idx = np.argsort(probs)[::-1]
            top5 = set(isotope_names[i] for i in sorted_idx[:5])
            hits = len(set(expected) & top5)
            
            if hits > best_hits:
                best_hits = hits
                best_params = (a1, offset, top5)
    
    print(f"  Best possible: {best_hits}/{len(expected)}")
    print(f"  Parameters: a1={best_params[0]:.4f}, offset={best_params[1]:.1f}")
    print(f"  Top-5: {best_params[2]}")
    
    # Show what's still missing
    missing = set(expected) - best_params[2]
    if missing:
        print(f"  Still missing: {missing}")
        # Show rankings of missing isotopes
        translated = translate_spectrogram_with_offset(raw, orig_a0, best_params[0], orig_a2, best_params[1])
        probs = infer(translated)
        sorted_idx = np.argsort(probs)[::-1]
        for m in missing:
            idx = isotope_names.index(m)
            rank = np.where(sorted_idx == idx)[0][0] + 1
            print(f"    {m}: rank {rank}, prob {probs[idx]:.4f}")
