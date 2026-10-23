# NoTouch gaze-accuracy evaluation: protocol, tooling, and first device session

| | |
|---|---|
| Document date | 2026-10-23 |
| Applies to | Evaluation schema v1, analyzer `analyze_gaze_eval` 1.1.1 |
| Source state | Written on branch `feature/gaze-accuracy-evaluation` (based on `006d2bc`) while the evaluation, logger, and analyzer were uncommitted. They were committed together with this document **after** the first session was recorded, so that commit is not a verified source of the session's APK (see §5). |
| First real session | `eval-20261023T054414Z-cbec17a1` (exploratory; build provenance **unverified**) |
| Current analysis | `test_results_visual_summary/` (analyzer 1.1.1). An older analyzer 1.0.0 output, `test_results_analysis_v1_0_0_archive/`, is kept only as a local archive and is not committed. |

All paths are relative to the repository root (`NoTouch/`). Line numbers refer to the working tree on the document date and will drift as the code changes.

---

## 1. Purpose and scope

The evaluation measures how far NoTouch's estimated gaze position lands from known on-screen targets that the participant is asked to look at. It exists so that future pipeline changes can be compared with recorded numbers rather than visual impressions.

What it is:

- An on-device, debug-build-only routine that shows 32 fixation targets and logs every gaze inference made while they are shown.
- An offline Python analyzer that turns the logs into per-target, per-session, and per-mode metrics, plots, and a report.

What it is not:

- **Not ground truth.** A *target* is the point displayed to the participant and where they were *instructed* to look. No independent eye tracker measures where they actually looked. Every spatial result is a **target-referenced gaze error**.
- Not a pass/fail test. No acceptance threshold has been defined.
- Not a clinical or usability measurement, and not a measure of visual angle. Errors are in screen coordinates.

---

## 2. Evaluation protocol

Verified in `app/src/main/java/dev/gazedot/Evaluation.kt` (lines 6–36) and against the first session's `metadata.json`.

| Item | Value |
|---|---|
| Target grid | 4 × 4, normalized coordinates `{0.20, 0.40, 0.60, 0.80}` on both axes |
| Target IDs | `r{row}c{col}`, with the row from y and the column from x. For example, `r1c3` is (x 0.8, y 0.4). |
| Pass 1 order | Serpentine from top-left: row 0 left→right, row 1 right→left, row 2 left→right, row 3 right→left |
| Pass 2 order | Exact reverse of pass 1, so pass 2 starts with pass 1's last target |
| Presentations | 32 (16 per pass). `trial_index` runs 0–31 across both passes; `index_in_pass` runs 0–15. Both are 0-based. |
| Per presentation | 2,500 ms: **1,000 ms settling**, then **1,500 ms measurement** |
| Total duration | 80,000 ms (excluding calibration) |
| Clock | `SystemClock.uptimeMillis()`. Each sample is assigned to the target and phase active at its own timestamp. |
| Calibration | The existing 9-point calibration (targets at 0.15 / 0.50 / 0.85) must be completed first. Evaluation targets never coincide with calibration targets. |

Pass 1 order: `r0c0 r0c1 r0c2 r0c3 · r1c3 r1c2 r1c1 r1c0 · r2c0 r2c1 r2c2 r2c3 · r3c3 r3c2 r3c1 r3c0`

### 2.1 Modes

| Mode ID | Cursor during targets | Role |
|---|---|---|
| `blind_measurement` | Hidden | **Primary.** The participant cannot see, and therefore cannot chase, the estimated cursor. |
| `live_preview` | Shown | Secondary. A visible cursor can change where the participant looks. |

Results from the two modes are **never combined**. Each session records exactly one mode. In both modes, dwell-click is suppressed and no accessibility tap is dispatched (`TrackingService.kt` line 208).

### 2.2 Coordinate frame

Targets, calibration anchors, the model output, and the cursor all share one **display-normalized frame**, `[0, 1]` on each axis:
- x = 0 is the left edge and x = 1 the right edge;
- y = 0 is the top and y = 1 the bottom.

