# EdgePro — real-time on-device object detection (Android)

Live camera → COCO object detection with bounding boxes, on-device, with an
on-screen fps / inference-latency HUD. MVP built on CameraX + MediaPipe Tasks
(LiteRT under the hood), model **EfficientDet-Lite0 INT8** (80 COCO classes).

This is a measurement-first baseline, not a finished product. Read the numbers
on your device before making model or resolution decisions.

## Honest performance expectations

The requirement "18–20 fps on **any** Android platform" is not achievable with a
single fixed configuration. Why:

- On Android, MediaPipe/LiteRT gives you a **CPU or GPU** delegate. There is **no
  portable NPU/EdgeTPU path** (unlike the Coral on your Pi). NNAPI, the closest
  thing, is deprecated (Android 15+). So the GPU is the practical ceiling.
- GPU throughput varies ~10× across the Android fleet. EfficientDet-Lite0 INT8 on
  GPU clears 20 fps on a Pixel 9a (Tensor G4) and will miss it on a low-end SoC.

The realistic target: **18–20 fps on mid-range-2022+ or better, degrading
gracefully below** via lower input resolution / frame-skipping. The knobs are in
`MainActivity` (analysis resolution) and `ObjectDetectorHelper` (delegate, model).

## Build

Requires JDK 17 + Android SDK (compileSdk 35). Easiest path: open in Android
Studio (Ladybug or newer) — it provisions the Gradle wrapper automatically.

CLI (with a local Gradle ≥ 8.9 installed):

```bash
gradle wrapper          # once, to generate ./gradlew
./gradlew :app:installDebug
```

The build downloads the .tflite model into `app/src/main/assets/` (needs network
at build time; the file is gitignored, not committed). Offline builds: place
`efficientdet-lite0.tflite` there manually — see the URL in `app/build.gradle.kts`.

## Test on Pixel 9a

1. Enable Developer Options + USB debugging, plug in the phone.
2. `./gradlew :app:installDebug` (or Run in Android Studio).
3. Grant camera permission. Point at COCO objects (person, chair, cup, dog…).
4. Read the HUD (top-left): `fps | inference-ms | object-count`.

## Measure before optimizing

"Good latency / low power" is unfalsifiable without numbers on the target:

- **fps + inference ms** — on the HUD.
- **Sustained perf** — run 5–10 min continuously; watch fps drop as the SoC
  thermally throttles. A 10-second demo hides this.
- **Power / thermal** —
  `adb shell dumpsys batterystats`, Android Studio Energy Profiler, or
  `adb shell dumpsys thermalservice`.

## Tuning levers (highest impact first)

1. **Input resolution** (`MainActivity`, `ResolutionStrategy`): 640×480 → 320×240
   roughly doubles fps. Biggest single power/latency dial.
2. **Delegate** (`ObjectDetectorHelper`): GPU default, auto-falls back to CPU.
3. **Frame skipping**: only enqueue every Nth frame under a weak-GPU tier.
4. **Model**: swap EfficientDet-Lite0 → Lite2 for accuracy, or export
   **YOLO11n → LiteRT INT8** for a better speed/accuracy curve (needs custom
   pre/post-processing; not a drop-in).

## Deferred (out of scope for v1)

Segmentation and counting are separate projects — counting in particular needs
multi-object tracking (ByteTrack/SORT) to avoid double-counting across frames.
Ship detection first.

## Layout

```
app/src/main/java/com/edgepro/app/
  MainActivity.kt          CameraX wiring + fps HUD
  ObjectDetectorHelper.kt  MediaPipe LIVE_STREAM detector, delegate fallback
  OverlayView.kt           bounding-box rendering
```
