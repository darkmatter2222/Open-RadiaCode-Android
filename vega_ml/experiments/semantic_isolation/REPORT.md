# Semantic Isolation Experiment Report

## Setup

- Date: 2026-01-25 17:48:58
- Device: `cuda`
- Data dir: `O:/master_data_collection/isotopev4`
- Model: `vega_ml/models/vega_2d_best.pt`
- Target time intervals: `60`

## Dataset

- Single-isotope samples found: `68,462`
- Isotopes with any single-isotope samples: `23`
- Isotopes selected: `20`
- Reference samples: `4,000`
- Test samples: `2,000`

Selected isotopes (class order used in confusion matrices):
- 00: Co-57
- 01: Na-22
- 02: Eu-152
- 03: Mn-54
- 04: Am-241
- 05: Co-60
- 06: Ba-133
- 07: Zn-65
- 08: Lu-177
- 09: Co-58
- 10: I-131
- 11: Tl-201
- 12: Tc-99m
- 13: I-123
- 14: Ga-67
- 15: Se-75
- 16: Ir-192
- 17: F-18
- 18: In-111
- 19: Cd-109

## Methods Compared

- Baseline: Vega2D classifier head (top-1 by sigmoid probability)
- Embedding 1-NN: Euclidean nearest neighbor over Vega2D FC-backbone embeddings
- Embedding centroid: Euclidean nearest class centroid in embedding space
- Raw centroid: Euclidean nearest class centroid over time-mean 1023-channel spectrum

## Results

Accuracy (single-label, top-1 unless noted):

- Baseline top-1: `0.9855`
- Baseline top-5: `0.9965`
- Embedding 1-NN top-1: `0.9775`
- Embedding centroid top-1: `0.9725`
- Raw centroid top-1: `0.8085`

## Timings

- scan: `8.12s`
- ref_embed: `5.04s`
- baseline_eval: `3.93s`
- knn_1nn: `0.01s`
- knn_centroid: `0.00s`
- knn_raw_centroid: `0.00s`

## Notes / Interpretation

- If embedding kNN beats baseline, it suggests the backbone learns a metric space that is more separable than the classifier head for this single-isotope regime.
- If baseline beats embedding kNN, it suggests the classifier head is well-aligned to the task (or the embedding is not Euclidean-friendly without additional metric learning).
- If raw centroid is competitive, a lot of separability may be explained by simple spectral shape averages in this dataset.

## Artifacts

- `artifacts/results.json` contains full metrics and confusion matrices.