On Android API 30 and later, the basis is `WindowManager.maximumWindowMetrics`, the same basis calibration uses (`MainActivity.kt` lines 346 and 416). The basis width and height in pixels are recorded in each session's `metadata.json` under `geometry`.

---

## 3. What is measured and how

All formulas below were checked against `tools/gaze_eval/analyze_gaze_eval.py`.

### 3.1 Signs and units

| Quantity | Definition |
|---|---|
| `dx` | `predicted_x − target_x`. Positive means the prediction is **right** of the target. |
| `dy` | `predicted_y − target_y`. Positive means the prediction is **below** the target. |
| Error (normalized) | `sqrt(dx² + dy²)` |
| Error (pixels) | `sqrt((dx × basis_width_px)² + (dy × basis_height_px)²)`. Width and height are applied **separately**, because the screen is not square. |

- **Normalized units** are fractions of the screen basis, not degrees of gaze. No visual-angle conversion is made, because viewing distance and physical screen size are not recorded.
- **Pixels and normalized units rank differently.** Because x and y are scaled differently, a pixel metric is not a constant multiple of its normalized counterpart, and two streams can swap order between the two units.

### 3.2 Two prediction streams, kept separate

| Stream | CSV columns | Meaning | Role |
|---|---|---|---|
| Cursor | `cursor_x_norm`, `cursor_y_norm` | Position after the app's existing smoothing (a dead zone of 0.004, then adaptive exponential smoothing with α between 0.10 and 0.42; `TrackingService.kt` lines 198–201) | **Primary result** |
| Model | `model_x_norm`, `model_y_norm` | `GazeModel.predict` output before smoothing; already clamped to `[0, 1]` (`GazeMath.kt` line 23) | Diagnostic only |

The two streams always use the same set of valid samples and are never pooled together.

### 3.3 Which samples count

- **Spatial error and jitter** use only rows that meet all three conditions:
  - `phase == measurement`;
  - `prediction_valid == true`;
  - finite target, model, and cursor coordinates.
- **Settling samples** are excluded from accuracy and jitter. Their invalid attempts are still reported separately as settling dropout.
- **Invalid attempts:**
  - Their coordinates are never imputed, carried forward, or counted as spatial error.
  - They stay in the coverage denominator.
  - They are counted under the reason the app logged.

Logged invalid reasons (`EvaluationLog.kt`):

| Reason | What the pipeline knows |
|---|---|
| `landmarker_not_ready` | The face-landmark model was not initialized yet |
| `no_face` | No face detected |
| `features_unavailable` | `LandmarkFeatures.extract` returned null. One label covers three causes: an eye judged closed, an eye too small in the image, or too few landmarks. **It does not identify blinking by itself.** Do not relabel these rows as blinks. |
| `no_calibration_model` | No calibration model was present |
| `model_rejected` | `GazeModel.predict` returned null (head position/scale gate, or non-finite input) |
| `processing_error` | Exception in the frame path |
| `service_stopping` | The result arrived while the service was shutting down |

The analyzer adds one category of its own, `malformed_row`, for rows marked valid whose coordinates are missing or non-finite. These rows are reported as issues, never used spatially.

### 3.4 Per-presentation metrics

| Metric | Definition |
|---|---|
| Measurement attempts | Measurement-phase inference attempts logged for the presentation |
| Valid samples | Attempts meeting the conditions in §3.3 |
| Valid coverage | `valid samples / measurement attempts` |
| Valid sample rate | `valid samples / observed measurement-window seconds` (1.5 s for a fully shown presentation) |
| Mean, median, and p95 error | Over the presentation's valid samples (normalized and px) |
| X and Y bias | Mean `dx` and mean `dy` |
| Centroid error | Distance from the presentation's mean predicted position to the target |
| Jitter RMS | Normalized: `sqrt(mean((x − mean_x)² + (y − mean_y)²))`. Pixels: `sqrt(mean(((x − mean_x) × screen_width_px)² + ((y − mean_y) × screen_height_px)²))`, using the recorded `basis_width_px` and `basis_height_px`. Computed over the presentation's valid samples, it is the observed stability of the cursor during one target hold. It is not pure model or sensor noise. |

