# HistoForge release notes

## 0.2.4

Requires QuPath 0.6.0 or 0.7.0 and HistoForge backend 0.1.5.

- Install the new JAR and restart QuPath. In HistoForge settings, select
  **Update cloud backend**. Updates use the saved Modal workspace and
  environment.
- Settings shows cloud connection feedback beside the relevant controls.
  Connected backends can offer H200 for inference.

### Training in QuPath

- **Train as** shows what each QuPath annotation class teaches the model.
  **Model predicts** lists the labels the trained model will return.
- The **Train** tab reads annotations when it opens. You can choose a model
  while they load. Changing a class assignment updates the object counts and
  keeps the split you reviewed.
- Before you start training or export a dataset, HistoForge flags any new
  class that has no examples in **Training**, including a class whose only
  examples are in **Validation**.
- If the cloud rejects overlapping training labels, the error gives the image
  name and pixel coordinates so you can find the annotations in QuPath.
  For small overlaps, you can choose to trim copies of the outlines and retry.

### Module development

- Finite point detection can return scored object centers from an explicit
  detector recipe and checkpoint. QuPath can save those points and use them in
  Metrics. Detector training is outside this release.

## 0.2.3 — 2026-11-15

Added support for analyzing larger regions with modules that enable it.
Cloud setup shows installation progress, and module installation explains
the permissions being requested. This version uses QuPath 0.6 or 0.7 and
HistoForge backend 0.1.4. [Release notes and downloads](https://github.com/HistoForge/histoforge-qupath-extension/releases/tag/v0.2.3)
