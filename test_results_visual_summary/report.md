# NoTouch gaze evaluation: target-referenced error report

- Source/build label: **UNVERIFIED: build revision not recorded in session metadata (app versionName 0.1.0, versionCode 1); not attributable to main or any branch**
- Tool: analyze_gaze_eval 1.1.1; log schema [1]
- Sufficiency rule: ≥ 10 valid measurement samples **and** ≥ 0.5 valid coverage per presentation; incomplete sessions excluded from summaries
- Sessions analyzed: 1; rejected: 0

## How to read this report

- **Targets are the instructed fixation points, not independently verified ground truth.** No eye tracker measured where the participant actually looked, so every spatial value is a *target-referenced gaze error*.
- Invalid inference attempts are **excluded from spatial error** (never imputed or carried forward) and **included in availability statistics** (coverage, dropout).
- `features_unavailable` combines an eye closed, an eye too small, and too few landmarks. **It cannot be identified specifically as blinking.**
- Settling-phase samples (first 1,000 ms of each target) are excluded from accuracy and jitter.
- Statistics appear at three levels: **per presentation** (presentations.csv), **trial-balanced** (equal weight per eligible presentation; primary) and **pooled samples** (sensitivity only).
- Units: normalized = fraction of the coordinate basis; px uses the session's recorded basis (dx × width, dy × height). No visual-angle conversion. Positive X = right, positive Y = down.
- Results describe the tested participant(s), phone(s) and conditions only; they do not generalize.
- No pass/fail threshold is defined for these values.

## Visual summary

One figure per session; sessions and modes are never combined in these figures. Build provenance is stated at the top of this report, not inside the figures.

### Blind measurement (primary)

**Session `eval-20261023T054414Z-cbec17a1`**

![plots/summary_eval-20261023T054414Z-cbec17a1.svg](plots/summary_eval-20261023T054414Z-cbec17a1.svg)

- Values at a screen edge among valid measurement samples: 4 cursor values and 13 model values at the bottom edge (y = 1.0). The log stores only values bounded to the screen and has no clip flag, so these are not confirmed clipping events.

## Primary result: blind_measurement

- Sessions included: **1**; excluded: 0
- Devices: samsung SM-S918N; pixel basis: 1080x2316
- Presentations: 32 eligible, 0 insufficient_data, 0 incomplete_presentation
- Measurement windows: 575 valid of 580 inference attempts (coverage 99.1%, 12.0 valid samples/s)
- Invalid measurement attempts by logged reason: `features_unavailable` 5

**Trial-balanced** (each eligible presentation weighted equally; n = 32 presentations)

| Metric | Cursor (primary) norm | Cursor px | Model (diagnostic) norm | Model px |
|---|---|---|---|---|
| Mean of presentation mean errors | 0.1107 | 168.8 | 0.1187 | 179.0 |
| Median of presentation mean errors | 0.1191 | 170.4 | 0.1188 | 171.8 |
| p95 of presentation mean errors | 0.1733 | 295.6 | 0.1984 | 348.4 |
| Median of presentation median errors | 0.1057 | 153.4 | 0.1166 | 161.6 |
| Median of presentation p95 errors | 0.1468 | 209.1 | 0.1512 | 226.8 |
| Mean centroid error | 0.0964 | 139.2 | 0.1027 | 146.7 |
| Mean X bias (+ right) | 0.0750 | 81.0 | 0.0825 | 89.1 |
| Mean Y bias (+ down) | -0.0077 | -17.9 | -0.0174 | -40.4 |
| Mean jitter RMS | 0.0488 | 93.6 | 0.0588 | 111.4 |

**Pooled-sample sensitivity view** (every valid measurement sample weighted equally, including insufficient presentations; n = 575 samples)

Samples from insufficient or partial presentations: 0.