**Sufficiency rule** (analyzer defaults `DEFAULT_MIN_VALID_SAMPLES = 10` and `DEFAULT_MIN_VALID_COVERAGE = 0.5`, lines 46–47). A presentation is `eligible` only if all three hold:
- it was fully shown;
- it has **at least 10 valid samples**;
- its **valid coverage is at least 0.5**.

Otherwise it is `insufficient_data` or `incomplete_presentation`. Ineligible presentations stay in every output with a stated reason, but their spatial metrics are left empty (`null`).
- **Changing the thresholds:** both are command-line options (`--min-valid-samples`, `--min-valid-coverage`) and are recorded in every output.
- **Policy:** do not tune them after seeing results. If they change, rerun every compared version with the same values.

### 3.5 Session and mode summaries

| View | How it is computed | Use |
|---|---|---|
| **Trial-balanced** (primary) | Compute each eligible presentation's metric first, then weight presentations equally. The headline is the **mean of presentation mean errors**. Median and p95 are taken across the presentation means. Bias, centroid error, and jitter are means of the per-presentation values. | Main comparison number; a presentation with more frames does not dominate |
| **Pooled samples** (sensitivity) | Every valid measurement sample weighted equally, including samples from insufficient presentations | Checks whether weighting changes the picture |

- **Percentiles:** p95 is **nearest-rank**, the k-th smallest value with `k = ceil(0.95 × n)`, computed in integer arithmetic. The median is Python's `statistics.median` (mean of the two middle values when n is even).
- **Two different p95 values:**
  - The **p95 of presentation means** ranks 32 per-target averages.
  - The **pooled p95** ranks individual samples (n = 575 in the first session).
  - These answer different questions and should not be compared with each other.
- **Incomplete sessions** (`final_state = incomplete`) appear in all outputs with their end reason, but are excluded from mode summaries unless `--include-incomplete` is passed. Results that include them are labelled *diagnostic only*.

---

## 4. Modules and how to use them

| Module / file | What it does | How a teammate uses it |
|---|---|---|
| `app/src/main/java/dev/gazedot/Evaluation.kt` | Protocol: modes, phases, grid, pass order, timing, screen geometry, run state, and the debug-only entry check | Read to understand or change the protocol. Changing it changes what is being measured. |
| `app/src/main/java/dev/gazedot/MainActivity.kt` | Debug-only entry links on the home screen; `EvaluationView` draws targets; opens and finalizes the session log on every exit path: completion, Back, `onPause`, service stop, geometry rejection, logging failure (lines 49–80, 287–361) | In a **debug** build, start the camera, complete the 9-point calibration, then tap **평가 · 블라인드 측정** ("Evaluate · blind measurement") or **평가 · 라이브 미리보기** ("Evaluate · live preview") |
| `app/src/main/java/dev/gazedot/TrackingService.kt` | Observe-only hooks in `process()`. They count analyzer frames, record each inference attempt with its timestamp, inference duration, and invalid reason, and record model and cursor outputs. Also hides the cursor in blind mode and suppresses dwell taps during evaluation. | No direct use. The hooks do not change frame selection, inference, smoothing, or cursor behaviour. |
| `app/src/main/java/dev/gazedot/EvaluationLog.kt` | Local session logger (schema v1): `samples.csv`, `metadata.json`, counters, background writer thread, one-time finalization, loss accounting | No direct use. Its output is the analyzer's input. |
| `app/src/test/java/dev/gazedot/EvaluationTest.kt`, `EvaluationLogTest.kt` | JVM unit tests (9 and 14) for the protocol, timing boundaries, geometry, cleanup, CSV/JSON format, finalization, and loss accounting | `./gradlew :app:testDebugUnitTest`, with a JDK 17–23 set as `JAVA_HOME` |
| `tools/gaze_eval/analyze_gaze_eval.py` | Offline analyzer 1.1.1, Python standard library only. Reads `samples.csv` and `metadata.json`, validates them, writes `summary.json`, `presentations.csv`, `report.md`, and `plots/*.svg` (§4.3). Never modifies the session files and refuses an output folder inside one. | See §4.2 |
| `tools/gaze_eval/test_analyze_gaze_eval.py` | 37 `unittest` tests on **synthetic** sessions | `python -B -m unittest discover -s tools/gaze_eval -v` |

