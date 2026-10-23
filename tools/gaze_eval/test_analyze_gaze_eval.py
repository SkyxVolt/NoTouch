"""Unit tests for analyze_gaze_eval. All sessions here are SYNTHETIC fixtures, not device results.

Run: python -B -m unittest discover -s tools/gaze_eval -v
"""
from __future__ import annotations

import csv
import io
import json
import math
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

import analyze_gaze_eval as tool

GRID = [0.2, 0.4, 0.6, 0.8]
FIRST_PASS = [f"r{r}c{c}" for r in range(4) for c in (range(4) if r % 2 == 0 else reversed(range(4)))]
ORDER = FIRST_PASS + FIRST_PASS[::-1]
START = 100_000


def target_of(trial):
    r, c = int(ORDER[trial][1]), int(ORDER[trial][3])
    return GRID[c], GRID[r]


def kotlin(v: float) -> str:
    """Kotlin Double.toString style, e.g. 0.2 and 1.0E-4."""
    return repr(v) if 1e-3 <= abs(v) < 1e7 or v == 0 else f"{v:.1E}".replace("E-0", "E-")


def default_rows(trial, phase, k, target):
    """(valid, reason, model_xy, cursor_xy). Synthetic: cursor 0.01 right and 0.02 below the target."""
    x, y = target
    return True, None, (x + 0.012, y + 0.018), (x + 0.01, y + 0.02)