| Metric | Cursor norm | Cursor px | Model norm | Model px |
|---|---|---|---|---|
| Mean error | 0.1106 | 168.2 | 0.1186 | 178.5 |
| Median error | 0.1061 | 150.8 | 0.1137 | 159.5 |
| p95 error | 0.1957 | 359.9 | 0.2039 | 302.1 |
| X bias (+ right) | 0.0750 | 81.0 | 0.0823 | 88.9 |
| Y bias (+ down) | -0.0078 | -18.1 | -0.0173 | -40.1 |
| Jitter RMS | 0.0662 | 133.8 | 0.0815 | 165.3 |

Supplementary plots, pooled across the 1 included session(s) of this mode. The error histogram weights camera frames, not presentations; the visual summary above is the primary view.

![plots/blind_measurement_target_map.svg](plots/blind_measurement_target_map.svg)
![plots/blind_measurement_error_distribution.svg](plots/blind_measurement_error_distribution.svg)

## Separate result: live_preview

Reported separately: a visible cursor can change where the participant looks. Never combine with the blind result.

- Sessions included: **0**; excluded: 0
- Devices: none; pixel basis: none
- Presentations: 0 eligible, 0 insufficient_data, 0 incomplete_presentation
- Measurement windows: 0 valid of 0 inference attempts (coverage N/A, N/A valid samples/s)
- Invalid measurement attempts by logged reason: none

**Trial-balanced** (each eligible presentation weighted equally; n = 0 presentations)

N/A: no eligible presentations.

**Pooled-sample sensitivity view** (every valid measurement sample weighted equally, including insufficient presentations; n = 0 samples)

N/A: no valid measurement samples.


## Sessions

| Session | Mode | State (end reason) | Device | Presentations done/planned | Eligible / insufficient / incomplete | Coverage | Valid/s | Rows lost | Issues |
|---|---|---|---|---|---|---|---|---|---|
| `eval-20261023T054414Z-cbec17a1` | blind_measurement | complete (completed) | samsung SM-S918N | 32/32 | 32 / 0 / 0 | 99.1% | 12.0 | 0 | 0 |

### Availability and pipeline counters

Inference-attempt coverage (above) is separate from analyzer frame counters (below), which come from metadata.

| Session | Frames received | Cadence-skipped | Ignored (closing) | Inference attempts | Unrecorded attempts | Settling attempts / invalid | Measurement invalid by reason |
|---|---|---|---|---|---|---|---|
| `eval-20261023T054414Z-cbec17a1` | 1918 | 953 | 0 | 965 | 0 | 385 / 7 | `features_unavailable` 5 |

## Definitions

- **reference**: Targets are instructed fixation points, not independently measured eye direction.
- **coordinate_frame**: Gaze-model display-normalized [0,1] frame shared by calibration, targets, model and cursor.
- **dx_dy**: dx = prediction_x - target_x (positive = right of target); dy = prediction_y - target_y (positive = below).
- **radial_error**: sqrt(dx^2 + dy^2); pixels use dx_px = dx_norm * basis_width_px, dy_px = dy_norm * basis_height_px.
- **valid_sample**: phase == measurement, prediction_valid == true, and finite target, model and cursor coordinates.
- **invalid_attempts**: Excluded from spatial error (never imputed or carried forward); counted in coverage and dropout.
- **features_unavailable**: Logged reason combining eye closed, eye too small and too few landmarks; not identified as blinking.
- **valid_coverage**: valid samples / measurement-phase inference attempts logged for the presentation.
- **valid_sample_rate**: valid samples / observed measurement-window seconds.
- **sufficiency**: A presentation is eligible if fully shown, valid samples >= min_valid_samples and valid coverage >= min_valid_coverage.
- **jitter_rms**: sqrt(mean((x - mean_x)^2 + (y - mean_y)^2)) of valid samples in one presentation: observed cursor stability during a target hold, not pure model or sensor noise.
- **centroid_error**: Distance from the presentation's mean predicted position to the target.
- **trial_balanced**: Each eligible presentation's metric is computed first; presentations are then weighted equally.
- **pooled**: Sensitivity view: every valid measurement sample weighted equally, including insufficient presentations.
- **median**: statistics.median: mean of the two middle values for an even count.
- **p95**: Nearest-rank: the k-th smallest value with k = ceil(0.95 * n), computed in integer arithmetic.