The analyzer **does not run the Android app** and needs no device. It only reads session files that were already exported.

### 4.1 Where sessions are stored and how to retrieve them

The app writes each session to a new, never-reused folder in app-private storage:

```
/data/data/dev.gazedot/files/evaluation/<session_id>/samples.csv
/data/data/dev.gazedot/files/evaluation/<session_id>/metadata.json
```

Session IDs look like `eval-<UTC yyyyMMddTHHmmssZ>-<8 hex>`.

`metadata.json` is first written at run start with `final_state: incomplete` and `end_reason: in_progress_or_interrupted`, then replaced exactly once when the run ends. A session cut short by a crash therefore stays marked incomplete.

Retrieval (debug build, USB debugging; from the README). In Windows PowerShell 5.1, use `cmd /c` for the redirect: PowerShell's own `>` corrupts binary output.

```powershell
adb shell run-as dev.gazedot ls files/evaluation
cmd /c "adb exec-out run-as dev.gazedot tar -cf - files/evaluation > evaluation.tar"
tar -xf evaluation.tar
```

Alternatively, use Android Studio **Device Explorer** → `/data/data/dev.gazedot/files/evaluation`.

### 4.2 Running the analyzer

Run from the repository root:

```powershell
# one session folder
python tools/gaze_eval/analyze_gaze_eval.py <session-folder> --out <new-output-folder> --source-label "<verified build identity, or UNVERIFIED ...>"
# a folder containing several sessions (searched recursively)
python tools/gaze_eval/analyze_gaze_eval.py <folder-of-sessions> --out <new-output-folder> --source-label "..."
```

- **Exit codes:** `0` means all sessions were analyzed; `1` means at least one session was rejected (outputs are still written, and the rejection is listed in them); `2` means a usage error.
- **Source label:** always supply `--source-label`. Sessions do not record the build revision (§5). The label appears in the report text and `summary.json`, never inside a figure.

### 4.3 What the analyzer writes

| Output | Contents |
|---|---|
| `report.md` | Provenance label, how-to-read notes, a **Visual summary** section, then per-mode tables (trial-balanced and pooled, cursor and model, normalized and px), session and counter tables, and definitions |
| `plots/summary_<session_id>.svg` | **Report-ready figure, one per session, embedded at the top of the report.** It has four cards (trial-balanced mean cursor error, p95 of presentation means, mean horizontal bias with direction, valid measurement predictions as valid / attempts), the number of eligible presentations, and two panels. The **target-position map** is drawn to the recorded screen aspect, with each target linked to its average cursor position: the equal-weight mean of its two presentation means. The **presentation-error comparison** shows pass 1 and pass 2 for each target, with trial-balanced mean, median, and p95 lines. It contains no build provenance. |
| `plots/<mode>_target_map.svg`, `plots/<mode>_error_distribution.svg` | **Supplementary** plots, pooled across the included sessions of a mode and embedded below that mode's tables. The histogram weights camera frames, not presentations. |
| `plots/coverage_<session_id>.svg` | Per-presentation coverage chart, kept as an **internal diagnostic**. It is **not embedded** in `report.md`; coverage is reported in the tables and on the summary figure's card. |
| `summary.json`, `presentations.csv` | Machine-readable results; one CSV row per presentation |

---

## 5. Build provenance of the first session

APK identity and analyzer identity are separate questions and are treated separately below.

