#!/usr/bin/env python3
"""Offline analysis of NoTouch gaze evaluation sessions (Stage 2 log schema v1).

Reads only each session's ``samples.csv`` and ``metadata.json``. Python standard library only;
no network access; deterministic output for the same inputs and arguments.

The displayed target is the *instructed* fixation point. There is no independent eye tracker, so
every spatial metric is a **target-referenced gaze error**, not a ground-truth eye-direction error.

Usage:
    python tools/gaze_eval/analyze_gaze_eval.py <session-dir | dir-of-sessions> --out <dir>
        [--source-label TEXT] [--min-valid-samples 10] [--min-valid-coverage 0.5]
        [--include-incomplete]
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

TOOL_VERSION = "1.1.1"
SUPPORTED_SCHEMA_VERSIONS = (1,)
MODES = ("blind_measurement", "live_preview")
PRIMARY_MODE = "blind_measurement"
STREAMS = ("cursor", "model")  # cursor = post-smoothing (primary); model = pre-smoothing (diagnostic)
KNOWN_INVALID_REASONS = (
    "landmarker_not_ready", "no_face", "features_unavailable", "no_calibration_model",
    "model_rejected", "processing_error", "service_stopping",
)
# Analysis-side category for rows the log itself marks inconsistent; never a logged reason.
MALFORMED_ROW = "malformed_row"
UNSPECIFIED_REASON = "unspecified"
CSV_COLUMNS = (
    "schema_version", "session_id", "attempt_seq", "sample_uptime_ms", "elapsed_ms", "mode", "phase",
    "pass_number", "trial_index", "index_in_pass", "target_id", "target_x_norm", "target_y_norm",
    "prediction_valid", "invalid_reason", "model_x_norm", "model_y_norm", "cursor_x_norm", "cursor_y_norm",
    "inference_duration_ms",
)
DEFAULT_MIN_VALID_SAMPLES = 10
DEFAULT_MIN_VALID_COVERAGE = 0.5
MAX_ISSUES_LISTED = 50
TARGET_ID = re.compile(r"^r(\d+)c(\d+)$")

DEFINITIONS = {
    "reference": "Targets are instructed fixation points, not independently measured eye direction.",
    "coordinate_frame": "Gaze-model display-normalized [0,1] frame shared by calibration, targets, model and cursor.",
    "dx_dy": "dx = prediction_x - target_x (positive = right of target); dy = prediction_y - target_y (positive = below).",
    "radial_error": "sqrt(dx^2 + dy^2); pixels use dx_px = dx_norm * basis_width_px, dy_px = dy_norm * basis_height_px.",
    "valid_sample": "phase == measurement, prediction_valid == true, and finite target, model and cursor coordinates.",
    "invalid_attempts": "Excluded from spatial error (never imputed or carried forward); counted in coverage and dropout.",
    "features_unavailable": "Logged reason combining eye closed, eye too small and too few landmarks; not identified as blinking.",
    "valid_coverage": "valid samples / measurement-phase inference attempts logged for the presentation.",
    "valid_sample_rate": "valid samples / observed measurement-window seconds.",
    "sufficiency": "A presentation is eligible if fully shown, valid samples >= min_valid_samples and valid coverage >= min_valid_coverage.",
    "jitter_rms": "sqrt(mean((x - mean_x)^2 + (y - mean_y)^2)) of valid samples in one presentation: observed cursor stability during a target hold, not pure model or sensor noise.",
    "centroid_error": "Distance from the presentation's mean predicted position to the target.",
    "trial_balanced": "Each eligible presentation's metric is computed first; presentations are then weighted equally.",
    "pooled": "Sensitivity view: every valid measurement sample weighted equally, including insufficient presentations.",
    "median": "statistics.median: mean of the two middle values for an even count.",
    "p95": "Nearest-rank: the k-th smallest value with k = ceil(0.95 * n), computed in integer arithmetic.",
}


class SessionError(Exception):
    """A session that cannot be analyzed at all (missing files, wrong schema, inconsistent identity)."""


# ---------------------------------------------------------------- statistics

def mean(values):
    return math.fsum(values) / len(values) if values else None


def median(values):
    return statistics.median(values) if values else None


def nearest_rank(values, percent: int):
    """Nearest-rank percentile: k-th smallest value, k = ceil(percent / 100 * n)."""
    if not values:
        return None
    ordered = sorted(values)
    k = max(1, (percent * len(ordered) + 99) // 100)
    return ordered[k - 1]


# ---------------------------------------------------------------- parsing

@dataclass
class Sample:
    seq: int
    uptime_ms: int
    phase: str
    trial_index: int
    valid: bool
    invalid_reason: str | None
    target: tuple[float, float]
    cursor: tuple[float, float] | None
    model: tuple[float, float] | None


@dataclass
class Session:
    path: Path
    label: str
    metadata: dict
    samples: list[Sample] = field(default_factory=list)
    csv_rows: int = 0
    issues: list[str] = field(default_factory=list)
    issue_count: int = 0
    unassigned_rows: int = 0

    def issue(self, message: str) -> None:
        self.issue_count += 1
        if len(self.issues) < MAX_ISSUES_LISTED:
            self.issues.append(message)


def _nullable_float(cell: str | None) -> float | None:
    if cell is None or cell == "":
        return None
    value = float(cell)
    if not math.isfinite(value):
        raise ValueError(f"non-finite value {cell!r}")
    return value


def _require(mapping, key, kind, where="metadata"):
    if not isinstance(mapping, dict) or key not in mapping:
        raise SessionError(f"{where}: missing required field '{key}'")
    value = mapping[key]
    if kind is int:
        ok = isinstance(value, int) and not isinstance(value, bool)
    elif kind is float:
        ok = isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
    else:
        ok = isinstance(value, kind)
    if not ok:
        raise SessionError(f"{where}: field '{key}' has invalid value {value!r}")
    return value


def validate_metadata(meta) -> dict:
    """Checks the fields the analysis depends on; raises SessionError if any is unusable."""
    if not isinstance(meta, dict):
        raise SessionError("metadata.json is not a JSON object")
    version = _require(meta, "schema_version", int)
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise SessionError(f"unsupported schema_version {version} (supported: {list(SUPPORTED_SCHEMA_VERSIONS)})")
    _require(meta, "session_id", str)
    if _require(meta, "mode", str) not in MODES:
        raise SessionError(f"metadata: unknown mode {meta['mode']!r}")
    if _require(meta, "final_state", str) not in ("complete", "incomplete"):
        raise SessionError(f"metadata: unknown final_state {meta['final_state']!r}")
    _require(meta, "end_reason", str)
    _require(meta, "start_uptime_ms", int)
    protocol = _require(meta, "protocol", dict)
    grid = _require(protocol, "grid_norm", list, "metadata.protocol")
    if not grid or not all(isinstance(g, (int, float)) and math.isfinite(g) for g in grid):
        raise SessionError("metadata.protocol: grid_norm must be a list of finite numbers")
    for key in ("target_ms", "settling_ms", "measurement_ms", "presentations_planned"):
        if _require(protocol, key, int, "metadata.protocol") <= 0:
            raise SessionError(f"metadata.protocol: {key} must be positive")
    if protocol["settling_ms"] + protocol["measurement_ms"] != protocol["target_ms"]:
        raise SessionError("metadata.protocol: settling_ms + measurement_ms != target_ms")
    planned = protocol["presentations_planned"]
    ids = _require(protocol, "presentation_target_ids", list, "metadata.protocol")
    if len(ids) != planned or planned % 2:
        raise SessionError("metadata.protocol: presentation_target_ids must list an even number of planned presentations")
    for target_id in ids:
        target_xy(target_id, grid)
    completed = protocol.get("presentations_completed")
    if completed is not None and (not isinstance(completed, int) or isinstance(completed, bool) or not 0 <= completed <= planned):
        raise SessionError(f"metadata.protocol: invalid presentations_completed {completed!r}")
    return meta


def target_xy(target_id, grid) -> tuple[float, float]:
    match = TARGET_ID.match(target_id) if isinstance(target_id, str) else None
    if not match or int(match.group(1)) >= len(grid) or int(match.group(2)) >= len(grid):
        raise SessionError(f"metadata.protocol: unrecognized target id {target_id!r}")
    return float(grid[int(match.group(2))]), float(grid[int(match.group(1))])


def pixel_basis(meta) -> tuple[tuple[int, int] | None, str | None]:
    """Exact normalized-coordinate basis from session geometry, or a reason it cannot be trusted."""
    geometry = meta.get("geometry")
    if not isinstance(geometry, dict):
        return None, "metadata has no geometry object"
    w, h = geometry.get("basis_width_px"), geometry.get("basis_height_px")
    if not all(isinstance(v, int) and not isinstance(v, bool) and v > 0 for v in (w, h)):
        return None, f"geometry basis_width_px/basis_height_px not positive integers ({w!r}, {h!r})"
    return (w, h), None


def load_session(path: Path, root: Path) -> Session:
    label = path.relative_to(root).as_posix() if path != root else path.name
    meta_file, csv_file = path / "metadata.json", path / "samples.csv"
    missing = [f.name for f in (meta_file, csv_file) if not f.is_file()]
    if missing:
        raise SessionError(f"missing {', '.join(missing)}")
    try:
        meta = json.loads(meta_file.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise SessionError(f"metadata.json unreadable: {e}") from e
    session = Session(path, label, validate_metadata(meta))
    _read_samples(session, csv_file.read_text(encoding="utf-8"))
    return session


def _read_samples(session: Session, text: str) -> None:
    meta = session.metadata
    protocol = meta["protocol"]
    planned, per_pass = protocol["presentations_planned"], protocol["presentations_planned"] // 2
    ids, grid = protocol["presentation_target_ids"], protocol["grid_norm"]
    reader = csv.reader(io.StringIO(text, newline=""))
    header = next(reader, None)
    if header is None:
        raise SessionError("samples.csv is empty (no header)")
    missing = [c for c in CSV_COLUMNS if c not in header]
    if missing:
        raise SessionError(f"samples.csv: missing columns {missing}")
    extra = [c for c in header if c not in CSV_COLUMNS]
    if extra:
        session.issue(f"samples.csv: ignoring unknown columns {extra}")
    col = {name: header.index(name) for name in CSV_COLUMNS}
    for line_no, cells in enumerate(reader, start=2):
        if not cells:
            continue
        session.csv_rows += 1
        if len(cells) != len(header):
            session.unassigned_rows += 1
            session.issue(f"line {line_no}: {len(cells)} cells, expected {len(header)}")
            continue
        row = {name: cells[i] for name, i in col.items()}
        if row["session_id"] != meta["session_id"]:
            raise SessionError(f"line {line_no}: session_id {row['session_id']!r} does not match metadata")
        if row["mode"] != meta["mode"]:
            raise SessionError(f"line {line_no}: mode {row['mode']!r} does not match metadata")
        try:
            if int(row["schema_version"]) != meta["schema_version"]:
                raise ValueError(f"schema_version {row['schema_version']!r} differs from metadata")
            seq, uptime, trial = int(row["attempt_seq"]), int(row["sample_uptime_ms"]), int(row["trial_index"])
            if row["phase"] not in ("settling", "measurement"):
                raise ValueError(f"unknown phase {row['phase']!r}")
            if not 0 <= trial < planned:
                raise ValueError(f"trial_index {trial} outside 0..{planned - 1}")
            if (int(row["pass_number"]), int(row["index_in_pass"]), row["target_id"]) != (trial // per_pass + 1, trial % per_pass, ids[trial]):
                raise ValueError(f"pass/index/target fields disagree with protocol for trial {trial}")
            target = (_nullable_float(row["target_x_norm"]), _nullable_float(row["target_y_norm"]))
            if target != target_xy(ids[trial], grid):
                raise ValueError(f"target coordinates {target} disagree with {ids[trial]}")
            if row["prediction_valid"] not in ("true", "false"):
                raise ValueError(f"prediction_valid {row['prediction_valid']!r} is not true/false")
        except (ValueError, SessionError) as e:
            session.unassigned_rows += 1
            session.issue(f"line {line_no}: {e}")
            continue
        session.samples.append(_classify(session, line_no, row, seq, uptime, trial, target))


def _classify(session, line_no, row, seq, uptime, trial, target) -> Sample:
    """Valid rows need every coordinate; invalid rows keep their logged reason and drop coordinates."""
    reason = row["invalid_reason"] or None
    if row["prediction_valid"] == "false":
        if reason is None:
            reason = UNSPECIFIED_REASON
            session.issue(f"line {line_no}: invalid row without invalid_reason")
        elif reason not in KNOWN_INVALID_REASONS:
            session.issue(f"line {line_no}: invalid_reason {reason!r} is not defined in schema v1 (kept verbatim)")
        if any(row[c] for c in ("model_x_norm", "model_y_norm", "cursor_x_norm", "cursor_y_norm")):
            session.issue(f"line {line_no}: invalid row carries coordinates (ignored)")
        return Sample(seq, uptime, row["phase"], trial, False, reason, target, None, None)
    try:
        cursor = (_nullable_float(row["cursor_x_norm"]), _nullable_float(row["cursor_y_norm"]))
        model = (_nullable_float(row["model_x_norm"]), _nullable_float(row["model_y_norm"]))
        if None in cursor or None in model:
            raise ValueError("missing model or cursor coordinate")
    except ValueError as e:
        session.issue(f"line {line_no}: valid row unusable ({e}); counted as {MALFORMED_ROW}")
        return Sample(seq, uptime, row["phase"], trial, False, MALFORMED_ROW, target, None, None)
    if reason is not None:
        session.issue(f"line {line_no}: valid row also has invalid_reason {reason!r} (ignored)")
    return Sample(seq, uptime, row["phase"], trial, True, None, target, cursor, model)


# ---------------------------------------------------------------- per-presentation analysis

def spatial_metrics(samples: list[Sample], stream: str, basis) -> dict:
    """Target-referenced error, bias, centroid and jitter for one stream of valid samples."""
    points = [getattr(s, stream) for s in samples]
    tx, ty = samples[0].target
    dx = [p[0] - tx for p in points]
    dy = [p[1] - ty for p in points]
    radial = [math.hypot(a, b) for a, b in zip(dx, dy)]
    mx, my = mean([p[0] for p in points]), mean([p[1] for p in points])
    spread = [(p[0] - mx, p[1] - my) for p in points]

    def pair(norm, px):
        return {"norm": norm, "px": px if basis else None}

    w, h = basis if basis else (0, 0)
    radial_px = [math.hypot(a * w, b * h) for a, b in zip(dx, dy)]
    return {
        "n_samples": len(points),
        "mean_error": pair(mean(radial), mean(radial_px)),
        "median_error": pair(median(radial), median(radial_px)),
        "p95_error": pair(nearest_rank(radial, 95), nearest_rank(radial_px, 95)),
        "bias_x": pair(mean(dx), mean(dx) * w),
        "bias_y": pair(mean(dy), mean(dy) * h),
        "centroid": {"x_norm": mx, "y_norm": my},
        "median_position": {"x_norm": median([p[0] for p in points]), "y_norm": median([p[1] for p in points])},
        "centroid_error": pair(math.hypot(mx - tx, my - ty), math.hypot((mx - tx) * w, (my - ty) * h)),
        "jitter_rms": pair(math.sqrt(mean([a * a + b * b for a, b in spread])),
                           math.sqrt(mean([(a * w) ** 2 + (b * h) ** 2 for a, b in spread]))),
    }


def _sample_errors(samples: list[Sample], stream: str, basis) -> list[dict]:
    """Per-sample errors for pooled views; jitter terms are relative to the presentation's own centroid."""
    if not samples:
        return []
    w, h = basis if basis else (0, 0)
    points = [getattr(s, stream) for s in samples]
    mx, my = mean([p[0] for p in points]), mean([p[1] for p in points])
    tx, ty = samples[0].target
    out = []
    for x, y in points:
        dx, dy = x - tx, y - ty
        out.append({"dx": dx, "dy": dy, "r": math.hypot(dx, dy), "r_px": math.hypot(dx * w, dy * h),
                    "dx_px": dx * w, "dy_px": dy * h,
                    "sq": (x - mx) ** 2 + (y - my) ** 2, "sq_px": ((x - mx) * w) ** 2 + ((y - my) * h) ** 2,
                    "px": basis is not None})
    return out


