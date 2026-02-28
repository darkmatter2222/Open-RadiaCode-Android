# Semantic Isolation (Experiment)

Goal: evaluate whether Euclidean nearest-neighbor retrieval over a learned embedding space ("semantic similarity") can outperform the trained classifier head for **single-isotope** spectra.

This experiment uses the existing **Vega 2D** model (Conv2D + FC backbone) as a feature extractor and compares:

- **Baseline**: trained model top-1 (argmax over sigmoid logits)
- **Embedding 1-NN**: Euclidean nearest neighbor in embedding space
- **Embedding centroid**: Euclidean nearest class centroid in embedding space
- **Raw centroid**: Euclidean nearest class centroid using mean-collapsed spectrum (time-averaged 1023-vector)

## Run

From repo root:

```powershell
python vega_ml/experiments/semantic_isolation/run_experiment.py --data-dir "O:/master_data_collection/isotopev4" --model "vega_ml/models/vega_2d_best.pt"
```

Outputs:
- `vega_ml/experiments/semantic_isolation/artifacts/results.json`
- `vega_ml/experiments/semantic_isolation/REPORT.md`