### 5.1 APK provenance

| Category | Evidence | Finding |
|---|---|---|
| **Recorded in session metadata** | `metadata.json` → `app` | `version_name` `0.1.0`, `version_code` `1`, `source_revision` **null**, `working_tree_dirty` **null**. Version 0.1.0 / 1 is the fixed value in `app/build.gradle.kts` for every build, so it does not identify a revision. |
| | `metadata.json` → `device` | `samsung`, `SM-S918N`, API 36 |
| **Established from Git** (checked 2026-10-23, before this work was committed) | `git log --all -- '*EvaluationLog.kt' '*Evaluation.kt'` returned nothing | At the time, no commit on any local branch (`main`, `feature/gaze-dwell-click`, `feature/gaze-accuracy-evaluation`) or remote branch (`origin/main`, `origin/feature/gaze-dwell-click`, `origin/feature/home-screen`, `origin/codex/gpt54-gaze-context`) contained the evaluation runner or logger |
| | `git grep` of the metadata field names on all refs | `presentation_target_ids`, `frames_cadence_skipped`, `attempts_unrecorded`, `blind_measurement`, and `features_unavailable` appeared in no committed `.kt` file; they existed only in the uncommitted working tree |
| | `git rev-parse HEAD` | `006d2bc` (the `feature/gaze-dwell-click` baseline), which did **not** contain the code that writes this session format |
| **Established from local build artifacts** | `app/build/outputs/apk/debug/app-debug.apk`, built 2026-10-23 09:07:48 KST (00:07:48 UTC) | Its compiled code (`classes*.dex`) contains the logger's metadata keys and the `EvaluationSession` class |
| | `app/build/intermediates/apk/debug/app-debug.apk`, built 09:31:21 KST (00:31:21 UTC; the path Android Studio installs from) | Same: it contains the logger keys and class |
| | Newest Android source edit: `MainActivity.kt` at 09:05:16 KST | Both local APKs were compiled after the last Android source edit. The session ran at 14:44:14 KST (05:44:14 UTC), after both builds. |
| **Unknown** | No build ID, hash, or revision exists in the session | Which APK was installed on the phone; whether it was built on this machine; the exact source snapshot; whether the working tree was dirty, and how |

**Conclusion.**

1. **Established (high confidence):** the APK was built from **uncommitted** code containing the evaluation runner and logger. The session's file format exists in no commit.
   - **Consequence:** this session is **not** a strict-`main` result, and it cannot be attributed to `feature/gaze-dwell-click`, `feature/gaze-accuracy-evaluation`, or any other branch.
2. **Plausible but unverified (medium confidence):** the installed APK was one of the two debug builds made on this machine on 2026-10-23, from the Android sources as they stand in the current working tree.
   - **Why it is plausible:** both APKs contain the logger, and both were built after the last Android source edit and before the session.
   - **Why it stays unverified:** nothing in the session links it to either file, and a build from another machine or another working-tree state cannot be ruled out.
3. **Unknown:** the exact source revision.

**Label every result from this session as "build unverified".**

The evaluation code was committed **after** this session was recorded, together with this document. That later commit does not establish the APK's source: the session records no revision, and the installed APK was built from an uncommitted working tree that was not hashed.

### 5.2 Analyzer provenance

The current analysis in `test_results_visual_summary/` was produced by `tools/gaze_eval/analyze_gaze_eval.py` version 1.1.1, the version committed with this document.
- **Reproducibility:** rerunning it on `test_results/` reproduced all seven output files (`report.md`, `summary.json`, `presentations.csv`, and four SVGs) **byte for byte**.
- **Tests:** all 37 analyzer tests passed.

The first analysis, made with analyzer 1.0.0, is kept only as the local archive `test_results_analysis_v1_0_0_archive/` and is not committed.
- **Metrics are identical:** its `presentations.csv` is byte-identical to the current one, and its `summary.json` differs only in `tool_version`.
- **Differences are in presentation only:** it lacks the summary figure, and its report still embeds the coverage chart.