@dataclass
class Thresholds:
    min_valid_samples: int = DEFAULT_MIN_VALID_SAMPLES
    min_valid_coverage: float = DEFAULT_MIN_VALID_COVERAGE

    def as_dict(self):
        return {"min_valid_samples": self.min_valid_samples, "min_valid_coverage": self.min_valid_coverage}


def _counts(samples: list[Sample]) -> dict:
    invalid: dict[str, int] = {}
    for s in samples:
        if not s.valid:
            invalid[s.invalid_reason] = invalid.get(s.invalid_reason, 0) + 1
    ordered = {r: invalid.pop(r, 0) for r in KNOWN_INVALID_REASONS}
    ordered.update(sorted(invalid.items()))
    valid = sum(s.valid for s in samples)
    return {"attempts": len(samples), "valid_samples": valid, "invalid_by_reason": ordered}


def analyze_session(session: Session, thresholds: Thresholds) -> dict:
    meta, protocol = session.metadata, session.metadata["protocol"]
    basis, basis_reason = pixel_basis(meta)
    planned, per_pass = protocol["presentations_planned"], protocol["presentations_planned"] // 2
    start, target_ms = meta["start_uptime_ms"], protocol["target_ms"]
    settling_ms, measurement_ms = protocol["settling_ms"], protocol["measurement_ms"]
    completed = protocol.get("presentations_completed")
    end_uptime = meta.get("end_uptime_ms") if isinstance(meta.get("end_uptime_ms"), int) else None
    last_sample = max((s.uptime_ms for s in session.samples), default=None)
    if completed is None:
        # Interrupted sessions never recorded completion; only count presentations a later sample proves finished.
        completed = 0 if last_sample is None else max(0, min(planned, (last_sample - start) // target_ms))
        completion_source = "inferred_from_last_logged_sample"
    else:
        completion_source = "metadata"
    observed_until = end_uptime if end_uptime is not None else last_sample

    by_trial: dict[int, list[Sample]] = {i: [] for i in range(planned)}
    for s in session.samples:
        by_trial[s.trial_index].append(s)

    presentations, sample_errors = [], {stream: [] for stream in STREAMS}
    for trial in range(planned):
        rows = by_trial[trial]
        measurement = [s for s in rows if s.phase == "measurement"]
        settling = [s for s in rows if s.phase == "settling"]
        valid = [s for s in measurement if s.valid]
        m_counts, s_counts = _counts(measurement), _counts(settling)
        complete = trial < completed
        window_start = start + trial * target_ms + settling_ms
        if complete:
            window_s = measurement_ms / 1000
        elif observed_until is not None:
            window_s = min(max(observed_until - window_start, 0), measurement_ms) / 1000
        else:
            window_s = 0.0
        coverage = len(valid) / len(measurement) if measurement else None
        if not complete:
            status, reason = "incomplete_presentation", "presentation was not fully shown before the session ended"
        elif not measurement:
            status, reason = "insufficient_data", "no measurement-phase inference attempts logged"
        elif len(valid) < thresholds.min_valid_samples:
            status, reason = "insufficient_data", f"{len(valid)} valid samples < min_valid_samples {thresholds.min_valid_samples}"
        elif coverage < thresholds.min_valid_coverage:
            status, reason = "insufficient_data", f"valid coverage {coverage:.3f} < min_valid_coverage {thresholds.min_valid_coverage}"
        else:
            status, reason = "eligible", None
        target_id = protocol["presentation_target_ids"][trial]
        tx, ty = target_xy(target_id, protocol["grid_norm"])
        p = {
            "trial_index": trial, "pass_number": trial // per_pass + 1, "index_in_pass": trial % per_pass,
            "target_id": target_id, "target_x_norm": tx, "target_y_norm": ty,
            "presentation_complete": complete, "measurement_window_s": window_s,
            "measurement": {**m_counts, "valid_coverage": coverage,
                            "valid_samples_per_s": len(valid) / window_s if window_s > 0 else None},
            "settling": s_counts,
            "status": status, "status_reason": reason,
            "spatial_unavailable_reason": None if status == "eligible" else f"{status}: {reason}",
        }
        for stream in STREAMS:
            p[stream] = spatial_metrics(valid, stream, basis) if status == "eligible" else None
            sample_errors[stream].extend({**e, "eligible": status == "eligible"} for e in _sample_errors(valid, stream, basis))
        presentations.append(p)

    measured = [p["measurement"] for p in presentations]
    attempts = sum(m["attempts"] for m in measured)
    valid_total = sum(m["valid_samples"] for m in measured)
    window_total = sum(p["measurement_window_s"] for p in presentations)
    eligible = [p for p in presentations if p["status"] == "eligible"]
    counters = meta.get("counters") if isinstance(meta.get("counters"), dict) else None
    logging = meta.get("logging") if isinstance(meta.get("logging"), dict) else None
    if logging and isinstance(logging.get("rows_written"), int) and logging["rows_written"] != session.csv_rows:
        session.issue(f"samples.csv has {session.csv_rows} rows but metadata logging.rows_written is {logging['rows_written']}")
    result = {
        "session_id": meta["session_id"], "path": session.label, "mode": meta["mode"],
        "final_state": meta["final_state"], "end_reason": meta["end_reason"],
        "start_wall_time_utc": meta.get("start_wall_time_utc"),
        "device": meta.get("device"), "app": meta.get("app"),
        "pixel_basis": {"width_px": basis[0], "height_px": basis[1], "basis": meta["geometry"].get("basis")} if basis else None,
        "pixel_unavailable_reason": basis_reason,
        "presentations_planned": planned,
        "presentations_completed": completed, "presentations_completed_source": completion_source,
        "presentation_status_counts": {s: sum(p["status"] == s for p in presentations)
                                       for s in ("eligible", "insufficient_data", "incomplete_presentation")},
        "measurement": {
            "attempts": attempts, "valid_samples": valid_total,
            "valid_coverage": valid_total / attempts if attempts else None,
            "observed_window_s": window_total,
            "valid_samples_per_s": valid_total / window_total if window_total > 0 else None,
            "invalid_by_reason": _merge_counts(m["invalid_by_reason"] for m in measured),
        },
        "settling_dropout": {
            "attempts": sum(p["settling"]["attempts"] for p in presentations),
            "valid": sum(p["settling"]["valid_samples"] for p in presentations),
            "invalid_by_reason": _merge_counts(p["settling"]["invalid_by_reason"] for p in presentations),
        },
        "pipeline_counters": counters,
        "logging": logging,
        "csv_rows": session.csv_rows,
        "unassigned_rows": session.unassigned_rows,
        "issue_count": session.issue_count,
        "issues": session.issues,
        "trial_balanced": {s: trial_balanced(eligible, s) for s in STREAMS},
        "pooled_sensitivity": {s: pooled(sample_errors[s]) for s in STREAMS},
        "presentations": presentations,
    }
    result["_sample_errors"] = sample_errors  # internal, stripped before JSON output
    result["_edge_values"] = edge_value_counts([s for s in session.samples if s.phase == "measurement" and s.valid])
    return result


def edge_value_counts(samples: list[Sample]) -> dict:
    """Counts logged coordinates exactly at a screen bound, per (stream, axis, bound).

    The log holds only post-bound values with no clip flag, so these are observed edge values,
    not confirmed clipping events.
    """
    counts: dict[tuple[str, str, float], int] = {}
    for s in samples:
        for stream in STREAMS:
            for axis, value in zip(("x", "y"), getattr(s, stream)):
                if value in (0.0, 1.0):
                    key = (stream, axis, value)
                    counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items()))


