# NoTouch Iteration 2 — Device validation

Status: pending. No Android device was listed by ADB on 2026-10-18.
Build: NoTouch-0.7.0-debug.apk. No IR camera is available.

## Setup

Connect the phone over USB, enable USB debugging, and authorize this computer
on the phone. Record device, Android version, orientation, glasses, lighting,
camera-to-face distance, and model label. Use a fixed phone mount and portrait
orientation. Grant camera/overlay permissions; grant accessibility and microphone
permissions only for the action tests. Keep cloud experiments off for baseline
measurements so results describe the local tracker.

First test the uncalibrated default. Then collect separate training sessions using
the existing touch-collection screen and train the personal model. Record the
model label after loading it. A completed training operation is not evidence that
accuracy improved. Do not reuse training targets as the sole evaluation data.

## Pointer tests

1. Horizontal response: fix head and phone; alternate looking at left, center,
   right targets ten times. Record wrong-direction movement, inability to reach
   a target, and tracking loss separately.
2. Fixation: look at each point in a 3-by-3 grid for three seconds, repeat three
   times. Use a separate evaluation session and exclude the first 500 ms after
   each target change. Record target and raw/filtered prediction when collecting
   quantitative results; a visual observation alone is qualitative evidence.
3. Head movement: keep looking at the center target while gently turning and
   translating the head. Record pointer drift and OUT_OF_RANGE/lost periods.
4. Distance: repeat center/grid trials at measured 30, 40, and 50 cm under the
   same lighting. Record estimated distance separately from measured distance.
   A missing distance estimate is unavailable data, not zero distance.

For numeric evaluation, report median and 95th-percentile Euclidean error in
screen-normalized units, valid-frame coverage, and fixation jitter (RMS distance
from the mean prediction within each fixation). Report raw and filtered results
separately. Normalized error is not visual-angle error. Compare baseline and
personal model on the same held-out conditions; include tracking failures in
coverage rather than silently excluding them. No accuracy threshold or result
has been established yet.

## Interaction and recovery tests

| Test | Expected behavior | Observed result |
| --- | --- | --- |
| Ordinary short blink | No click | Not tested |
| Hold both eyes closed 1 second | Arm the previous valid target | Not tested |
| Reopen, then close 1 second again | One click at the armed target | Not tested |
| Wait more than 6 seconds after arming | Cancel the pending click | Not tested |
| Lose face after arming | Cancel; no click on recovery | Not tested |
| Look beyond top/bottom edge | Scroll after dwell; no click | Not tested |
| Two short blinks | Start voice input when permitted | Not tested |
| Disable actions | Pointer preview only; pending click cancelled | Not tested |
| Open data-collection screen | No eye actions or pointer overlay | Not tested |
| Interrupt camera | Hide stale pointer and cancel pending action | Not tested |
| Resume camera | New observations required before actions | Not tested |
| Rotate display | Stop and request restart for that configuration | Not tested |

Use the app's practice screen for action tests before other apps. Record each
failure with steps, model label, approximate distance, and whether the tracker
reported lost/out-of-range. Device acceptance is incomplete until these tests
are actually performed. Automated unit tests do not fill the result column.

## Submission wording

We implemented a front-camera pipeline combining MediaPipe iris landmarks,
head-pose features, an optional distance estimate, local gaze mapping, temporal
smoothing, and blink/scroll/voice interactions. The pipeline passed 41 automated
unit tests and produced an Android debug APK. Physical-device accuracy and
interaction acceptance remain to be measured. IR acquisition, iris-texture
tracking, and dual-camera fusion are future work because the team has not yet
acquired an IR camera.