---

## 6. First real session: `eval-20261023T054414Z-cbec17a1`

Files: `test_results/metadata.json` and `test_results/samples.csv`, left unmodified.

| File | SHA-256 of the local copy checked on 2026-10-23 |
|---|---|
| `metadata.json` | `78dcf74a15bda9602211e0207c20cf5e55fe7491a0f15652672954fd9aef421c` |
| `samples.csv` | `f3b95e5daed57aea729915121ebe530a1a853b63c9ce66d234f3341a2e843311` |

**Scope of these hashes:**
- **What they cover:** the local copies in `test_results/` as checked before analysis and re-checked afterwards.
- **Line endings:** those copies use LF only, which is how the app's logger writes them. An earlier review note reporting CRLF line endings was a tool artifact and was wrong.
- **Not compared with the phone:** no on-device hash was taken, so these copies have not been compared byte for byte with the files on the phone.
- **Checkouts may differ:** the repository normalizes text line endings (`* text=auto`), so a checkout made with `core.autocrlf=true` on Windows may contain CRLF and give different hashes. The analysis does not depend on this: CRLF and LF copies produced byte-identical analyzer outputs.

**Analysis outputs (current):** `test_results_visual_summary/report.md`, `summary.json`, `presentations.csv`, and `plots/` (summary figure, supplementary plots, diagnostic coverage chart; see §4.3).

### 6.1 Session identity and data integrity

| Item | Value | Verified by |
|---|---|---|
| Mode | `blind_measurement` | metadata, all 965 rows |
| Device / screen basis | samsung `SM-S918N`, API 36; **1080 × 2316 px** (`max_window_metrics`; evaluation view and overlay are the same size) | metadata |
| Start (UTC) | 2026-10-23 05:44:14.125 | metadata |
| Completion | `final_state: complete`, `end_reason: completed`; 80,014 ms against 80,000 ms planned; **32/32 presentations** | metadata |
| CSV rows | **965** data rows; exact 20-column schema-v1 header; `attempt_seq` 0–964 with no gaps or duplicates | raw CSV |
| Logging loss | `rows_written` 965 = CSV rows; `rows_lost_total` 0; `attempts_unrecorded` 0; no write error | metadata vs raw CSV |
| Frame accounting | 1,918 analyzer frames = 953 skipped by the cadence limiter + 0 ignored + 965 inference attempts | metadata |
| Row consistency | In all 965 rows, phase and trial agree with the sample timestamp; pass, index, target ID, and target coordinates agree with the protocol | raw CSV |
| Invalid attempts | 12 in total, all `features_unavailable`: **5 of 580** in measurement windows (trials 7, 16, 17) and **7 of 385** in settling | raw CSV, metadata |

### 6.2 Coverage and eligibility

| Item | Value |
|---|---|
| Measurement windows | **575 valid / 580 attempts = 99.1 %** coverage; **≈ 12.0 valid predictions per second** (575 / 48 s) |
| Eligible presentations (defaults: 10 samples, 0.5 coverage) | **32 / 32**; none insufficient or incomplete |
| Valid samples per presentation | 16–19 |
| Lowest presentation coverage | 88.9 % |

### 6.3 Spatial results (blind measurement)

**Trial-balanced** (each of the 32 eligible presentations weighted equally):

| Metric | Cursor (primary) normalized | Cursor px | Model (diagnostic) normalized | Model px |
|---|---|---|---|---|
| Mean of presentation mean errors | **0.1107** | **168.8** | 0.1187 | 179.0 |
| Median of presentation mean errors | 0.1191 | 170.4 | 0.1188 | 171.8 |
| p95 of presentation mean errors (k = 31 of 32) | 0.1733 | 295.6 | 0.1984 | 348.4 |
| X bias (+ right) | **+0.0750** | +81.0 | +0.0825 | +89.1 |
| Y bias (+ down) | −0.0077 | −17.9 | −0.0174 | −40.4 |
| Mean centroid error | 0.0964 | 139.2 | 0.1027 | 146.7 |
| Mean jitter RMS | 0.0488 | 93.6 | 0.0588 | 111.4 |

