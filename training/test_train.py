"""Synthetic tests verify plumbing, not human gaze accuracy."""
import csv
import tempfile
import unittest
from pathlib import Path

import numpy as np
import train


def session(session_id, participant, count=60, shift=0):
    rows = []
    for i in range(count):
        x, y = .12 + .76 * (i % 5) / 4, .15 + .7 * ((i // 5) % 5) / 4
        values = [(x - .5) * .3, (y - .5) * .2, (x - .5) * .29, (y - .5) * .21,
                  .5 + shift, .5, .25, 0, .2, 0]
        rows.append(dict(schema_version="2", participant=participant, session=session_id,
                         config_key="test|phone|1080x2400|0|features-v2", target_index=str(i),
                         frame_count="8", target_x=str(x), target_y=str(y),
                         **{key: str(value) for key, value in zip(train.FEATURES, values)}))
    return rows


class TrainingTest(unittest.TestCase):
    def test_holdout_does_not_affect_fit_or_scaler(self):
        rows = session("a", "P01") + session("b", "P02", shift=.2)
        learning, held = train.split_rows(rows, test_participants=["P02"])
        model = train.fit(learning)
        self.assertEqual({r["session"] for r in learning}, {"a"})
        self.assertAlmostEqual(model["mean"][4], .5)
        self.assertEqual(train.evaluate(model, held)["coverage"], 0)

    def test_quadratic_export_predicts_unseen_coordinates(self):
        model = train.fit(session("a", "P01"))
        x = np.array([[-.054, .042, -.0522, .0441, .5, .5, .25, 0, .2, 0]])
        np.testing.assert_allclose(train.predict(model, x)[0], [.32, .71], atol=.01)
        self.assertTrue(train.supported(model, x)[0])

    def test_csv_deduplicates_cumulative_exports_and_rejects_mixed_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = [Path(tmp) / "one.csv", Path(tmp) / "two.csv"]
            rows = session("a", "P01")
            def write(path, data):
                with path.open("w", newline="", encoding="utf-8") as stream:
                    writer = csv.DictWriter(stream, fieldnames=data[0].keys())
                    writer.writeheader(); writer.writerows(data)
            for path in paths:
                write(path, rows)
            self.assertEqual(len(train.read_rows(paths)), 60)
            other = session("b", "P02")
            for row in other:
                row["config_key"] = "different-screen"
            write(paths[1], other)
            with self.assertRaisesRegex(ValueError, "one device"):
                train.read_rows(paths)

    def test_unknown_or_empty_holdout_rejected(self):
        rows = session("a", "P01")
        for held in ([], ["unknown"], ["a"]):
            with self.assertRaises(ValueError):
                train.split_rows(rows, test_sessions=held)

    def test_full_export_metadata_excludes_test_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "data.csv"
            rows = session("a", "P01") + session("b", "P01")
            with path.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader(); writer.writerows(rows)
            model, report = train.train([path], test_sessions=["b"])
            self.assertEqual(model["training"]["sessions"], ["a"])
            self.assertEqual(report["held_out_sessions"], ["b"])
            self.assertLess(report["held_out"]["all_predictions"]["rmse_normalized"], .01)


if __name__ == "__main__":
    unittest.main()
