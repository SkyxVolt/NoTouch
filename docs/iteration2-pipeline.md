# Iteration 2 — P4 implementation and integration notes

Owner assignment: Kim Gyulim + Anna. Updated: 2026-10-18.

## Confirmed iteration scope

The team has not acquired an IR camera (confirmed by the user). The current
iteration baseline is the phone's front RGB camera, MediaPipe, and a locally
trained personal mapping using head pose and estimated distance. IR capture,
iris-texture tracking, and RGB/IR fusion are deferred hardware-dependent work.
Implementation and automated verification are available; physical-device
acceptance and measured gaze accuracy remain pending. See
`iteration2-device-validation.md` for the repeatable test protocol.

## Implemented Android path

CameraX front RGB camera (latest frame, single analysis worker)
→ upright bitmap
→ bundled MediaPipe Face Landmarker (478 landmarks and face transform)
→ LandmarkFeatures (iris displacement, eye openness, face position/scale,
roll, head yaw/pitch, optional iris-size distance estimate)
→ GazePipeline (local predictor, validity checks, temporal smoothing)
→ InteractionEngine
→ overlay and Android accessibility actions / voice input.

The pipeline is running application code, not a design-only diagram. It uses
the existing bundled face landmark model. The default pointer uses a heuristic
mapping; this is not a pretrained high-accuracy screen gaze estimator. A saved
personal model is loaded by ModelStore if compatible with the device/display.
Distance-aware regression requires collected sessions and training; no fabricated
user weights or accuracy measurements are included.

## New frontend contract

`TrackerState.sample: GazeSample?` is updated on the main thread before the
existing `TrackerState.listener` notification. Consumers should render state,
not replay `sample.actions`: TrackingService already executes these actions.

- `timestampMs`: analysis-start uptime in milliseconds, NOT camera exposure time.
- `quality`: TRACKING, CLOSED, UNCERTAIN, LOST, OUT_OF_RANGE, or STALE.
- `features`: valid open-eye features, otherwise null. Distance can be NaN.
- `rawPoint`: finite local prediction before temporal smoothing.
- `point`: filtered normalized screen coordinates, origin at top left.
- `armedPoint`: fixed click-confirmation target, if one is pending.
- `actions`: actions emitted for this sample, for diagnostics only.

Coordinates intentionally extend outside [0,1] for scroll detection. Clamp only
for drawing, not before passing coordinates to the interaction state machine.
Sample becomes null on service reset/shutdown. Existing features/status/listener
fields remain available for current screens and touch-data collection.

## Tracking and interaction behavior

The local predictor is the sole source for the red gaze pointer. Optional cloud
context recommendations can select a click candidate, but do not replace gaze
coordinates. Cloud face-analysis requests remain experimental samples; they do
not control the pointer.

Frames more than 250 ms late, future timestamps, and duplicate/out-of-order
timestamps are rejected. Gaps over 250 ms and model replacement reset temporal
state. Invalid/non-finite predictions cannot reach the pointer or gestures.
Camera errors, the watchdog, and screen-change cancellation clear pending
interactions and the filter. Closed eyes are distinct from missing face detection.

One continuous 1-second closure arms a click at the last valid target; a second
1-second closure confirms it. The existing 6-second timeout, two-short-blink
dictation, and off-screen vertical dwell scrolling remain in place. Disabling
actions permits pointer preview but cancels an armed click. Data collection
suppresses actions and overlay drawing.

## IR integration boundary — not implemented or verified

No USB camera product/SDK or Android test device has been confirmed for this
task. This build uses the phone's RGB front camera; it does not acquire IR frames,
track iris texture, run EllSeg, or perform two-camera fusion. P4 must not be marked
fully complete if those are its acceptance requirements.

Next hardware gate: verify USB permission, frame acquisition/format, usable eye
resolution at the intended distance, and concurrent USB + front-camera capture.
Use acquisition timestamps in a shared clock for fusion; the current analysis
timestamps must not be mistaken for synchronized exposure timestamps. A separate
IR feature schema and a newly trained mapping are required: do not feed IR pixel
coordinates into existing RGB regression weights. Missing/stale IR should report
unavailable instead of silently reusing old eye measurements.

## Validation and remaining acceptance work

GazePipelineTest covers local/candidate separation, stale cancellation, face loss
and recovery, disabled actions, non-finite output, model changes, gaps/reset,
off-screen scrolling, and invalid timestamp order. Existing regression, geometry,
interaction, and cloud protocol tests remain applicable.

Build with `testDebugUnitTest assembleDebug lintDebug`. Device acceptance still
requires: pointer tracking on a phone, camera interruption/recovery, long-blink
confirmation, off-screen scrolling, double-blink voice input, training on real
touch sessions, and measured held-out error. Build/unit-test success alone does
not establish gaze accuracy or physical-device behavior.

Verified on 2026-10-18: 41 unit tests passed (0 failures/errors/skips), debug APK
assembled successfully, Android lint reported 0 errors and 30 warnings. No ADB
device was attached. APK: app/build/outputs/apk/debug/NoTouch-0.7.0-debug.apk.