**Pooled-sample sensitivity view** (575 valid measurement samples, each weighted equally):

| Metric | Cursor normalized | Cursor px | Model normalized | Model px |
|---|---|---|---|---|
| Mean error | 0.1106 | 168.2 | 0.1186 | 178.5 |
| Median error | 0.1061 | 150.8 | 0.1137 | 159.5 |
| p95 error (k = 547 of 575 samples) | 0.1957 | 359.9 | 0.2039 | 302.1 |

The cursor is lower than the model on most metrics, but not all:
- **Median of presentation mean errors:** essentially equal, and slightly higher for the cursor in normalized units (0.1191 vs 0.1188; 170.4 vs 171.8 px).
- **Pooled p95:** higher for the cursor in **pixels** (359.9 vs 302.1), although lower in normalized units (0.1957 vs 0.2039). This is the anisotropic pixel conversion described in §3.1.

Smoothing does not improve every metric in every unit.

**Scale.** The grid spacing between neighbouring targets is 0.2 normalized on both axes, which is **216 px horizontally and 463.2 px vertically** on this 1080 × 2316 basis. Measured against that spacing:
- the X bias (+81.0 px) is about 38 % of the horizontal spacing;
- the Y bias (−17.9 px) is about 4 % of the vertical spacing;
- the trial-balanced mean error (0.1107 normalized) is about 55 % of the normalized spacing.

### 6.4 Bottom-edge values (y = 1.0)

`GazeModel.predict` bounds its output to `[0, 1]` (`GazeMath.kt` line 23), and the cursor is smoothed from that bounded value. The log records only these post-bound values. In this session, every coordinate observed exactly at a bound is at the **bottom edge, `y = 1.0`**. No value was observed at 0, and none on the x axis.

| Phase | Stream | Values at y = 1.0 | Trials | Target rows (y) |
|---|---|---|---|---|
| Measurement | Model | 13 | 9, 14, 16, 17, 20, 25 | 0.4, 0.6, 0.8 |
| Measurement | Cursor | 4 | 14, 16, 17 | 0.8 only |
| Settling (excluded) | Model | 8 | 5, 14, 23, 27, 30 | 0.2–0.8 |
| Settling (excluded) | Cursor | 4 | 5, 14, 23, 30 | 0.2–0.8 |

- **Where:** the measurement-phase y = 1.0 values fall in 13 distinct sample rows across 6 presentations.
- **Cursor (primary):** the cursor coordinates are the bounded on-screen cursor positions. Their recorded distance to the displayed target is the **actual screen-space cursor error**, not a lower bound. A cursor at y = 1.0 was at the bottom edge of the screen.
- **Model (diagnostic):** model values at exactly y = 1.0 are consistent with **saturation at the bottom bound**. However, the log contains no pre-bound value and no flag proving that a given value was clipped. Whether the unbounded model output went further below the screen, and by how much, **cannot be recovered from this session**.
- **Overlap with jitter:** the four presentations with the highest cursor jitter (trials 25, 9, 20, 16) are all among the six with y = 1.0 values in the measurement phase.
- **Correction to an earlier summary:** model values reached y = 1.0 for targets at y = 0.4 and 0.6, but cursor values did so only for targets at y = 0.8.

---

## 7. Informal interpretation (for teammates new to this)