def _merge_counts(dicts) -> dict:
    total: dict[str, int] = {r: 0 for r in KNOWN_INVALID_REASONS}
    for d in dicts:
        for k, v in d.items():
            total[k] = total.get(k, 0) + v
    return {**{r: total.pop(r) for r in KNOWN_INVALID_REASONS}, **dict(sorted(total.items()))}


# ---------------------------------------------------------------- aggregation

def _distribution(values) -> dict:
    return {"mean": mean(values), "median": median(values), "p95": nearest_rank(values, 95)}


def trial_balanced(presentations: list[dict], stream: str) -> dict:
    """Equal weight per eligible presentation, using each presentation's own metrics."""
    blocks = [p[stream] for p in presentations if p.get(stream)]
    if not blocks:
        return {"presentations_used": 0, "unavailable_reason": "no eligible presentations"}
    px_ok = all(b["mean_error"]["px"] is not None for b in blocks)

    def both(key, how):
        norm = how([b[key]["norm"] for b in blocks])
        return {"norm": norm, "px": how([b[key]["px"] for b in blocks]) if px_ok else None}

    out = {
        "presentations_used": len(blocks),
        "presentation_mean_error": {k: {"norm": v, "px": None} for k, v in _distribution([b["mean_error"]["norm"] for b in blocks]).items()},
        "presentation_median_error_median": both("median_error", median),
        "presentation_p95_error_median": both("p95_error", median),
        "bias_x_mean": both("bias_x", mean),
        "bias_y_mean": both("bias_y", mean),
        "centroid_error_mean": both("centroid_error", mean),
        "jitter_rms_mean": both("jitter_rms", mean),
        "jitter_rms_median": both("jitter_rms", median),
        "pixel_unavailable_reason": None if px_ok else "pixel basis unavailable for at least one session",
    }
    if px_ok:
        for k, v in _distribution([b["mean_error"]["px"] for b in blocks]).items():
            out["presentation_mean_error"][k]["px"] = v
    return out


def pooled(errors: list[dict]) -> dict:
    """Sample-weighted sensitivity view over all valid measurement samples (eligible or not)."""
    if not errors:
        return {"samples_used": 0, "unavailable_reason": "no valid measurement samples"}
    px_ok = all(e["px"] for e in errors)

    def both(norm_key, px_key, how):
        return {"norm": how([e[norm_key] for e in errors]), "px": how([e[px_key] for e in errors]) if px_ok else None}

    root_mean = lambda v: math.sqrt(mean(v))  # noqa: E731
    return {
        "samples_used": len(errors),
        "samples_from_insufficient_presentations": sum(not e["eligible"] for e in errors),
        "mean_error": both("r", "r_px", mean),
        "median_error": both("r", "r_px", median),
        "p95_error": both("r", "r_px", lambda v: nearest_rank(v, 95)),
        "bias_x": both("dx", "dx_px", mean),
        "bias_y": both("dy", "dy_px", mean),
        "jitter_rms": both("sq", "sq_px", root_mean),
        "pixel_unavailable_reason": None if px_ok else "pixel basis unavailable for at least one session",
    }


def aggregate_mode(mode: str, sessions: list[dict], include_incomplete: bool) -> dict:
    mine = [s for s in sessions if s["mode"] == mode]
    included = [s for s in mine if s["final_state"] == "complete" or include_incomplete]
    excluded = [{"session_id": s["session_id"], "reason": f"incomplete session (end_reason={s['end_reason']})"}
                for s in mine if s["final_state"] != "complete" and not include_incomplete]
    eligible = [p for s in included for p in s["presentations"] if p["status"] == "eligible"]
    attempts = sum(s["measurement"]["attempts"] for s in included)
    valid = sum(s["measurement"]["valid_samples"] for s in included)
    window = sum(s["measurement"]["observed_window_s"] for s in included)
    diagnostic = any(s["final_state"] != "complete" for s in included)
    return {
        "mode": mode,
        "role": "primary" if mode == PRIMARY_MODE else "secondary (visible cursor can change where the participant looks)",
        "diagnostic_only": diagnostic,
        "diagnostic_reason": "includes incomplete sessions (--include-incomplete)" if diagnostic else None,
        "sessions_included": [s["session_id"] for s in included],
        "sessions_excluded": excluded,
        "devices": sorted({f"{(s['device'] or {}).get('manufacturer')} {(s['device'] or {}).get('model')}" for s in included}),
        "pixel_bases": sorted({f"{s['pixel_basis']['width_px']}x{s['pixel_basis']['height_px']}" if s["pixel_basis"] else "unavailable" for s in included}),
        "presentation_status_counts": {k: sum(s["presentation_status_counts"][k] for s in included)
                                       for k in ("eligible", "insufficient_data", "incomplete_presentation")},
        "measurement": {
            "attempts": attempts, "valid_samples": valid,
            "valid_coverage": valid / attempts if attempts else None,
            "valid_samples_per_s": valid / window if window > 0 else None,
            "invalid_by_reason": _merge_counts(s["measurement"]["invalid_by_reason"] for s in included),
        },
        "trial_balanced": {st: trial_balanced(eligible, st) for st in STREAMS},
        "pooled_sensitivity": {st: pooled([e for s in included for e in s["_sample_errors"][st]]) for st in STREAMS},
    }


# ---------------------------------------------------------------- discovery and run

def discover(root: Path) -> list[Path]:
    """A session is any directory holding samples.csv or metadata.json; search stops there."""
    if (root / "samples.csv").exists() or (root / "metadata.json").exists():
        return [root]
    found = []
    for child in sorted(p for p in root.iterdir() if p.is_dir()):
        found.extend(discover(child))
    return found


