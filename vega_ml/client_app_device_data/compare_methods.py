"""Compare linear interpolation vs flux-conserving rebinning for translation."""
import numpy as np
import torch
import sys
sys.path.insert(0, 'C:/Users/ryans/source/repos/RadiaCodeAndroidDataCollection')

from vega_ml.client_app_device_data.spectrum_translator_v2 import (
    translate_spectrogram_flux, translate_spectrogram_linear
)
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

a0, a1, a2 = 3.5093544, 2.3624456, 4.0645464e-4

def infer(data):
    data = data.astype(np.float32)
    if data.max() > 0:
        data = data / data.max()
    x = torch.from_numpy(data).unsqueeze(0).unsqueeze(0)
    with torch.no_grad():
        logits, _ = model(x)
        return torch.sigmoid(logits).squeeze().numpy()

print("="*70)
print("COMPARISON: Linear Interpolation vs Flux-Conserving Rebinning")
print("="*70)

for translate_func, method_name in [(translate_spectrogram_linear, "Linear"), 
                                      (translate_spectrogram_flux, "Flux-Conserving")]:
    print(f"\n{method_name} Method:")
    print("-"*50)
    
    total_hits = 0
    total_expected = 0
    
    for name, path, expected in tests:
        raw = np.genfromtxt(path, delimiter=',', skip_header=1, dtype=np.float32)
        translated = translate_func(raw, a0, a1, a2)
        probs = infer(translated)
        
        sorted_idx = np.argsort(probs)[::-1]
        top5_isotopes = set(isotope_names[i] for i in sorted_idx[:5])
        hits = len(set(expected) & top5_isotopes)
        total_hits += hits
        total_expected += len(expected)
        
        print(f"  {name}: {hits}/{len(expected)} in top-5")
        print(f"    Top-5: {[isotope_names[i] for i in sorted_idx[:5]]}")
        # Show rankings of expected isotopes
        for exp in expected:
            if exp in isotope_names:
                idx = isotope_names.index(exp)
                rank = np.where(sorted_idx == idx)[0][0] + 1
                prob = probs[idx]
                marker = "OK" if rank <= 5 else "MISSED"
                print(f"      {exp}: rank {rank}, prob {prob:.4f} [{marker}]")
    
    print(f"\n  TOTAL: {total_hits}/{total_expected} ({100*total_hits/total_expected:.0f}%)")
