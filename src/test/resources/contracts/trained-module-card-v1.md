---
tags: [histoforge, dinov3-adapter-cellpose]
---

# Fine-tuned Leydig workflow · HistoForge module

- **Repository:** `alice/my-leydig`
- **Output:** Instance segmentation
- **Classes:** Leydig cell
- **Input:** Field at 0.5 µm/px
- **Architecture:** dinov3-adapter-cellpose
- **Base encoder:** MahmoodLab/UNI2-h
- **Source:** histoforge/leydig@1111111111111111111111111111111111111111
- **Dataset:** 70 object labels · 2 ROIs · 2 slides
- **Validation:** Validation mSA 0.7


## Use

In HistoForge, open **Modules**, choose **+**, choose **From Hugging Face**, and install
`alice/my-leydig`.