def run(input_path: Path, out_dir: Path, thresholds: Thresholds, source_label: str | None,
        include_incomplete: bool) -> dict:
    root = input_path
    sessions, rejected = [], []
    paths = discover(root)
    resolved_out = out_dir.resolve()
    for path in paths:
        if resolved_out == path.resolve() or path.resolve() in resolved_out.parents:
            raise SystemExit(f"error: --out {out_dir} is inside session data {path}; choose another directory")
    for path in paths:
        try:
            sessions.append(analyze_session(load_session(path, root), thresholds))
        except (SessionError, OSError, UnicodeDecodeError) as e:
            label = path.relative_to(root).as_posix() if path != root else path.name
            rejected.append({"path": label, "error": str(e)})
    sessions.sort(key=lambda s: (s["session_id"], s["path"]))
    summary = {
        "tool": "analyze_gaze_eval", "tool_version": TOOL_VERSION,
        "supported_schema_versions": list(SUPPORTED_SCHEMA_VERSIONS),
        "source_label": source_label,
        "source_label_note": None if source_label else "not supplied; the APK's exact source revision is unknown",
        "thresholds": thresholds.as_dict(),
        "include_incomplete": include_incomplete,
        "primary_mode": PRIMARY_MODE, "primary_stream": "cursor", "diagnostic_stream": "model",
        "definitions": DEFINITIONS,
        "modes": {m: aggregate_mode(m, sessions, include_incomplete) for m in MODES},
        "sessions": sessions,
        "rejected_sessions": rejected,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    public = _public(summary)
    (out_dir / "summary.json").write_text(json.dumps(public, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    (out_dir / "presentations.csv").write_text(presentations_csv(public["sessions"]), encoding="utf-8")
    plots = write_plots(out_dir, public, sessions)
    (out_dir / "report.md").write_text(markdown_report(public, plots, sessions), encoding="utf-8")
    return summary


def _public(summary: dict) -> dict:
    return {**summary, "sessions": [{k: v for k, v in s.items() if not k.startswith("_")} for s in summary["sessions"]]}


# ---------------------------------------------------------------- CSV output

def _num(v) -> str:
    return "" if v is None else repr(float(v)) if isinstance(v, float) else str(v)


def presentations_csv(sessions: list[dict]) -> str:
    reasons = list(KNOWN_INVALID_REASONS) + sorted({r for s in sessions for p in s["presentations"]
                                                     for r in p["measurement"]["invalid_by_reason"]} - set(KNOWN_INVALID_REASONS))
    metric_cols = []
    for stream in STREAMS:
        for key in ("mean_error", "median_error", "p95_error", "bias_x", "bias_y", "centroid_error", "jitter_rms"):
            metric_cols += [(stream, key, "norm"), (stream, key, "px")]
        metric_cols += [(stream, "median_position", "x_norm"), (stream, "median_position", "y_norm")]
    header = ["session_id", "mode", "session_final_state", "pass_number", "trial_index", "index_in_pass", "target_id",
              "target_x_norm", "target_y_norm", "presentation_complete", "measurement_attempts", "valid_samples",
              "valid_coverage", "measurement_window_s", "valid_samples_per_s"]
    header += [f"invalid_{r}" for r in reasons]
    header += ["settling_attempts", "settling_invalid", "status", "status_reason"]
    header += [f"{s}_{k}_{u}" for s, k, u in metric_cols]
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(header)
    for s in sessions:
        for p in s["presentations"]:
            m = p["measurement"]
            row = [s["session_id"], s["mode"], s["final_state"], p["pass_number"], p["trial_index"], p["index_in_pass"],
                   p["target_id"], p["target_x_norm"], p["target_y_norm"], str(p["presentation_complete"]).lower(),
                   m["attempts"], m["valid_samples"], m["valid_coverage"], p["measurement_window_s"], m["valid_samples_per_s"]]
            row += [m["invalid_by_reason"].get(r, 0) for r in reasons]
            row += [p["settling"]["attempts"], p["settling"]["attempts"] - p["settling"]["valid_samples"], p["status"], p["status_reason"] or ""]
            row += [(p[st] or {}).get(k, {}).get(u) if p[st] else None for st, k, u in metric_cols]
            w.writerow([_num(v) if not isinstance(v, str) else v for v in row])
    return buf.getvalue()


# ---------------------------------------------------------------- SVG plots

SVG_STYLE = """<style>
  .viz { --surface:#fcfcfb; --card:#f1f0ec; --ink:#0b0b0b; --ink2:#52514e; --rule:#e4e3df; --s1:#2a78d6; --s2:#eb6834; --serious:#ec835a; --muted:#b9b8b2; }
  @media (prefers-color-scheme: dark) { .viz { --surface:#1a1a19; --card:#262624; --ink:#f0efec; --ink2:#c3c2b7; --rule:#3a3936; --s1:#3987e5; --s2:#d95926; --muted:#6b6a65; } }
  text { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; fill: var(--ink); }
  .t2 { fill: var(--ink2); }
</style>"""


def _esc(text) -> str:
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def _svg(width: int, height: int, title: str, body: list[str]) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" class="viz" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" role="img" aria-label="{_esc(title)}">\n<title>{_esc(title)}</title>\n'
            f"{SVG_STYLE}\n" f'<rect width="{width}" height="{height}" fill="var(--surface)"/>\n' + "\n".join(body) + "\n</svg>\n")


def _bar(x, y_top, width, base, color, tooltip) -> str:
    """Bar with 4px rounded data end anchored on the baseline."""
    h = base - y_top
    if h <= 0:
        return ""
    r = min(4.0, width / 2, h)
    return (f'<path d="M{x:.2f},{base:.2f} V{y_top + r:.2f} Q{x:.2f},{y_top:.2f} {x + r:.2f},{y_top:.2f} '
            f'H{x + width - r:.2f} Q{x + width:.2f},{y_top:.2f} {x + width:.2f},{y_top + r:.2f} V{base:.2f} Z" '
            f'fill="var({color})"><title>{_esc(tooltip)}</title></path>')


def target_map_svg(mode: str, agg: dict, sessions: list[dict]) -> str | None:
    included = [s for s in sessions if s["session_id"] in agg["sessions_included"]]
    points = [(s, p) for s in included for p in s["presentations"] if p["status"] == "eligible"]
    if not points:
        return None
    bases = {(s["pixel_basis"]["width_px"], s["pixel_basis"]["height_px"]) for s in included if s["pixel_basis"]}
    aspect = (lambda b: b[1] / b[0])(next(iter(bases))) if len(bases) == 1 else 1.0
    pw = 360.0
    ph = pw * aspect
    left, top = 70.0, 110.0
    width, height = int(left + pw + 40), int(top + ph + 76)
    sx = lambda v: left + v * pw  # noqa: E731
    sy = lambda v: top + v * ph  # noqa: E731
    body = [f'<text x="20" y="30" font-size="16" font-weight="600">Target map: {_esc(mode)} (post-smoothing cursor)</text>',
            '<text class="t2" x="20" y="50" font-size="12">Median cursor position per eligible presentation, linked to its target.</text>',
            f'<text class="t2" x="20" y="66" font-size="12">n = {len(points)} presentations, {len(included)} session(s).</text>',
            f'<circle cx="{left + 6}" cy="86" r="5" fill="var(--s1)"/><text x="{left + 16}" y="90" font-size="12">Pass 1 (filled)</text>',
            f'<circle cx="{left + 136}" cy="86" r="4" fill="var(--surface)" stroke="var(--s2)" stroke-width="2"/>'
            f'<text x="{left + 146}" y="90" font-size="12">Pass 2 (hollow)</text>',
            f'<rect x="{left}" y="{top}" width="{pw:.2f}" height="{ph:.2f}" fill="none" stroke="var(--rule)" stroke-width="1"/>']
    for g in (0.2, 0.4, 0.6, 0.8):
        body.append(f'<line x1="{sx(g):.2f}" y1="{top}" x2="{sx(g):.2f}" y2="{top + ph:.2f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<line x1="{left}" y1="{sy(g):.2f}" x2="{left + pw:.2f}" y2="{sy(g):.2f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<text class="t2" x="{sx(g):.2f}" y="{top + ph + 16:.2f}" font-size="11" text-anchor="middle">{g:.1f}</text>')
        body.append(f'<text class="t2" x="{left - 8}" y="{sy(g) + 4:.2f}" font-size="11" text-anchor="end">{g:.1f}</text>')
    body.append(f'<text class="t2" x="{left + pw / 2:.2f}" y="{top + ph + 36:.2f}" font-size="11" text-anchor="middle">x, normalized (right +)</text>')
    body.append(f'<text class="t2" x="20" y="{top + ph / 2:.2f}" font-size="11" transform="rotate(-90 20 {top + ph / 2:.2f})" '
                f'text-anchor="middle">y, normalized (down +)</text>')
    for s, p in points:
        mp, color = p["cursor"]["median_position"], "--s1" if p["pass_number"] == 1 else "--s2"
        x, y = sx(mp["x_norm"]), sy(mp["y_norm"])
        body.append(f'<line x1="{sx(p["target_x_norm"]):.2f}" y1="{sy(p["target_y_norm"]):.2f}" x2="{x:.2f}" y2="{y:.2f}" '
                    f'stroke="var({color})" stroke-width="1.5"/>')
    for s, p in points:
        mp, err = p["cursor"]["median_position"], p["cursor"]["mean_error"]["norm"]
        tip = (f'{s["session_id"]} pass {p["pass_number"]} trial {p["trial_index"]} {p["target_id"]}: '
               f'n={p["cursor"]["n_samples"]}, mean error {err:.4f}')
        x, y = sx(mp["x_norm"]), sy(mp["y_norm"])
        if p["pass_number"] == 1:
            body.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="5" fill="var(--s1)" stroke="var(--surface)" stroke-width="2"><title>{_esc(tip)}</title></circle>')
        else:
            body.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="4" fill="var(--surface)" stroke="var(--s2)" stroke-width="2"><title>{_esc(tip)}</title></circle>')
    for g_y in (0.2, 0.4, 0.6, 0.8):
        for g_x in (0.2, 0.4, 0.6, 0.8):
            cx, cy = sx(g_x), sy(g_y)
            body.append(f'<path d="M{cx - 6:.2f},{cy:.2f} H{cx + 6:.2f} M{cx:.2f},{cy - 6:.2f} V{cy + 6:.2f}" stroke="var(--ink)" stroke-width="2"/>')
    body.append(f'<text class="t2" x="20" y="{height - 12}" font-size="11">+ = target (instructed fixation, not measured gaze). '
                f'Aspect: {"pixel basis " + "x".join(map(str, next(iter(bases)))) if len(bases) == 1 else "square (mixed or missing bases)"}.</text>')
    return _svg(width, height, f"Target map {mode}", body)


def error_distribution_svg(mode: str, agg: dict, sessions: list[dict]) -> str | None:
    included = [s for s in sessions if s["session_id"] in agg["sessions_included"]]
    errors = sorted(e["r"] for s in included for e in s["_sample_errors"]["cursor"] if e["eligible"])
    if not errors:
        return None
    n_pres = agg["presentation_status_counts"]["eligible"]
    top_value = max(errors)
    step = 0.05
    x_max = max(step, math.ceil(top_value / step) * step)
    bins = 30
    width_bin = x_max / bins
    counts = [0] * bins
    for e in errors:
        counts[min(bins - 1, int(e / width_bin))] += 1
    left, top, pw, ph = 70.0, 100.0, 520.0, 220.0
    width, height = int(left + pw + 30), int(top + ph + 80)
    y_step = _nice_step(max(counts))
    y_max = y_step * math.ceil(max(counts) / y_step)
    sx = lambda v: left + v / x_max * pw  # noqa: E731
    base = top + ph
    body = [f'<text x="20" y="30" font-size="16" font-weight="600">Target-referenced error distribution: {_esc(mode)} (cursor)</text>',
            f'<text class="t2" x="20" y="50" font-size="12">Pooled valid measurement samples from eligible presentations. '
            f'n = {len(errors)} samples, {n_pres} presentations, {len(included)} session(s).</text>']
    for tick in range(y_step, y_max + 1, y_step):
        y = base - tick / y_max * ph
        body.append(f'<line x1="{left}" y1="{y:.2f}" x2="{left + pw}" y2="{y:.2f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<text class="t2" x="{left - 8}" y="{y + 4:.2f}" font-size="11" text-anchor="end">{tick}</text>')
    gap = 2.0
    for i, c in enumerate(counts):
        x0 = left + i * pw / bins
        lo, hi = i * width_bin, (i + 1) * width_bin
        body.append(_bar(x0 + gap / 2, base - c / y_max * ph, pw / bins - gap, base, "--s1", f"{lo:.3f}-{hi:.3f}: {c} samples"))
    body.append(f'<line x1="{left}" y1="{base}" x2="{left + pw}" y2="{base}" stroke="var(--ink2)" stroke-width="1"/>')
    for k in range(6):
        v = x_max * k / 5
        body.append(f'<text class="t2" x="{sx(v):.2f}" y="{base + 16}" font-size="11" text-anchor="middle">{v:.2f}</text>')
    body.append(f'<text class="t2" x="{left + pw / 2}" y="{base + 36}" font-size="11" text-anchor="middle">radial error, normalized units</text>')
    body.append(f'<text class="t2" x="18" y="{top + ph / 2}" font-size="11" transform="rotate(-90 18 {top + ph / 2})" text-anchor="middle">samples per bin</text>')
    # Reference labels sit in a band above the plot, staggered so they never touch bars or each other.
    for label, value, label_y in (("median", median(errors), top - 22), ("p95", nearest_rank(errors, 95), top - 8)):
        x = sx(value)
        body.append(f'<line x1="{x:.2f}" y1="{label_y + 4:.2f}" x2="{x:.2f}" y2="{base}" stroke="var(--ink)" stroke-width="1.5"/>')
        body.append(f'<text x="{x + 4:.2f}" y="{label_y:.2f}" font-size="11">{label} {value:.4f}</text>')
    body.append(f'<text class="t2" x="20" y="{height - 12}" font-size="11">Sample-weighted view; the primary summary is trial-balanced (see report).</text>')
    return _svg(width, height, f"Error distribution {mode}", body)


def _nice_step(max_count: int) -> int:
    """Tick step from 1/2/5 x 10^k giving at most five gridlines."""
    magnitude = 1
    while True:
        for factor in (1, 2, 5):
            if max_count / (factor * magnitude) <= 5:
                return factor * magnitude
        magnitude *= 10


def coverage_svg(session: dict, thresholds: dict) -> str:
    pres = session["presentations"]
    left, top, pw, ph = 70.0, 92.0, 620.0, 200.0
    width, height = int(left + pw + 30), int(top + ph + 84)
    base = top + ph
    slot = pw / len(pres)
    counts = session["presentation_status_counts"]
    body = [f'<text x="20" y="30" font-size="16" font-weight="600">Valid coverage per presentation: {_esc(session["session_id"])}</text>',
            f'<text class="t2" x="20" y="50" font-size="12">{_esc(session["mode"])}, {session["final_state"]} ({_esc(session["end_reason"])}). '
            f'{len(pres)} presentations: {counts["eligible"]} eligible, {counts["insufficient_data"]} insufficient, '
            f'{counts["incomplete_presentation"]} not fully shown.</text>',
            f'<rect x="{left}" y="64" width="10" height="10" rx="2" fill="var(--s1)"/><text x="{left + 16}" y="73" font-size="12">eligible</text>',
            f'<rect x="{left + 90}" y="64" width="10" height="10" rx="2" fill="var(--serious)"/>'
            f'<text x="{left + 106}" y="73" font-size="12">! insufficient data</text>',
            f'<rect x="{left + 236}" y="64" width="10" height="10" rx="2" fill="var(--muted)"/>'
            f'<text x="{left + 252}" y="73" font-size="12">– not fully shown</text>',
            f'<line x1="{left + 382}" y1="69" x2="{left + 398}" y2="69" stroke="var(--ink)" stroke-width="1.5"/>'
            f'<text x="{left + 404}" y="73" font-size="12">min coverage {thresholds["min_valid_coverage"]:g}</text>']
    for frac in (0.25, 0.5, 0.75, 1.0):
        y = base - frac * ph
        body.append(f'<line x1="{left}" y1="{y:.2f}" x2="{left + pw}" y2="{y:.2f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<text class="t2" x="{left - 8}" y="{y + 4:.2f}" font-size="11" text-anchor="end">{int(frac * 100)}%</text>')
    colors = {"eligible": "--s1", "insufficient_data": "--serious", "incomplete_presentation": "--muted"}
    for i, p in enumerate(pres):
        m, x0 = p["measurement"], left + i * slot
        cov = m["valid_coverage"] or 0.0
        tip = (f'trial {p["trial_index"]} (pass {p["pass_number"]}) {p["target_id"]}: {m["valid_samples"]}/{m["attempts"]} valid, '
               f'{p["status"]}' + (f' ({p["status_reason"]})' if p["status_reason"] else ""))
        body.append(_bar(x0 + 1, base - cov * ph, slot - 2, base, colors[p["status"]], tip))
        mark = {"insufficient_data": "!", "incomplete_presentation": "–"}.get(p["status"])
        if mark:
            body.append(f'<text x="{x0 + slot / 2:.2f}" y="{base - cov * ph - 4:.2f}" font-size="11" font-weight="700" text-anchor="middle">{mark}<title>{_esc(tip)}</title></text>')
        if i % 4 == 0:
            body.append(f'<text class="t2" x="{x0 + slot / 2:.2f}" y="{base + 16}" font-size="11" text-anchor="middle">{i}</text>')
    half = left + slot * len(pres) / 2
    body.append(f'<line x1="{half:.2f}" y1="{top - 14}" x2="{half:.2f}" y2="{base}" stroke="var(--ink2)" stroke-width="1"/>')
    body.append(f'<text class="t2" x="{half - 6:.2f}" y="{top - 6}" font-size="11" text-anchor="end">pass 1</text>')
    body.append(f'<text class="t2" x="{half + 6:.2f}" y="{top - 6}" font-size="11">pass 2</text>')
    ty = base - thresholds["min_valid_coverage"] * ph
    body.append(f'<line x1="{left}" y1="{ty:.2f}" x2="{left + pw}" y2="{ty:.2f}" stroke="var(--ink)" stroke-width="1.5"/>')
    body.append(f'<line x1="{left}" y1="{base}" x2="{left + pw}" y2="{base}" stroke="var(--ink2)" stroke-width="1"/>')
    body.append(f'<text class="t2" x="{left + pw / 2}" y="{base + 34}" font-size="11" text-anchor="middle">trial index (0-based)</text>')
    body.append(f'<text class="t2" x="20" y="{height - 12}" font-size="11">Eligibility also needs ≥ {thresholds["min_valid_samples"]} valid samples; '
                f'coverage = valid / measurement attempts (invalid attempts stay in the denominator).</text>')
    return _svg(width, height, f"Coverage {session['session_id']}", body)


# ---------------------------------------------------------------- per-session visual summary

MODE_TITLES = {"blind_measurement": "Blind measurement", "live_preview": "Live preview"}


def _grid_order(target_id: str) -> tuple[int, int]:
    match = TARGET_ID.match(target_id)
    return int(match.group(1)), int(match.group(2))


def target_map_points(session: dict) -> list[dict]:
    """One point per target: the equal-weight mean of its eligible presentations' mean cursor positions.

    Frames are never pooled across presentations, so a presentation with more valid frames does not
    pull the target's point toward itself.
    """
    by_target: dict[str, dict] = {}
    for p in session["presentations"]:
        entry = by_target.setdefault(p["target_id"], {"target_id": p["target_id"], "target_x": p["target_x_norm"],
                                                      "target_y": p["target_y_norm"], "presentations": []})
        if p["cursor"]:
            c = p["cursor"]["centroid"]
            entry["presentations"].append({"pass": p["pass_number"], "trial_index": p["trial_index"],
                                           "x": c["x_norm"], "y": c["y_norm"]})
    points = []
    for target_id in sorted(by_target, key=_grid_order):
        e = by_target[target_id]
        used = e["presentations"]
        e["x"] = mean([u["x"] for u in used])
        e["y"] = mean([u["y"] for u in used])
        e["presentations_used"] = len(used)
        points.append(e)
    return points


def paired_presentation_errors(session: dict) -> tuple[str, list[dict]]:
    """Per target (grid reading order), the pass 1 and pass 2 presentation mean cursor errors.

    Values are None for presentations that are not eligible; they are never replaced by zero.
    Returns the unit used ('px' when the session has a pixel basis, else 'norm').
    """
    unit = "px" if session["pixel_basis"] else "norm"
    rows: dict[str, dict] = {}
    for p in session["presentations"]:
        row = rows.setdefault(p["target_id"], {"target_id": p["target_id"], "target_x": p["target_x_norm"],
                                               "target_y": p["target_y_norm"], "passes": {}})
        value = p["cursor"]["mean_error"][unit] if p["cursor"] else None
        row["passes"][p["pass_number"]] = {"trial_index": p["trial_index"], "value": value, "status": p["status"]}
    return unit, [rows[t] for t in sorted(rows, key=_grid_order)]


def edge_value_note(edge_values: dict) -> str:
    """Plain-language note on logged coordinates at a screen bound (not confirmed clipping)."""
    if not edge_values:
        return "No valid measurement value was at a screen edge (x or y equal to 0 or 1)."
    names = {("y", 1.0): "bottom edge (y = 1.0)", ("y", 0.0): "top edge (y = 0.0)",
             ("x", 0.0): "left edge (x = 0.0)", ("x", 1.0): "right edge (x = 1.0)"}
    parts = []
    for (axis, bound), label in names.items():
        found = [f"{edge_values[(stream, axis, bound)]} {stream} value{'s' if edge_values[(stream, axis, bound)] != 1 else ''}"
                 for stream in STREAMS if (stream, axis, bound) in edge_values]
        if found:
            parts.append(f"{' and '.join(found)} at the {label}")
    return ("Values at a screen edge among valid measurement samples: " + "; ".join(parts) + ". "
            "The log stores only values bounded to the screen and has no clip flag, so these are not confirmed clipping events.")


def _wrap(text: str, max_chars: int) -> list[str]:
    lines, current = [], ""
    for word in text.split():
        if current and len(current) + 1 + len(word) > max_chars:
            lines.append(current)
            current = word
        else:
            current = f"{current} {word}".strip()
    return lines + ([current] if current else [])


def _nice_axis_max(value: float) -> tuple[float, float]:
    """Axis maximum and tick step from 1/2/2.5/5 x 10^k, with at most six ticks."""
    if value <= 0:
        return 1.0, 0.2
    magnitude = 10 ** math.floor(math.log10(value / 6))
    for factor in (1, 2, 2.5, 5, 10):
        step = factor * magnitude
        if value / step <= 6:
            return step * math.ceil(value / step), step
    return value, value  # unreachable


def _fmt_value(v, unit: str) -> str:
    if v is None:
        return "N/A"
    return f"{v:.1f} px" if unit == "px" else f"{v:.4f} norm"


def _threshold_phrase(thresholds: dict) -> str:
    default = (thresholds["min_valid_samples"] == DEFAULT_MIN_VALID_SAMPLES
               and thresholds["min_valid_coverage"] == DEFAULT_MIN_VALID_COVERAGE)
    rule = (f"≥ {thresholds['min_valid_samples']} valid samples and ≥ {thresholds['min_valid_coverage'] * 100:g}% "
            f"valid coverage per presentation")
    return f"under the analyzer's default thresholds ({rule})" if default else f"under the thresholds used ({rule})"


def summary_svg(session: dict, thresholds: dict) -> str:
    """Report-ready summary for ONE session: metric cards, target-position map, paired presentation errors.

    Contains no build or source provenance by design; that belongs in the report prose.
    """
    mode_title = MODE_TITLES.get(session["mode"], session["mode"])
    title = f"{mode_title}: cursor position and target-referenced error"
    unit, rows = paired_presentation_errors(session)
    points = target_map_points(session)
    tb = session["trial_balanced"]["cursor"]
    counts = session["presentation_status_counts"]
    n_pres = len(session["presentations"])
    m = session["measurement"]
    dev = session["device"] or {}
    basis = session["pixel_basis"]

    width = 1000
    body = [f'<text x="24" y="38" font-size="21" font-weight="600">{_esc(title)}</text>']
    basis_text = f"{basis['width_px']}×{basis['height_px']} px" if basis else "unavailable"
    n_targets = len({p["target_id"] for p in session["presentations"]})
    context = (f"{dev.get('manufacturer') or 'unknown'} {dev.get('model') or 'device'} · screen basis {basis_text} · "
               f"{n_pres} target presentations ({n_targets} targets × {n_pres // max(n_targets, 1)} passes)")
    body.append(f'<text class="t2" x="24" y="62" font-size="13">{_esc(context)}</text>')
    scope = f"Session {session['session_id']} only; not combined with other sessions."
    if session["final_state"] != "complete":
        scope += f" Incomplete session ({session['end_reason']}): diagnostic only."
    body.append(f'<text class="t2" x="24" y="81" font-size="12">{_esc(scope)}</text>')

    # Metric cards.
    used = tb.get("presentations_used", 0)
    if used:
        mean_err = tb["presentation_mean_error"]["mean"][unit]
        p95_err = tb["presentation_mean_error"]["p95"][unit]
        bias_x = tb["bias_x_mean"][unit]
    else:
        mean_err = p95_err = bias_x = None
    if bias_x is None:
        bias_text, bias_sub = "N/A", "no eligible presentations"
    else:
        direction = "right" if bias_x > 0 else "left" if bias_x < 0 else "none"
        bias_text = f"{_fmt_value(abs(bias_x), unit)} {direction}" if direction != "none" else _fmt_value(0.0, unit)
        bias_sub = f"cursor {direction} of target on average" if direction != "none" else "no horizontal offset on average"
    coverage = m["valid_coverage"]
    cards = [
        ("Trial-balanced mean error", _fmt_value(mean_err, unit), f"mean of {used} presentation means"),
        ("p95 of presentation means", _fmt_value(p95_err, unit), f"nearest rank, {used} presentation means"),
        ("Mean horizontal bias", bias_text, bias_sub),
        ("Valid measurement predictions", f"{m['valid_samples']} / {m['attempts']}",
         f"{coverage * 100:.1f}% of measurement attempts" if coverage is not None else "no measurement attempts"),
    ]
    card_w, gap, top = (width - 48 - 3 * 16) / 4, 16, 98
    for i, (label, value, sub) in enumerate(cards):
        x = 24 + i * (card_w + gap)
        body.append(f'<rect x="{x:.1f}" y="{top}" width="{card_w:.1f}" height="84" rx="8" fill="var(--card)"/>')
        body.append(f'<text class="t2" x="{x + 14:.1f}" y="{top + 24}" font-size="12.5">{_esc(label)}</text>')
        body.append(f'<text x="{x + 14:.1f}" y="{top + 54}" font-size="24" font-weight="600">{_esc(value)}</text>')
        body.append(f'<text class="t2" x="{x + 14:.1f}" y="{top + 73}" font-size="11.5">{_esc(sub)}</text>')
    eligible_line = f"{counts['eligible']} of {n_pres} presentations eligible {_threshold_phrase(thresholds)}."
    body.append(f'<text x="24" y="{top + 110}" font-size="13">{_esc(eligible_line)}</text>')
    body.append(f'<text class="t2" x="24" y="{top + 128}" font-size="12">No pass/fail threshold is defined for these values.</text>')

    panel_top = top + 166
    # ---- Left panel: target-position map (true portrait aspect from the recorded basis).
    map_w = 250.0
    map_h = map_w * basis["height_px"] / basis["width_px"] if basis else map_w
    mx0, my0 = 66.0, panel_top + 56
    sx = lambda v: mx0 + v * map_w  # noqa: E731
    sy = lambda v: my0 + v * map_h  # noqa: E731
    body.append(f'<text x="24" y="{panel_top}" font-size="15" font-weight="600">Target-position map</text>')
    body.append(f'<text class="t2" x="24" y="{panel_top + 18}" font-size="12">Screen drawn to scale; y = 0 is the top edge.</text>')
    body.append(f'<text class="t2" x="{mx0 + map_w / 2:.1f}" y="{my0 - 8:.1f}" font-size="11" text-anchor="middle">top (y = 0)</text>')
    body.append(f'<rect x="{mx0}" y="{my0}" width="{map_w:.1f}" height="{map_h:.1f}" rx="6" fill="none" stroke="var(--ink2)" stroke-width="1"/>')
    for g in sorted({p["target_x"] for p in points}):
        body.append(f'<line x1="{sx(g):.1f}" y1="{my0}" x2="{sx(g):.1f}" y2="{my0 + map_h:.1f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<text class="t2" x="{sx(g):.1f}" y="{my0 + map_h + 16:.1f}" font-size="11" text-anchor="middle">{g:g}</text>')
    for g in sorted({p["target_y"] for p in points}):
        body.append(f'<line x1="{mx0}" y1="{sy(g):.1f}" x2="{mx0 + map_w:.1f}" y2="{sy(g):.1f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<text class="t2" x="{mx0 - 8:.1f}" y="{sy(g) + 4:.1f}" font-size="11" text-anchor="end">{g:g}</text>')
    body.append(f'<text class="t2" x="{mx0 + map_w / 2:.1f}" y="{my0 + map_h + 34:.1f}" font-size="11" text-anchor="middle">'
                f'bottom (y = 1) · x increases to the right</text>')
    for p in points:
        if p["x"] is not None:
            body.append(f'<line x1="{sx(p["target_x"]):.1f}" y1="{sy(p["target_y"]):.1f}" x2="{sx(p["x"]):.1f}" '
                        f'y2="{sy(p["y"]):.1f}" stroke="var(--ink2)" stroke-width="1.25"/>')
    for p in points:
        tx, ty = sx(p["target_x"]), sy(p["target_y"])
        body.append(f'<circle cx="{tx:.1f}" cy="{ty:.1f}" r="7" fill="none" stroke="var(--ink)" stroke-width="1.5"/>'
                    f'<path d="M{tx - 10:.1f},{ty:.1f} H{tx + 10:.1f} M{tx:.1f},{ty - 10:.1f} V{ty + 10:.1f}" stroke="var(--ink)" stroke-width="1.25"/>')
        if p["x"] is not None:
            tip = (f"Target ({p['target_x']:g}, {p['target_y']:g}): average cursor ({p['x']:.3f}, {p['y']:.3f}) "
                   f"from {p['presentations_used']} presentation mean(s)")
            body.append(f'<circle cx="{sx(p["x"]):.1f}" cy="{sy(p["y"]):.1f}" r="5" fill="var(--s1)" stroke="var(--surface)" '
                        f'stroke-width="1.5"><title>{_esc(tip)}</title></circle>')
    legend_y = my0 + map_h + 60
    body.append(f'<circle cx="34" cy="{legend_y - 4:.1f}" r="6" fill="none" stroke="var(--ink)" stroke-width="1.5"/>'
                f'<path d="M26,{legend_y - 4:.1f} H42 M34,{legend_y - 12:.1f} V{legend_y + 4:.1f}" stroke="var(--ink)" stroke-width="1.25"/>')
    body.append(f'<text x="50" y="{legend_y:.1f}" font-size="12">Target (each shown twice: pass 1 and pass 2)</text>')
    body.append(f'<circle cx="34" cy="{legend_y + 18:.1f}" r="5" fill="var(--s1)"/>')
    for j, line in enumerate(_wrap("Average cursor position: equal-weight mean of the two presentation means "
                                   "(after smoothing); line joins it to its target", 46)):
        body.append(f'<text x="50" y="{legend_y + 22 + 15 * j:.1f}" font-size="12">{_esc(line)}</text>')
    left_bottom = legend_y + 22 + 15 * 2

    # ---- Right panel: paired presentation-error dot plot.
    rx0, plot_x0, plot_x1 = 370.0, 470.0, 960.0
    body.append(f'<text x="{rx0}" y="{panel_top}" font-size="15" font-weight="600">Presentation-error comparison</text>')
    body.append(f'<text class="t2" x="{rx0}" y="{panel_top + 18}" font-size="12">Mean cursor error of each presentation, '
                f'pass 1 and pass 2 side by side for each target.</text>')
    ly = panel_top + 44
    body.append(f'<circle cx="{rx0 + 6}" cy="{ly - 4}" r="5" fill="var(--s1)"/><text x="{rx0 + 18}" y="{ly}" font-size="12">Pass 1</text>')
    body.append(f'<rect x="{rx0 + 82}" y="{ly - 9}" width="10" height="10" fill="none" stroke="var(--s2)" stroke-width="2"/>'
                f'<text x="{rx0 + 100}" y="{ly}" font-size="12">Pass 2</text>')
    values = [r["passes"][k]["value"] for r in rows for k in r["passes"] if r["passes"][k]["value"] is not None]
    refs = []
    if used:
        refs = [("p95", tb["presentation_mean_error"]["p95"][unit]), ("median", tb["presentation_mean_error"]["median"][unit]),
                ("mean", tb["presentation_mean_error"]["mean"][unit])]
    axis_max, step = _nice_axis_max(max(values + [v for _, v in refs if v is not None], default=0.0))
    px = lambda v: plot_x0 + v / axis_max * (plot_x1 - plot_x0)  # noqa: E731
    label_rows = [ly + 22, ly + 38, ly + 54]
    rows_top, row_h = ly + 70, 26.0
    rows_bottom = rows_top + row_h * len(rows)
    body.append(f'<text class="t2" x="{plot_x0 - 12}" y="{rows_top - 8:.1f}" font-size="11" text-anchor="end">target (x, y)</text>')
    tick = 0.0
    while tick <= axis_max + 1e-9:
        x = px(tick)
        body.append(f'<line x1="{x:.1f}" y1="{rows_top:.1f}" x2="{x:.1f}" y2="{rows_bottom:.1f}" stroke="var(--rule)" stroke-width="1"/>')
        body.append(f'<text class="t2" x="{x:.1f}" y="{rows_bottom + 16:.1f}" font-size="11" text-anchor="middle">{tick:g}</text>')
        tick += step
    for i, row in enumerate(rows):
        cy = rows_top + row_h * (i + 0.5)
        body.append(f'<text x="{plot_x0 - 12}" y="{cy + 4:.1f}" font-size="12" text-anchor="end">'
                    f'({row["target_x"]:g}, {row["target_y"]:g})</text>')
        p1, p2 = row["passes"].get(1, {}).get("value"), row["passes"].get(2, {}).get("value")
        if p1 is not None and p2 is not None:
            body.append(f'<line x1="{px(p1):.1f}" y1="{cy:.1f}" x2="{px(p2):.1f}" y2="{cy:.1f}" stroke="var(--rule)" stroke-width="3"/>')
        missing = [str(k) for k in (1, 2) if row["passes"].get(k, {}).get("value") is None]
        if missing:
            body.append(f'<text class="t2" x="{plot_x1:.1f}" y="{cy + 4:.1f}" font-size="11" text-anchor="end">'
                        f'pass {" and ".join(missing)}: no eligible data</text>')
        for k, info in sorted(row["passes"].items()):
            v = info["value"]
            if v is None:
                continue
            tip = (f"Target ({row['target_x']:g}, {row['target_y']:g}), pass {k} (trial {info['trial_index']}): "
                   f"{_fmt_value(v, unit)}")
            if k == 1:
                body.append(f'<circle cx="{px(v):.1f}" cy="{cy:.1f}" r="5" fill="var(--s1)"><title>{_esc(tip)}</title></circle>')
            else:
                # Hollow (no fill) so a coinciding pass 1 dot stays visible inside the square.
                body.append(f'<rect x="{px(v) - 5:.1f}" y="{cy - 5:.1f}" width="10" height="10" fill="none" '
                            f'stroke="var(--s2)" stroke-width="2"><title>{_esc(tip)}</title></rect>')
    # Reference lines: each reaches its own label row, so near-identical values stay readable.
    for (name, v), label_y in zip(refs, label_rows):
        if v is None:
            continue
        x = px(v)
        body.append(f'<line x1="{x:.1f}" y1="{label_y + 3:.1f}" x2="{x:.1f}" y2="{rows_bottom:.1f}" stroke="var(--ink)" stroke-width="1.25"/>')
        anchor, dx = ("end", -5) if x > plot_x1 - 140 else ("start", 5)
        body.append(f'<text x="{x + dx:.1f}" y="{label_y:.1f}" font-size="11.5" text-anchor="{anchor}">'
                    f'{name} {_fmt_value(v, unit)}</text>')
    body.append(f'<line x1="{plot_x0}" y1="{rows_bottom:.1f}" x2="{plot_x1}" y2="{rows_bottom:.1f}" stroke="var(--ink2)" stroke-width="1"/>')
    axis_label = ("mean cursor error per presentation (px), axis starts at 0" if unit == "px"
                  else "mean cursor error per presentation (normalized; pixel basis unavailable), axis starts at 0")
    body.append(f'<text class="t2" x="{(plot_x0 + plot_x1) / 2:.1f}" y="{rows_bottom + 34:.1f}" font-size="11.5" '
                f'text-anchor="middle">{_esc(axis_label)}</text>')
    caption = (f"Each marker is one target presentation (the mean over its valid measurement samples), not one camera frame. "
               f"Reference lines are across the {used} eligible presentation means; the p95 is not taken over individual samples.")
    for j, line in enumerate(_wrap(caption, 92)):
        body.append(f'<text class="t2" x="{rx0}" y="{rows_bottom + 58 + 15 * j:.1f}" font-size="11.5">{_esc(line)}</text>')
    right_bottom = rows_bottom + 58 + 15 * 2

    # ---- Notes outside the plots.
    notes_top = max(left_bottom, right_bottom) + 34
    body.append(f'<line x1="24" y1="{notes_top - 18:.1f}" x2="{width - 24}" y2="{notes_top - 18:.1f}" stroke="var(--rule)" stroke-width="1"/>')
    notes = [edge_value_note(session.get("_edge_values", {})),
             "Spatial values use valid measurement samples only; settling samples and invalid predictions are excluded "
             "(invalid attempts remain in the coverage card). Targets are the instructed fixation points, not measured gaze."]
    y = notes_top
    for note in notes:
        for line in _wrap(note, 150):
            body.append(f'<text class="t2" x="24" y="{y:.1f}" font-size="12">{_esc(line)}</text>')
            y += 16
        y += 4
    return _svg(width, int(y + 14), title, body)


def write_plots(out_dir: Path, public: dict, sessions: list[dict]) -> dict:
    plot_dir = out_dir / "plots"
    plot_dir.mkdir(exist_ok=True)
    written: dict[str, list[str]] = {}
    for s in sessions:
        name = f"summary_{re.sub(r'[^A-Za-z0-9._-]', '_', s['session_id'])}.svg"
        (plot_dir / name).write_text(summary_svg(s, public["thresholds"]), encoding="utf-8")
        written.setdefault(f"summary:{s['session_id']}", []).append(f"plots/{name}")
    for mode in MODES:
        agg = public["modes"][mode]
        for name, svg in ((f"{mode}_target_map.svg", target_map_svg(mode, agg, sessions)),
                          (f"{mode}_error_distribution.svg", error_distribution_svg(mode, agg, sessions))):
            if svg:
                (plot_dir / name).write_text(svg, encoding="utf-8")
                written.setdefault(mode, []).append(f"plots/{name}")
    for s in sessions:
        name = f"coverage_{re.sub(r'[^A-Za-z0-9._-]', '_', s['session_id'])}.svg"
        (plot_dir / name).write_text(coverage_svg(s, public["thresholds"]), encoding="utf-8")
        written.setdefault("diagnostic_coverage", []).append(f"plots/{name}")  # written, not embedded in report.md
    return written


# ---------------------------------------------------------------- Markdown report

def _f(v, digits=4, suffix="") -> str:
    return "N/A" if v is None else f"{v:.{digits}f}{suffix}"


def _pct(v) -> str:
    return "N/A" if v is None else f"{v * 100:.1f}%"


def _mode_section(agg: dict, plots: dict) -> list[str]:
    lines = []
    if agg["diagnostic_only"]:
        lines.append(f"> **Diagnostic only:** {agg['diagnostic_reason']}. Do not use as a primary result.\n")
    m = agg["measurement"]
    counts = agg["presentation_status_counts"]
    lines.append(f"- Sessions included: **{len(agg['sessions_included'])}**; excluded: {len(agg['sessions_excluded'])}"
                 + "".join(f"\n  - `{e['session_id']}`: {e['reason']}" for e in agg["sessions_excluded"]))
    lines.append(f"- Devices: {', '.join(agg['devices']) or 'none'}; pixel basis: {', '.join(agg['pixel_bases']) or 'none'}")
    lines.append(f"- Presentations: {counts['eligible']} eligible, {counts['insufficient_data']} insufficient_data, "
                 f"{counts['incomplete_presentation']} incomplete_presentation")
    lines.append(f"- Measurement windows: {m['valid_samples']} valid of {m['attempts']} inference attempts "
                 f"(coverage {_pct(m['valid_coverage'])}, {_f(m['valid_samples_per_s'], 1)} valid samples/s)")
    lines.append("- Invalid measurement attempts by logged reason: " +
                 (", ".join(f"`{k}` {v}" for k, v in m["invalid_by_reason"].items() if v) or "none"))
    lines.append("")
    tb_c, tb_m = agg["trial_balanced"]["cursor"], agg["trial_balanced"]["model"]
    lines.append(f"**Trial-balanced** (each eligible presentation weighted equally; n = {tb_c['presentations_used']} presentations)\n")
    if not tb_c["presentations_used"]:
        lines.append(f"N/A: {tb_c['unavailable_reason']}.\n")
    else:
        lines.append("| Metric | Cursor (primary) norm | Cursor px | Model (diagnostic) norm | Model px |")
        lines.append("|---|---|---|---|---|")
        rows = [("Mean of presentation mean errors", lambda t: t["presentation_mean_error"]["mean"]),
                ("Median of presentation mean errors", lambda t: t["presentation_mean_error"]["median"]),
                ("p95 of presentation mean errors", lambda t: t["presentation_mean_error"]["p95"]),
                ("Median of presentation median errors", lambda t: t["presentation_median_error_median"]),
                ("Median of presentation p95 errors", lambda t: t["presentation_p95_error_median"]),
                ("Mean centroid error", lambda t: t["centroid_error_mean"]),
                ("Mean X bias (+ right)", lambda t: t["bias_x_mean"]),
                ("Mean Y bias (+ down)", lambda t: t["bias_y_mean"]),
                ("Mean jitter RMS", lambda t: t["jitter_rms_mean"])]
        for name, get in rows:
            c, mo = get(tb_c), get(tb_m)
            lines.append(f"| {name} | {_f(c['norm'])} | {_f(c['px'], 1)} | {_f(mo['norm'])} | {_f(mo['px'], 1)} |")
        if tb_c["pixel_unavailable_reason"]:
            lines.append(f"\nPixel values N/A: {tb_c['pixel_unavailable_reason']}.")
        lines.append("")
    pc, pm = agg["pooled_sensitivity"]["cursor"], agg["pooled_sensitivity"]["model"]
    lines.append(f"**Pooled-sample sensitivity view** (every valid measurement sample weighted equally, including "
                 f"insufficient presentations; n = {pc['samples_used']} samples)\n")
    if not pc["samples_used"]:
        lines.append(f"N/A: {pc['unavailable_reason']}.\n")
    else:
        lines.append(f"Samples from insufficient or partial presentations: {pc['samples_from_insufficient_presentations']}.\n")
        lines.append("| Metric | Cursor norm | Cursor px | Model norm | Model px |")
        lines.append("|---|---|---|---|---|")
        for name, key in (("Mean error", "mean_error"), ("Median error", "median_error"), ("p95 error", "p95_error"),
                          ("X bias (+ right)", "bias_x"), ("Y bias (+ down)", "bias_y"), ("Jitter RMS", "jitter_rms")):
            lines.append(f"| {name} | {_f(pc[key]['norm'])} | {_f(pc[key]['px'], 1)} | {_f(pm[key]['norm'])} | {_f(pm[key]['px'], 1)} |")
        lines.append("")
    if plots.get(agg["mode"]):
        lines.append(f"Supplementary plots, pooled across the {len(agg['sessions_included'])} included session(s) of this mode. "
                     "The error histogram weights camera frames, not presentations; the visual summary above is the primary view.\n")
    for path in plots.get(agg["mode"], []):
        lines.append(f"![{path}]({path})")
    lines.append("")
    return lines


def _visual_summary_section(public: dict, plots: dict, sessions: list[dict]) -> list[str]:
    out = ["## Visual summary", "",
           "One figure per session; sessions and modes are never combined in these figures. "
           "Build provenance is stated at the top of this report, not inside the figures.", ""]
    for mode in MODES:
        mine = [s for s in sessions if s["mode"] == mode]
        if not mine:
            continue
        role = "primary" if mode == PRIMARY_MODE else "reported separately; a visible cursor can change where the participant looks"
        out += [f"### {MODE_TITLES[mode]} ({role})", ""]
        included = set(public["modes"][mode]["sessions_included"])
        for s in mine:
            label = "" if s["session_id"] in included else " (excluded from mode summaries: incomplete session; diagnostic only)"
            out.append(f"**Session `{s['session_id']}`**{label}\n")
            out += [f"![{p}]({p})" for p in plots.get(f"summary:{s['session_id']}", [])]
            out += ["", f"- {edge_value_note(s.get('_edge_values', {}))}", ""]
    return out


def markdown_report(public: dict, plots: dict, sessions: list[dict] | None = None) -> str:
    th = public["thresholds"]
    out = ["# NoTouch gaze evaluation: target-referenced error report", ""]
    out.append(f"- Source/build label: **{public['source_label']}**" if public["source_label"]
               else "- Source/build label: **not supplied**. The exact source revision of the tested APK is unknown.")
    out.append(f"- Tool: analyze_gaze_eval {public['tool_version']}; log schema {public['supported_schema_versions']}")
    out.append(f"- Sufficiency rule: ≥ {th['min_valid_samples']} valid measurement samples **and** ≥ {th['min_valid_coverage']:g} "
               f"valid coverage per presentation; incomplete sessions {'included (diagnostic only)' if public['include_incomplete'] else 'excluded from summaries'}")
    out.append(f"- Sessions analyzed: {len(public['sessions'])}; rejected: {len(public['rejected_sessions'])}")
    out += ["", "## How to read this report", "",
            "- **Targets are the instructed fixation points, not independently verified ground truth.** No eye tracker "
            "measured where the participant actually looked, so every spatial value is a *target-referenced gaze error*.",
            "- Invalid inference attempts are **excluded from spatial error** (never imputed or carried forward) and "
            "**included in availability statistics** (coverage, dropout).",
            "- `features_unavailable` combines an eye closed, an eye too small, and too few landmarks. "
            "**It cannot be identified specifically as blinking.**",
            "- Settling-phase samples (first 1,000 ms of each target) are excluded from accuracy and jitter.",
            "- Statistics appear at three levels: **per presentation** (presentations.csv), **trial-balanced** "
            "(equal weight per eligible presentation; primary) and **pooled samples** (sensitivity only).",
            "- Units: normalized = fraction of the coordinate basis; px uses the session's recorded basis "
            "(dx × width, dy × height). No visual-angle conversion. Positive X = right, positive Y = down.",
            "- Results describe the tested participant(s), phone(s) and conditions only; they do not generalize.",
            "- No pass/fail threshold is defined for these values.", ""]
    out += _visual_summary_section(public, plots, sessions if sessions is not None else public["sessions"])
    out += [f"## Primary result: {PRIMARY_MODE}", ""]
    out += _mode_section(public["modes"][PRIMARY_MODE], plots)
    out += ["## Separate result: live_preview", "",
            "Reported separately: a visible cursor can change where the participant looks. Never combine with the blind result.", ""]
    out += _mode_section(public["modes"]["live_preview"], plots)
    out += ["## Sessions", "",
            "| Session | Mode | State (end reason) | Device | Presentations done/planned | Eligible / insufficient / incomplete "
            "| Coverage | Valid/s | Rows lost | Issues |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for s in public["sessions"]:
        c, m, dev = s["presentation_status_counts"], s["measurement"], s["device"] or {}
        lost = (s["logging"] or {}).get("rows_lost_total")
        out.append(f"| `{s['session_id']}` | {s['mode']} | {s['final_state']} ({s['end_reason']}) | "
                   f"{dev.get('manufacturer')} {dev.get('model')} | {s['presentations_completed']}/{s['presentations_planned']} | "
                   f"{c['eligible']} / {c['insufficient_data']} / {c['incomplete_presentation']} | {_pct(m['valid_coverage'])} | "
                   f"{_f(m['valid_samples_per_s'], 1)} | {'N/A' if lost is None else lost} | {s['issue_count']} |")
    out += ["", "### Availability and pipeline counters", "",
            "Inference-attempt coverage (above) is separate from analyzer frame counters (below), which come from metadata.", "",
            "| Session | Frames received | Cadence-skipped | Ignored (closing) | Inference attempts | Unrecorded attempts | "
            "Settling attempts / invalid | Measurement invalid by reason |",
            "|---|---|---|---|---|---|---|---|"]
    for s in public["sessions"]:
        k = s["pipeline_counters"] or {}
        sd = s["settling_dropout"]
        reasons = ", ".join(f"`{r}` {n}" for r, n in s["measurement"]["invalid_by_reason"].items() if n) or "none"
        na = lambda key: "N/A" if k.get(key) is None else k[key]  # noqa: E731
        out.append(f"| `{s['session_id']}` | {na('frames_received')} | {na('frames_cadence_skipped')} | "
                   f"{na('frames_ignored_service_closing')} | {na('inference_attempts')} | {na('attempts_unrecorded')} | "
                   f"{sd['attempts']} / {sd['attempts'] - sd['valid']} | {reasons} |")
    if any(not s["pipeline_counters"] for s in public["sessions"]):
        out.append("\nN/A counters: the session was interrupted before metadata was finalized.")
    issues = [s for s in public["sessions"] if s["issue_count"]]
    if issues or public["rejected_sessions"]:
        out += ["", "## Data issues", ""]
        for r in public["rejected_sessions"]:
            out.append(f"- **Rejected** `{r['path']}`: {r['error']}")
        for s in issues:
            out.append(f"- `{s['session_id']}`: {s['issue_count']} issue(s); unassigned rows {s['unassigned_rows']}. "
                       f"First: {s['issues'][0]}")
    # Per-session coverage charts are internal diagnostics (plots/coverage_*.svg) and are not embedded;
    # coverage is reported in the tables above and on the visual summary's coverage card.
    out += ["", "## Definitions", ""] + [f"- **{k}**: {v}" for k, v in public["definitions"].items()]
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- CLI

def _coverage(text: str) -> float:
    value = float(text)
    if not 0 < value <= 1:
        raise argparse.ArgumentTypeError("must be in (0, 1]")
    return value


def _positive_int(text: str) -> int:
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError("must be >= 1")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("input", type=Path, help="a session directory, or a directory containing session directories")
    parser.add_argument("--out", type=Path, required=True, help="output directory (must not be inside session data)")
    parser.add_argument("--source-label", help="manual source/build label, e.g. 'feature/gaze-accuracy-evaluation @006d2bc + uncommitted'")
    parser.add_argument("--min-valid-samples", type=_positive_int, default=DEFAULT_MIN_VALID_SAMPLES,
                        help=f"per-presentation minimum valid measurement samples (default {DEFAULT_MIN_VALID_SAMPLES})")
    parser.add_argument("--min-valid-coverage", type=_coverage, default=DEFAULT_MIN_VALID_COVERAGE,
                        help=f"per-presentation minimum valid coverage in (0, 1] (default {DEFAULT_MIN_VALID_COVERAGE})")
    parser.add_argument("--include-incomplete", action="store_true",
                        help="include incomplete sessions in mode summaries (labelled diagnostic only)")
    args = parser.parse_args(argv)
    if not args.input.is_dir():
        parser.error(f"input {args.input} is not a directory")
    if not discover(args.input):
        parser.error(f"no session directories (with samples.csv or metadata.json) under {args.input}")
    summary = run(args.input, args.out, Thresholds(args.min_valid_samples, args.min_valid_coverage),
                  args.source_label, args.include_incomplete)
    for r in summary["rejected_sessions"]:
        print(f"rejected {r['path']}: {r['error']}", file=sys.stderr)
    print(f"analyzed {len(summary['sessions'])} session(s), rejected {len(summary['rejected_sessions'])}; "
          f"wrote {args.out / 'report.md'}")
    return 1 if summary["rejected_sessions"] else 0


if __name__ == "__main__":
    sys.exit(main())
