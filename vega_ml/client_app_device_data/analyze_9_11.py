"""Analyze what's still missing at 9/11 and try broader search."""
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

# Best parameters so far
a0 = 3.5093544
a1 = 2.40
a2 = 4.0645464e-4
offset = -5.0

print("="*70)
print("ANALYSIS OF 9/11 RESULT")
print(f"Parameters: a0={a0}, a1={a1}, a2={a2}, offset={offset}")
print("="*70)

for name, path, expected in tests:
    raw = data_cache[name]
    translated = translate_spectrogram_with_offset(raw, a0, a1, a2, offset)
    probs = infer(translated)
    
    sorted_idx = np.argsort(probs)[::-1]
    print(f"\n{name}:")
    print(f"  Top 10 predictions:")
    for i in range(10):
        iso = isotope_names[sorted_idx[i]]
        prob = probs[sorted_idx[i]]
        marker = " <<<" if iso in expected else ""
        print(f"    {i+1:2d}. {iso:10s} {prob:.4f}{marker}")
    
    print(f"  Expected isotope rankings:")
    for exp in expected:
        if exp in isotope_names:
            idx = isotope_names.index(exp)
            rank = np.where(sorted_idx == idx)[0][0] + 1
            prob = probs[idx]
            status = "OK" if rank <= 5 else "MISSED"
            print(f"    {exp:10s}: rank {rank:2d}, prob {prob:.4f} [{status}]")