def write_session(root: Path, name: str, *, mode="blind_measurement", final_state="complete", end_reason="completed",
                  rows=default_rows, basis=(1080, 2400), completed=32, finalized=True, measurement_rows=23,
                  settling_rows=15, meta_patch=None, csv_patch=None) -> Path:
    d = root / name
    d.mkdir(parents=True)
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\n")
    w.writerow(tool.CSV_COLUMNS)
    seq = 0
    for trial in range(32):
        onset, target = START + trial * 2500, target_of(trial)
        for phase, n, offset in (("settling", settling_rows, 0), ("measurement", measurement_rows, 1000)):
            for k in range(n):
                spec = rows(trial, phase, k, target)
                if spec is None:
                    continue
                valid, reason, model, cursor = spec
                uptime = onset + offset + 66 * k
                cells = ["1", name, str(seq), str(uptime), str(uptime - START), mode, phase, str(trial // 16 + 1), str(trial),
                         str(trial % 16), ORDER[trial], kotlin(target[0]), kotlin(target[1]), "true" if valid else "false",
                         reason or "", *(kotlin(v) if isinstance(v, float) else (v or "") for v in (model or (None, None))),
                         *(kotlin(v) if isinstance(v, float) else (v or "") for v in (cursor or (None, None))), "18.204"]
                if csv_patch:
                    cells = csv_patch(cells) or cells
                w.writerow(cells)
                seq += 1
    (d / "samples.csv").write_text(out.getvalue(), encoding="utf-8")
    meta = {
        "schema_version": 1, "session_id": name, "mode": mode,
        "final_state": final_state if finalized else "incomplete",
        "end_reason": end_reason if finalized else "in_progress_or_interrupted",
        "start_wall_time_utc": "2026-10-23T08:15:30Z", "end_wall_time_utc": "2026-10-23T08:16:50Z" if finalized else None,
        "start_uptime_ms": START, "end_uptime_ms": START + 80_016 if finalized else None, "planned_end_uptime_ms": START + 80_000,
        "protocol": {"grid_norm": GRID, "target_ms": 2500, "settling_ms": 1000, "measurement_ms": 1500,
                     "presentations_planned": 32, "presentations_completed": completed if finalized else None,
                     "trial_index_base": 0, "index_in_pass_base": 0, "presentation_target_ids": ORDER},
        "device": {"manufacturer": "synthetic", "model": "fixture", "api_level": 35},
        "app": {"version_name": "0.1.0", "version_code": 1, "source_revision": None, "working_tree_dirty": None},
        "geometry": {"basis": "max_window_metrics", "basis_width_px": basis[0] if basis else None,
                     "basis_height_px": basis[1] if basis else None, "evaluation_view_left_px": 0, "evaluation_view_top_px": 0,
                     "evaluation_view_width_px": 1080, "evaluation_view_height_px": 2400, "overlay_width_px": 1080,
                     "overlay_height_px": 2400},
        "counters": {"frames_received": 2400, "frames_cadence_skipped": 1184, "frames_ignored_service_closing": 0,
                     "inference_attempts": seq, "valid_predictions": seq, "invalid_predictions": 0,
                     "invalid_by_reason": {}, "attempts_unrecorded": 0} if finalized else None,
        "logging": {"rows_enqueued": seq, "rows_written": seq, "rows_dropped_queue_full": 0, "rows_dropped_write_error": 0,
                    "rows_lost_total": 0, "write_error": False} if finalized else None,
    }
    if meta_patch:
        meta_patch(meta)
    (d / "metadata.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return d


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.data = self.root / "data"
        self.data.mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def analyze(self, path=None, **kw):
        th = tool.Thresholds(kw.pop("min_valid_samples", 10), kw.pop("min_valid_coverage", 0.5))
        out = self.root / kw.pop("out", "out")
        return tool._public(tool.run(path or self.data, out, th, kw.pop("label", None), kw.pop("include_incomplete", False)))

    def session(self, summary, sid):
        return next(s for s in summary["sessions"] if s["session_id"] == sid)


class StatisticsTest(unittest.TestCase):
    def test_mean_median_and_nearest_rank_p95(self):
        self.assertEqual(tool.mean([1.0, 2.0, 6.0]), 3.0)
        self.assertEqual(tool.median([4.0, 1.0, 3.0, 2.0]), 2.5)
        twenty = [float(i) for i in range(1, 21)]
        self.assertEqual(tool.nearest_rank(twenty, 95), 19.0)          # ceil(0.95 * 20) = 19
        self.assertEqual(tool.nearest_rank(twenty + [21.0], 95), 20.0)  # ceil(0.95 * 21) = 20
        self.assertEqual(tool.nearest_rank([float(i) for i in range(10)], 95), 9.0)
        self.assertEqual(tool.nearest_rank([7.0], 95), 7.0)
        self.assertIsNone(tool.nearest_rank([], 95))
        self.assertIsNone(tool.mean([])); self.assertIsNone(tool.median([]))

    def test_jitter_is_rms_spread_around_own_mean(self):
        a = 0.01
        pts = [(0.5 + a, 0.5 + a), (0.5 - a, 0.5 + a), (0.5 + a, 0.5 - a), (0.5 - a, 0.5 - a)]
        samples = [tool.Sample(i, i, "measurement", 0, True, None, (0.2, 0.2), p, p) for i, p in enumerate(pts)]
        m = tool.spatial_metrics(samples, "cursor", (1000, 2000))
        self.assertAlmostEqual(m["jitter_rms"]["norm"], math.sqrt(2) * a)
        self.assertAlmostEqual(m["jitter_rms"]["px"], math.sqrt((a * 1000) ** 2 + (a * 2000) ** 2))

    def test_sign_convention_radial_error_and_pixel_conversion(self):
        s = tool.Sample(0, 0, "measurement", 0, True, None, (0.2, 0.4), (0.21, 0.42), (0.21, 0.42))
        m = tool.spatial_metrics([s], "cursor", (1000, 2000))
        self.assertAlmostEqual(m["bias_x"]["norm"], 0.01); self.assertAlmostEqual(m["bias_y"]["norm"], 0.02)  # right, below
        self.assertAlmostEqual(m["bias_x"]["px"], 10.0); self.assertAlmostEqual(m["bias_y"]["px"], 40.0)
        self.assertAlmostEqual(m["mean_error"]["norm"], math.hypot(0.01, 0.02))
        self.assertAlmostEqual(m["mean_error"]["px"], math.hypot(10.0, 40.0))
        left_up = tool.Sample(0, 0, "measurement", 0, True, None, (0.2, 0.4), (0.19, 0.38), (0.19, 0.38))
        m = tool.spatial_metrics([left_up], "cursor", None)
        self.assertLess(m["bias_x"]["norm"], 0); self.assertLess(m["bias_y"]["norm"], 0)
        self.assertIsNone(m["mean_error"]["px"]); self.assertIsNone(m["jitter_rms"]["px"])


class ParsingTest(Base):
    def test_parses_exact_stage2_schema(self):
        write_session(self.data, "eval-a")
        s = tool.load_session(self.data / "eval-a", self.data)
        self.assertEqual(len(s.samples), 32 * (15 + 23))
        self.assertEqual(s.issue_count, 0)
        first = s.samples[0]
        self.assertEqual((first.phase, first.trial_index, first.target), ("settling", 0, (0.2, 0.2)))
        self.assertAlmostEqual(first.cursor[0], 0.21); self.assertAlmostEqual(first.cursor[1], 0.22)
        self.assertIsNone(first.invalid_reason); self.assertTrue(first.valid)

    def test_kotlin_scientific_notation_parses(self):
        rows = lambda t, ph, k, tg: (True, None, (1.0e-4, tg[1]), (1.0e-4, tg[1]))  # noqa: E731
        write_session(self.data, "eval-sci", rows=rows)
        self.assertIn("1.0E-4", (self.data / "eval-sci" / "samples.csv").read_text())
        s = tool.load_session(self.data / "eval-sci", self.data)
        self.assertEqual(s.samples[0].cursor[0], 1.0e-4)

    def test_schema_errors_reject_the_session_but_are_reported(self):
        write_session(self.data, "eval-v2", meta_patch=lambda m: m.update(schema_version=2))
        write_session(self.data, "eval-mixed", csv_patch=lambda c: c.__setitem__(1, "other-session"))
        write_session(self.data, "eval-ok")
        (self.data / "eval-nocsv").mkdir()
        (self.data / "eval-nocsv" / "metadata.json").write_text("{}")
        summary = self.analyze()
        rejected = {r["path"]: r["error"] for r in summary["rejected_sessions"]}
        self.assertIn("unsupported schema_version 2", rejected["eval-v2"])
        self.assertIn("does not match metadata", rejected["eval-mixed"])
        self.assertIn("missing samples.csv", rejected["eval-nocsv"])
        self.assertEqual([s["session_id"] for s in summary["sessions"]], ["eval-ok"])
        self.assertIn("**Rejected** `eval-v2`", (self.root / "out" / "report.md").read_text(encoding="utf-8"))

    def test_missing_column_rejects_session(self):
        d = write_session(self.data, "eval-cols")
        text = (d / "samples.csv").read_text().replace("cursor_y_norm,", "", 1)
        (d / "samples.csv").write_text(text)
        with self.assertRaisesRegex(tool.SessionError, "missing columns"):
            tool.load_session(d, self.data)

    def test_nullable_and_malformed_coordinates(self):
        def rows(t, ph, k, tg):
            if t == 0 and ph == "measurement" and k == 0:
                return True, None, (tg[0], tg[1]), ("", "")                       # valid flag, missing cursor
            if t == 0 and ph == "measurement" and k == 1:
                return True, None, (tg[0], tg[1]), ("NaN", "0.2")                 # non-finite
            if t == 0 and ph == "measurement" and k == 2:
                return False, "no_face", None, None                               # normal invalid row, empty cells
            return default_rows(t, ph, k, tg)
        write_session(self.data, "eval-null", rows=rows)
        s = self.session(self.analyze(), "eval-null")
        p0 = s["presentations"][0]["measurement"]
        self.assertEqual(p0["attempts"], 23)
        self.assertEqual(p0["valid_samples"], 20)
        self.assertEqual(p0["invalid_by_reason"]["malformed_row"], 2)
        self.assertEqual(p0["invalid_by_reason"]["no_face"], 1)
        self.assertEqual(s["presentations"][0]["cursor"]["n_samples"], 20)
        self.assertEqual(s["issue_count"], 2)


class SelectionTest(Base):
    def test_settling_rows_do_not_affect_spatial_metrics(self):
        far = lambda t, ph, k, tg: (True, None, (0.99, 0.99), (0.99, 0.99)) if ph == "settling" else default_rows(t, ph, k, tg)  # noqa: E731
        write_session(self.data, "eval-settle", rows=far)
        p = self.session(self.analyze(), "eval-settle")["presentations"][3]
        self.assertAlmostEqual(p["cursor"]["mean_error"]["norm"], math.hypot(0.01, 0.02))
        self.assertEqual(p["settling"]["attempts"], 15)

    def test_invalid_rows_excluded_from_error_but_kept_in_coverage(self):
        def rows(t, ph, k, tg):
            if ph == "measurement" and k < 5:
                return False, "features_unavailable", None, None
            return default_rows(t, ph, k, tg)
        write_session(self.data, "eval-gaps", rows=rows)
        summary = self.analyze()
        s = self.session(summary, "eval-gaps")
        p = s["presentations"][0]
        self.assertEqual(p["measurement"]["attempts"], 23)
        self.assertEqual(p["measurement"]["valid_samples"], 18)
        self.assertAlmostEqual(p["measurement"]["valid_coverage"], 18 / 23)
        self.assertAlmostEqual(p["measurement"]["valid_samples_per_s"], 18 / 1.5)
        self.assertAlmostEqual(p["cursor"]["mean_error"]["norm"], math.hypot(0.01, 0.02))
        self.assertEqual(s["measurement"]["invalid_by_reason"]["features_unavailable"], 5 * 32)

    def test_features_unavailable_is_never_relabelled_as_blink(self):
        rows = lambda t, ph, k, tg: (False, "features_unavailable", None, None) if k == 0 else default_rows(t, ph, k, tg)  # noqa: E731
        write_session(self.data, "eval-features", rows=rows)
        summary = self.analyze()
        reasons = summary["modes"]["blind_measurement"]["measurement"]["invalid_by_reason"]
        self.assertEqual(reasons["features_unavailable"], 32)
        text = (self.root / "out" / "summary.json").read_text(encoding="utf-8") + \
            (self.root / "out" / "presentations.csv").read_text(encoding="utf-8")
        self.assertNotIn("blink", text.replace("cannot be identified specifically as blinking", "")
                         .replace("not identified as blinking", ""))
        self.assertIn("invalid_features_unavailable", (self.root / "out" / "presentations.csv").read_text())

    def test_presentation_with_no_rows_is_reported_not_skipped(self):
        rows = lambda t, ph, k, tg: None if t == 7 else default_rows(t, ph, k, tg)  # noqa: E731
        write_session(self.data, "eval-empty", rows=rows)
        p = self.session(self.analyze(), "eval-empty")["presentations"][7]
        self.assertEqual((p["status"], p["measurement"]["attempts"]), ("insufficient_data", 0))
        self.assertIsNone(p["measurement"]["valid_coverage"])
        self.assertIsNone(p["cursor"])
        self.assertIn("no measurement-phase", p["spatial_unavailable_reason"])


class PixelBasisTest(Base):
    def test_pixel_metrics_use_recorded_basis(self):
        write_session(self.data, "eval-px", basis=(1000, 2000))
        s = self.session(self.analyze(), "eval-px")
        self.assertAlmostEqual(s["presentations"][0]["cursor"]["mean_error"]["px"], math.hypot(10.0, 40.0), places=6)
        self.assertEqual(s["pixel_basis"]["width_px"], 1000)

    def test_pixel_metrics_unavailable_without_valid_basis(self):
        write_session(self.data, "eval-nobasis", basis=None)
        write_session(self.data, "eval-zero", basis=(0, 2400))
        write_session(self.data, "eval-nogeom", meta_patch=lambda m: m.pop("geometry"))
        summary = self.analyze()
        for sid in ("eval-nobasis", "eval-zero", "eval-nogeom"):
            s = self.session(summary, sid)
            self.assertIsNone(s["pixel_basis"])
            self.assertTrue(s["pixel_unavailable_reason"])
            p = s["presentations"][0]["cursor"]
            self.assertIsNone(p["mean_error"]["px"]); self.assertIsNotNone(p["mean_error"]["norm"])
        tb = summary["modes"]["blind_measurement"]["trial_balanced"]["cursor"]
        self.assertIsNone(tb["presentation_mean_error"]["mean"]["px"])
        self.assertIn("pixel basis unavailable", tb["pixel_unavailable_reason"])


class SufficiencyTest(Base):
    def rows_with_valid(self, n_valid, n_attempts):
        def rows(t, ph, k, tg):
            if ph == "measurement":
                if k >= n_attempts:
                    return None
                if k >= n_valid:
                    return False, "no_face", None, None
            return default_rows(t, ph, k, tg)
        return rows

    def status(self, n_valid, n_attempts, **kw):
        name = f"eval-{n_valid}-{n_attempts}-{len(kw)}"
        write_session(self.data, name, rows=self.rows_with_valid(n_valid, n_attempts))
        return self.session(self.analyze(out=name, **kw), name)["presentations"][0]["status"]

    def test_ten_samples_and_half_coverage_boundaries(self):
        self.assertEqual(self.status(9, 9), "insufficient_data")
        self.assertEqual(self.status(10, 20), "eligible")      # exactly 50% coverage
        self.assertEqual(self.status(10, 21), "insufficient_data")
        self.assertEqual(self.status(10, 10), "eligible")

    def test_cli_overrides_are_applied_and_recorded(self):
        write_session(self.data, "eval-cli", rows=self.rows_with_valid(6, 20))
        out = self.root / "cli"
        with redirect_stdout(io.StringIO()):
            code = tool.main([str(self.data), "--out", str(out), "--min-valid-samples", "5",
                              "--min-valid-coverage", "0.3", "--source-label", "synthetic-fixture"])
        self.assertEqual(code, 0)
        summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["thresholds"], {"min_valid_samples": 5, "min_valid_coverage": 0.3})
        self.assertEqual(summary["sessions"][0]["presentations"][0]["status"], "eligible")
        report = (out / "report.md").read_text(encoding="utf-8")
        self.assertIn("≥ 5 valid measurement samples", report)
        self.assertIn("≥ 0.3 valid coverage", report)
        self.assertIn("synthetic-fixture", report)
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            tool.main([str(self.data), "--out", str(out), "--min-valid-coverage", "1.5"])

    def test_insufficient_presentations_kept_and_counted(self):
        def rows(t, ph, k, tg):
            if t < 4 and ph == "measurement" and k >= 5:
                return False, "model_rejected", None, None
            return default_rows(t, ph, k, tg)
        write_session(self.data, "eval-insuf", rows=rows)
        summary = self.analyze()
        s = self.session(summary, "eval-insuf")
        self.assertEqual(len(s["presentations"]), 32)
        self.assertEqual(s["presentation_status_counts"], {"eligible": 28, "insufficient_data": 4, "incomplete_presentation": 0})
        self.assertEqual(s["trial_balanced"]["cursor"]["presentations_used"], 28)
        self.assertEqual(s["pooled_sensitivity"]["cursor"]["samples_from_insufficient_presentations"], 20)
        csv_rows = list(csv.DictReader(io.StringIO((self.root / "out" / "presentations.csv").read_text(encoding="utf-8"))))
        self.assertEqual(len(csv_rows), 32)
        self.assertEqual(csv_rows[0]["status"], "insufficient_data")
        self.assertEqual(csv_rows[0]["cursor_mean_error_norm"], "")   # missing is empty, not 0


class AggregationTest(Base):
    def test_trial_balanced_weights_presentations_equally(self):
        def rows(t, ph, k, tg):
            if ph != "measurement":
                return default_rows(t, ph, k, tg)
            if t == 0:
                return (True, None, (tg[0] + 0.01, tg[1]), (tg[0] + 0.01, tg[1])) if k < 10 else None
            if t == 1:
                return True, None, (tg[0] + 0.05, tg[1]), (tg[0] + 0.05, tg[1])
            return None
        write_session(self.data, "eval-weights", rows=rows, measurement_rows=30)
        s = self.session(self.analyze(), "eval-weights")
        tb, pooled = s["trial_balanced"]["cursor"], s["pooled_sensitivity"]["cursor"]
        self.assertEqual(tb["presentations_used"], 2)
        self.assertAlmostEqual(tb["presentation_mean_error"]["mean"]["norm"], 0.03)
        self.assertEqual(pooled["samples_used"], 40)
        self.assertAlmostEqual(pooled["mean_error"]["norm"], (10 * 0.01 + 30 * 0.05) / 40)

    def test_cursor_and_model_streams_stay_separate(self):
        write_session(self.data, "eval-streams")
        tb = self.session(self.analyze(), "eval-streams")["trial_balanced"]
        self.assertAlmostEqual(tb["cursor"]["bias_x_mean"]["norm"], 0.01)
        self.assertAlmostEqual(tb["model"]["bias_x_mean"]["norm"], 0.012)

    def test_incomplete_sessions_excluded_unless_requested_then_diagnostic(self):
        write_session(self.data, "eval-full")
        write_session(self.data, "eval-back", final_state="incomplete", end_reason="back", completed=10)
        write_session(self.data, "eval-crash", finalized=False,
                      rows=lambda t, ph, k, tg: default_rows(t, ph, k, tg) if t < 6 else None)
        summary = self.analyze()
        blind = summary["modes"]["blind_measurement"]
        self.assertEqual(blind["sessions_included"], ["eval-full"])
        self.assertEqual({e["session_id"] for e in blind["sessions_excluded"]}, {"eval-back", "eval-crash"})
        self.assertFalse(blind["diagnostic_only"])
        back = self.session(summary, "eval-back")
        self.assertEqual((back["final_state"], back["end_reason"], back["presentations_completed"]), ("incomplete", "back", 10))
        self.assertEqual(back["presentation_status_counts"]["incomplete_presentation"], 22)
        crash = self.session(summary, "eval-crash")
        self.assertEqual(crash["end_reason"], "in_progress_or_interrupted")
        self.assertEqual(crash["presentations_completed_source"], "inferred_from_last_logged_sample")
        self.assertEqual(crash["presentations_completed"], 5)  # last sample lies inside presentation 5
        self.assertIsNone(crash["pipeline_counters"])
        diag = self.analyze(out="diag", include_incomplete=True)["modes"]["blind_measurement"]
        self.assertTrue(diag["diagnostic_only"])
        self.assertEqual(len(diag["sessions_included"]), 3)
        self.assertIn("Diagnostic only", (self.root / "diag" / "report.md").read_text(encoding="utf-8"))

    def test_blind_and_live_preview_are_reported_separately(self):
        write_session(self.data, "eval-blind")
        live = lambda t, ph, k, tg: (True, None, (tg[0] - 0.03, tg[1]), (tg[0] - 0.03, tg[1]))  # noqa: E731
        write_session(self.data, "eval-live", mode="live_preview", rows=live)
        modes = self.analyze()["modes"]
        self.assertEqual(modes["blind_measurement"]["sessions_included"], ["eval-blind"])
        self.assertEqual(modes["live_preview"]["sessions_included"], ["eval-live"])
        self.assertAlmostEqual(modes["blind_measurement"]["trial_balanced"]["cursor"]["bias_x_mean"]["norm"], 0.01)
        self.assertAlmostEqual(modes["live_preview"]["trial_balanced"]["cursor"]["bias_x_mean"]["norm"], -0.03)
        self.assertEqual(modes["blind_measurement"]["role"], "primary")
        report = (self.root / "out" / "report.md").read_text(encoding="utf-8")
        self.assertLess(report.index("## Primary result: blind_measurement"), report.index("## Separate result: live_preview"))

    def test_missing_metrics_are_null_with_reason_not_zero(self):
        write_session(self.data, "eval-live-only", mode="live_preview")
        blind = self.analyze()["modes"]["blind_measurement"]
        self.assertEqual(blind["trial_balanced"]["cursor"], {"presentations_used": 0, "unavailable_reason": "no eligible presentations"})
        self.assertEqual(blind["pooled_sensitivity"]["cursor"]["samples_used"], 0)
        self.assertIsNone(blind["measurement"]["valid_coverage"])


class VisualSummaryTest(Base):
    def internal_session(self, name, **kw):
        """Analyzer's internal session dict (keeps fields stripped from JSON, e.g. edge counts)."""
        d = write_session(self.data, name, **kw)
        return tool.analyze_session(tool.load_session(d, self.data), tool.Thresholds())

    def test_target_map_weights_the_two_presentation_means_equally(self):
        # Target r0c0 is trial 0 (pass 1) and trial 31 (pass 2). Pass 1 has 10 frames at +0.01,
        # pass 2 has 30 frames at +0.05: equal weight gives +0.03, pooling frames would give +0.04.
        def rows(t, ph, k, tg):
            if ph == "measurement" and t == 0:
                return (True, None, (tg[0] + 0.01, tg[1]), (tg[0] + 0.01, tg[1])) if k < 10 else None
            if ph == "measurement" and t == 31:
                return True, None, (tg[0] + 0.05, tg[1]), (tg[0] + 0.05, tg[1])
            return default_rows(t, ph, k, tg)
        s = self.internal_session("eval-weights", rows=rows, measurement_rows=30)
        p = next(pt for pt in tool.target_map_points(s) if pt["target_id"] == "r0c0")
        self.assertEqual(p["presentations_used"], 2)
        self.assertEqual(sorted(u["trial_index"] for u in p["presentations"]), [0, 31])
        self.assertAlmostEqual(p["x"], 0.2 + 0.03)
        self.assertAlmostEqual(p["y"], 0.2)

    def test_target_map_has_sixteen_targets_in_grid_order(self):
        points = tool.target_map_points(self.internal_session("eval-map"))
        self.assertEqual([p["target_id"] for p in points], [f"r{r}c{c}" for r in range(4) for c in range(4)])
        self.assertTrue(all(p["presentations_used"] == 2 for p in points))

    def test_paired_plot_has_32_presentation_values_paired_by_target(self):
        unit, rows = tool.paired_presentation_errors(self.internal_session("eval-pairs"))
        self.assertEqual(unit, "px")
        self.assertEqual(len(rows), 16)
        values = [row["passes"][k]["value"] for row in rows for k in (1, 2)]
        self.assertEqual(len(values), 32)
        self.assertTrue(all(v is not None for v in values))
        for row in rows:  # pass 2 reverses pass 1, so a target's trials always sum to 31
            self.assertEqual(row["passes"][1]["trial_index"] + row["passes"][2]["trial_index"], 31)
        self.assertAlmostEqual(rows[0]["passes"][1]["value"], math.hypot(0.01 * 1080, 0.02 * 2400), places=6)

    def test_ineligible_presentation_is_missing_not_zero(self):
        rows = lambda t, ph, k, tg: (False, "no_face", None, None) if t == 5 and ph == "measurement" else default_rows(t, ph, k, tg)  # noqa: E731
        s = self.internal_session("eval-gap", rows=rows)
        _, paired = tool.paired_presentation_errors(s)
        row = next(r for r in paired if r["passes"][1]["trial_index"] == 5)
        self.assertIsNone(row["passes"][1]["value"])
        self.assertIsNotNone(row["passes"][2]["value"])
        point = next(p for p in tool.target_map_points(s) if p["target_id"] == row["target_id"])
        self.assertEqual(point["presentations_used"], 1)
        svg = tool.summary_svg(s, tool.Thresholds().as_dict())
        self.assertIn("pass 1: no eligible data", svg)
        self.assertIn("31 of 32 presentations eligible", svg)

    def test_edge_values_are_reported_as_observed_not_as_clipping(self):
        def rows(t, ph, k, tg):
            if ph == "measurement" and t == 3 and k < 2:
                return True, None, (tg[0], 1.0), (tg[0], 1.0)
            if ph == "measurement" and t == 4 and k == 0:
                return True, None, (tg[0], 1.0), (tg[0], 0.98)
            return default_rows(t, ph, k, tg)
        s = self.internal_session("eval-edge", rows=rows)
        self.assertEqual(s["_edge_values"], {("cursor", "y", 1.0): 2, ("model", "y", 1.0): 3})
        note = tool.edge_value_note(s["_edge_values"])
        self.assertIn("2 cursor values and 3 model values at the bottom edge (y = 1.0)", note)
        self.assertIn("not confirmed clipping events", note)
        self.assertNotIn("lower bound", note)
        self.assertIn("No valid measurement value was at a screen edge", tool.edge_value_note({}))
        self.assertIn("not confirmed clipping events", tool.summary_svg(s, tool.Thresholds().as_dict()))

    def test_summary_svgs_are_wellformed_and_contain_no_provenance(self):
        import xml.etree.ElementTree as ET
        write_session(self.data, "eval-blind-a")
        live = lambda t, ph, k, tg: (True, None, (tg[0] - 0.03, tg[1]), (tg[0] - 0.03, tg[1]))  # noqa: E731
        write_session(self.data, "eval-live-b", mode="live_preview", rows=live)
        marker = "PROVENANCE-MARKER feature/some-branch @abc1234"
        self.analyze(label=marker)
        out = self.root / "out"
        for svg in (out / "plots").glob("*.svg"):
            text = svg.read_text(encoding="utf-8")
            ET.fromstring(text)  # well-formed
            for forbidden in ("PROVENANCE-MARKER", "some-branch", "abc1234", "source_revision", "versionName", "0.1.0"):
                self.assertNotIn(forbidden, text, f"{forbidden!r} leaked into {svg.name}")
            self.assertNotIn("accuracy %", text)
            self.assertNotIn("lower bound", text)
        blind = (out / "plots" / "summary_eval-blind-a.svg").read_text(encoding="utf-8")
        live_svg = (out / "plots" / "summary_eval-live-b.svg").read_text(encoding="utf-8")
        self.assertIn("<title>Blind measurement: cursor position and target-referenced error</title>", blind)
        self.assertIn("<title>Live preview: cursor position and target-referenced error</title>", live_svg)
        self.assertIn("Session eval-blind-a only; not combined with other sessions.", blind)
        self.assertNotIn("eval-live-b", blind)
        report = (out / "report.md").read_text(encoding="utf-8")
        self.assertIn(marker, report)  # provenance stays in the report prose
        visual = report[report.index("## Visual summary"):report.index("## Primary result")]
        self.assertLess(visual.index("### Blind measurement (primary)"), visual.index("### Live preview"))
        self.assertIn("![plots/summary_eval-blind-a.svg](plots/summary_eval-blind-a.svg)", visual)
        self.assertIn("![plots/summary_eval-live-b.svg](plots/summary_eval-live-b.svg)", visual)

    def test_cards_and_reference_lines_come_from_analyzer_output(self):
        s = self.internal_session("eval-cards")
        tb = s["trial_balanced"]["cursor"]
        svg = tool.summary_svg(s, tool.Thresholds().as_dict())
        for name, key in (("mean", "mean"), ("median", "median"), ("p95", "p95")):
            self.assertIn(f"{name} {tb['presentation_mean_error'][key]['px']:.1f} px", svg)
        self.assertIn(f"{tb['presentation_mean_error']['mean']['px']:.1f} px", svg)
        self.assertIn(f"{abs(tb['bias_x_mean']['px']):.1f} px right", svg)
        m = s["measurement"]
        self.assertIn(f"{m['valid_samples']} / {m['attempts']}", svg)
        self.assertIn("32 of 32 presentations eligible under the analyzer's default thresholds", svg)
        self.assertIn("No pass/fail threshold is defined", svg)

    def test_non_default_thresholds_are_not_called_default(self):
        s = self.internal_session("eval-th")
        svg = tool.summary_svg(s, tool.Thresholds(5, 0.3).as_dict())
        self.assertIn("under the thresholds used (≥ 5 valid samples and ≥ 30% valid coverage", svg)
        self.assertNotIn("default thresholds", svg)

    def test_metric_outputs_are_unaffected_by_visual_summary(self):
        write_session(self.data, "eval-same")
        summary = self.analyze()
        public_session = summary["sessions"][0]
        self.assertNotIn("_edge_values", public_session)
        self.assertNotIn("_sample_errors", public_session)


class OutputTest(Base):
    def test_outputs_are_deterministic_and_complete(self):
        write_session(self.data, "eval-1")
        write_session(self.data, "eval-2", mode="live_preview")
        self.analyze(out="a", label="synthetic")
        self.analyze(out="b", label="synthetic")
        files_a = sorted(p.relative_to(self.root / "a").as_posix() for p in (self.root / "a").rglob("*") if p.is_file())
        files_b = sorted(p.relative_to(self.root / "b").as_posix() for p in (self.root / "b").rglob("*") if p.is_file())
        self.assertEqual(files_a, files_b)
        for name in ("summary.json", "presentations.csv", "report.md", "plots/blind_measurement_target_map.svg",
                     "plots/blind_measurement_error_distribution.svg", "plots/live_preview_target_map.svg",
                     "plots/coverage_eval-1.svg", "plots/coverage_eval-2.svg"):
            self.assertIn(name, files_a)
        for name in files_a:
            self.assertEqual((self.root / "a" / name).read_bytes(), (self.root / "b" / name).read_bytes(), name)

    def test_report_contains_required_statements(self):
        write_session(self.data, "eval-r")
        self.analyze()
        report = (self.root / "out" / "report.md").read_text(encoding="utf-8")
        for phrase in ("not independently verified ground truth", "excluded from spatial error",
                       "included in availability statistics", "cannot be identified specifically as blinking",
                       "Source/build label: **not supplied**", "Trial-balanced", "Pooled-sample sensitivity view",
                       "per presentation", "n = 32 presentations"):
            self.assertIn(phrase, report)
        svg = (self.root / "out" / "plots" / "blind_measurement_target_map.svg").read_text(encoding="utf-8")
        self.assertIn("n = 32 presentations, 1 session(s)", svg)

    def test_report_omits_coverage_chart_but_keeps_coverage_values(self):
        gaps = lambda t, ph, k, tg: (False, "features_unavailable", None, None) if ph == "measurement" and k == 0 else default_rows(t, ph, k, tg)  # noqa: E731
        write_session(self.data, "eval-cov", rows=gaps)
        summary = self.analyze()
        out = self.root / "out"
        report = (out / "report.md").read_text(encoding="utf-8")
        self.assertNotIn("Coverage plots", report)
        self.assertNotIn("coverage_eval-cov.svg", report)
        self.assertNotIn("coverage_", report)
        # Still written as an internal diagnostic file.
        self.assertTrue((out / "plots" / "coverage_eval-cov.svg").is_file())
        # Coverage values remain in the report text/tables, summary.json and the summary card.
        m = summary["sessions"][0]["measurement"]
        self.assertEqual((m["valid_samples"], m["attempts"]), (22 * 32, 23 * 32))
        self.assertIn(f"{m['valid_samples']} valid of {m['attempts']} inference attempts (coverage 95.7%", report)
        self.assertIn("| 95.7% |", report)
        self.assertAlmostEqual(json.loads((out / "summary.json").read_text(encoding="utf-8"))
                               ["sessions"][0]["measurement"]["valid_coverage"], 22 / 23)
        card = (out / "plots" / "summary_eval-cov.svg").read_text(encoding="utf-8")
        self.assertIn(f"{m['valid_samples']} / {m['attempts']}", card)
        self.assertIn("95.7% of measurement attempts", card)
        # Every image the report embeds exists, and none is a coverage chart.
        import re
        embedded = re.findall(r"!\[[^\]]*\]\(([^)]+)\)", report)
        self.assertIn("plots/summary_eval-cov.svg", embedded)
        self.assertTrue(all((out / p).is_file() and "coverage" not in p for p in embedded))

    def test_session_data_is_never_modified_and_out_inside_session_refused(self):
        d = write_session(self.data, "eval-ro")
        before = {p.name: p.read_bytes() for p in d.iterdir()}
        self.analyze()
        self.assertEqual(before, {p.name: p.read_bytes() for p in d.iterdir()})
        with self.assertRaises(SystemExit):
            tool.run(d, d / "analysis", tool.Thresholds(), None, False)
        self.assertFalse((d / "analysis").exists())

    def test_cli_exit_code_signals_rejected_sessions(self):
        write_session(self.data, "eval-good")
        write_session(self.data, "eval-bad", meta_patch=lambda m: m.update(mode="unknown"))
        err = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(err):
            code = tool.main([str(self.data), "--out", str(self.root / "o")])
        self.assertEqual(code, 1)
        self.assertIn("rejected eval-bad", err.getvalue())
        self.assertTrue((self.root / "o" / "report.md").exists())

    def test_single_session_directory_input(self):
        d = write_session(self.data, "eval-single")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(tool.main([str(d), "--out", str(self.root / "single")]), 0)
        summary = json.loads((self.root / "single" / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual([s["session_id"] for s in summary["sessions"]], ["eval-single"])


if __name__ == "__main__":
    unittest.main()
