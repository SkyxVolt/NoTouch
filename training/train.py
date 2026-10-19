"""Train the Android feature-v2 model from deliberate developer capture sessions."""
import argparse
import csv
import json
from pathlib import Path

import numpy as np

FEATURES = ["left_x", "left_y", "right_x", "right_y", "face_x", "face_y",
            "scale", "yaw_proxy", "pitch_proxy", "roll"]


def read_rows(paths):
    rows, seen, owners = [], {}, {}
    for path in paths:
        with Path(path).open(encoding="utf-8-sig", newline="") as stream:
            for line, row in enumerate(csv.DictReader(stream), 2):
                try:
                    if row["schema_version"] != "2":
                        raise ValueError("expected schema_version 2")
                    if not row["session"] or not row["participant"] or not row["config_key"]:
                        raise ValueError("missing session, participant or configuration")
                    owner = (row["participant"], row["config_key"])
                    if owners.setdefault(row["session"], owner) != owner:
                        raise ValueError("session has inconsistent participant/configuration")
                    numeric = np.array([float(row[k]) for k in FEATURES + ["target_x", "target_y"]])
                    if not np.isfinite(numeric).all() or (np.abs(numeric) >= 1e6).any():
                        raise ValueError("invalid numeric value")
                    if not ((numeric[-2:] >= 0) & (numeric[-2:] <= 1)).all():
                        raise ValueError("target outside screen")
                    if int(row["frame_count"]) < 5 or int(row["target_index"]) < 0:
                        raise ValueError("invalid sample count/index")
                    key = (row["session"], int(row["target_index"]))
                    if key in seen:
                        if seen[key] != row:
                            raise ValueError("conflicting duplicate sample")
                        continue  # Repeated cumulative exports must not overweight old samples.
                    seen[key] = row
                    rows.append(row)
                except (ValueError, KeyError, TypeError) as error:
                    raise ValueError(f"{path}:{line}: {error}") from error
    if not rows:
        raise ValueError("no samples found")
    if len({r["config_key"] for r in rows}) != 1:
        raise ValueError("use one device/screen/orientation configuration per model")
    return rows


def split_rows(rows, test_sessions=(), test_participants=()):
    if bool(test_sessions) == bool(test_participants):
        raise ValueError("select either held-out sessions or held-out participants")
    key, chosen = ("session", set(test_sessions)) if test_sessions else ("participant", set(test_participants))
    if not chosen <= {r[key] for r in rows}:
        raise ValueError("held-out identifier not present in data")
    train = [r for r in rows if r[key] not in chosen]
    test = [r for r in rows if r[key] in chosen]
    if len(train) < 50 or len(test) < 10:
        raise ValueError("need at least 50 training touches and 10 held-out touches")
    return train, test


def arrays(rows):
    return (np.array([[float(r[k]) for k in FEATURES] for r in rows]),
            np.array([[float(r["target_x"]), float(r["target_y"])] for r in rows]))


def basis(z):
    return np.column_stack((np.ones(len(z)), z, z * z))


def fit(rows, alpha=1.0):
    if not np.isfinite(alpha) or alpha <= 0:
        raise ValueError("alpha must be positive and finite")
    x, y = arrays(rows)
    if (np.ptp(y, axis=0) < .5).any():
        raise ValueError("training targets must cover both screen axes")
    if (np.std(x[:, :4], axis=0) < .003).any():
        raise ValueError("insufficient iris movement; check capture quality")
    mean, std = x.mean(axis=0), np.maximum(x.std(axis=0), .01)
    b = basis((x - mean) / std)
    penalty = np.eye(b.shape[1]) * alpha
    penalty[0, 0] = 0
    weights = np.linalg.solve(b.T @ b + penalty, b.T @ y)
    return dict(schema_version=2, kind="quadratic_ridge", config_key=rows[0]["config_key"],
                feature_names=FEATURES, mean=mean.tolist(), std=std.tolist(),
                min=x.min(axis=0).tolist(), max=x.max(axis=0).tolist(), weights=weights.tolist())


def predict(model, x):
    z = (x - np.array(model["mean"])) / np.array(model["std"])
    return basis(z) @ np.array(model["weights"])


def supported(model, x):
    """Same pose and standardized-feature rejection as Android LearnedModel."""
    lo, hi = np.array(model["min"]), np.array(model["max"])
    margin = np.maximum((hi - lo) * .35, .025)
    pose = ((x[:, 4:] >= (lo - margin)[4:]) & (x[:, 4:] <= (hi + margin)[4:])).all(axis=1)
    z = (x - np.array(model["mean"])) / np.array(model["std"])
    return pose & (np.abs(z) <= 12).all(axis=1)


def evaluate(model, rows):
    x, y = arrays(rows)
    p = predict(model, x)  # Deliberately not clamped: do not hide edge errors.
    valid = supported(model, x)
    error = p - y
    def metrics(e):
        return dict(mean_euclidean_normalized=float(np.linalg.norm(e, axis=1).mean()),
                    rmse_normalized=float(np.sqrt((e * e).sum(axis=1).mean())),
                    mean_abs_x_screen_fraction=float(np.abs(e[:, 0]).mean()),
                    mean_abs_y_screen_fraction=float(np.abs(e[:, 1]).mean()))
    return dict(samples=len(rows), accepted_samples=int(valid.sum()), coverage=float(valid.mean()),
                all_predictions=metrics(error), accepted_predictions=metrics(error[valid]) if valid.any() else None)


def train(paths, test_sessions=(), test_participants=(), alpha=1.0):
    rows = read_rows(paths)
    train_rows, test_rows = split_rows(rows, test_sessions, test_participants)
    model = fit(train_rows, alpha)
    model["training"] = dict(samples=len(train_rows), sessions=sorted({r["session"] for r in train_rows}),
                             participants=sorted({r["participant"] for r in train_rows}), alpha=alpha)
    report = dict(split="participant" if test_participants else "session",
                  held_out_sessions=sorted({r["session"] for r in test_rows}),
                  held_out_participants=sorted({r["participant"] for r in test_rows}),
                  train=evaluate(model, train_rows), held_out=evaluate(model, test_rows),
                  note="Session holdout does not establish accuracy for unseen people. These are screen-coordinate errors, not degrees or distance. Offscreen scrolling is unvalidated extrapolation.")
    return model, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="+", type=Path)
    parser.add_argument("--list", action="store_true", help="list collected session IDs without training")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--test-session", action="append", default=[])
    group.add_argument("--test-participant", action="append", default=[])
    parser.add_argument("--alpha", type=float, default=1.0)
    parser.add_argument("--output", type=Path, default=Path("gaze-model.json"))
    args = parser.parse_args()
    try:
        if args.list:
            rows = read_rows(args.csv)
            for session in sorted({r["session"] for r in rows}):
                samples = [r for r in rows if r["session"] == session]
                print(f"{session}  participant={samples[0]['participant']}  touches={len(samples)}")
            return
        model, report = train(args.csv, args.test_session, args.test_participant, args.alpha)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(model, indent=2, allow_nan=False), encoding="utf-8")
        report_path = args.output.with_suffix(".report.json")
        report_path.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
        print(json.dumps(report, indent=2))
        print(f"Saved {args.output} and {report_path}. Import only the model JSON into Android.")
    except (ValueError, OSError, np.linalg.LinAlgError) as error:
        parser.exit(2, f"Error: {error}\n")


if __name__ == "__main__":
    main()