- **The logging worked.** The run completed, nothing was lost, every count matches, and 99 % of measurement-window attempts produced a valid prediction. The data can be trusted as a record of what the app output.
- **The error was substantial.** On average the cursor sat about 0.11 normalized units (about 169 px) from the target. In normalized coordinates, that is roughly half the 0.2 spacing between neighbouring targets. In pixels the spacing differs by axis, 216 px horizontally and 463.2 px vertically, so the half-spacing comparison holds only in normalized units. Most of the error was systematic (centroid error 0.096) rather than wobble (jitter 0.049).
- **The direction was consistent.** There was a clear rightward bias (+0.075, about 81 px), almost no vertical bias on average, and occasional samples at the bottom edge of the screen (y = 1.0). For the cursor, those are real on-screen positions and their errors are as recorded. For the model output, how far beyond the bottom edge the unbounded prediction would have gone cannot be recovered from this session.
- **Smoothing looked helpful here, with caveats.** Compared with the raw model output, it lowered the mean error, the p95 of presentation means, and jitter. It did not lower every metric: the median of presentation means is essentially unchanged, and the pooled p95 in pixels is higher. One run cannot show that it reliably helps.
- **This is one participant, one phone, one calibration, one session.** It says nothing yet about repeatability, other people or devices, or usability, and it is not a clinical measure.
- **It is not a `main` baseline.** The build revision is unknown (§5).
- **No pass/fail verdict and no root cause.** No accuracy threshold has been agreed, so this run neither passes nor fails. One run is not enough to diagnose why the bias exists.

---

## 8. Limitations and requirements for a comparable baseline

Limitations of the current setup:

- No independent measure of where the participant actually looked.
- Build revision, calibration fit quality, viewing distance, and lighting are not recorded in the session.
- Model output is logged only after it is bounded to `[0, 1]`, and there is no clip flag. When the model saturates at a screen edge, the size of the unbounded prediction cannot be recovered. Cursor errors are unaffected: they are measured between real on-screen positions.
- `features_unavailable` cannot separate blinks from other causes.
- Pixel results depend on the device's screen basis and are not comparable across different screens without care.

Before any result is used as a baseline for comparing pipeline versions:

1. **Verified build identity.** Build from a known commit, and record the revision and whether the working tree was dirty at build time. Supply the same label to `--source-label`.
2. **Repeat runs.** Record at least three complete `blind_measurement` sessions for each compared build, each with a fresh calibration.
3. **Consistent conditions.** Use the same device, a fixed orientation, a stable phone mount, similar viewing distance and lighting, and the same participant where possible. Write these conditions down for each session.
4. **Fixed analysis settings.** Use the same analyzer version and default thresholds (10 samples, 0.5 coverage) for every compared build. Never change thresholds after seeing results.
5. **Separate reporting.** Report `live_preview` sessions separately, never mixed with blind results.
6. **Shared definitions.** Report trial-balanced cursor metrics as the primary comparison, with the pooled view and model diagnostics alongside, using the definitions in §3.

---

## Appendix: how this document was verified (2026-10-23)

- **Repository state:** `feature/gaze-accuracy-evaluation` at `006d2bc`; the evaluation, logger, analyzer, and README changes are uncommitted.
- **Session files:**
  - SHA-256 recorded before any analysis and re-checked afterwards; unchanged.
  - The raw CSV was validated against the metadata with a separate script that does not use the analyzer: header, row counts, attempt sequence, phase and trial against timestamps, protocol fields, coordinate completeness, frame accounting.
- **Metrics:**
  - Every value in §6.2–§6.4 was recomputed directly from the raw CSV with a script independent of the analyzer.
  - All matched the analyzer's saved outputs.
- **Analyzer output reproducibility:** rerunning analyzer 1.1.1 into a new folder reproduced every file in `test_results_visual_summary/` byte for byte, from both the LF session files and a CRLF copy.
- **Tests, rechecked at commit time:**
  - all 37 analyzer tests passed on Python 3.14.6;
  - Gradle `:app:testDebugUnitTest` passed 26/26 JVM tests;
  - `:app:lintDebug` reported 0 errors.
  - `:app:assembleDebug` was deliberately not rerun, so that the two local debug APKs cited in §5.1 stay unchanged. It last succeeded on 2026-10-23 during logger development.
- **Provenance:** checked with `git log --all`, `git grep` on all local and remote refs, local APK timestamps, and string checks of the compiled `.dex` code inside both local debug APKs.
