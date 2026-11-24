# JD Android TTS

Offline text-to-speech Android app, built on [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).
No account, no API key, no internet needed after the first model download.

## Voice

- **Engine:** Supertonic 3 (int8), 24 kHz, 31 languages — this app uses English (`en`)
- **Default voice:** **M5 (sid 9)** — warm, storytelling male
- All 10 voices are selectable in the app: F1–F5 female, M1–M5 male
  - M1 lively/upbeat, **M2 deep/calm**, **M3 authoritative**, M4 soft/friendly, **M5 warm/storytelling**

## How it works

1. First run: the app downloads the model itself (~123 MB) —
   `sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2` from the
   sherpa-onnx `tts-models` release — and extracts it on the phone.
   Download shows live progress (% + MB + speed + time left); extraction
   shows % + the file being extracted. There is no download button.
2. After that, type text (or tap the small paste icon above the text
   box to paste from the clipboard), pick a voice and speed, tap **Play**
   (plays through the speaker) or **Save as WAV** (saves to the phone's
   Downloads folder, as `jd-tts-<timestamp>.wav`). Everything runs
   on-device after step 1. UI: Claude-style warm orange (`#D97757`)
   on cream, light theme forced — no download / re-download / Stop
   buttons; the model downloads itself on first launch.

## Build (GitHub Actions)

Every push to `main` builds the debug APK — see
`.github/workflows/build-apk.yml`. Download it from the run's
**Artifacts** (`jd-android-tts-debug-apk`, kept 90 days).

Local build (needs JDK 21 + Android SDK 36):

```sh
./gradlew :app:assembleDebug
```

The sherpa-onnx AAR (v1.13.6, ~48 MB) is downloaded automatically by
Gradle on first build (`fetchSherpaOnnx` in `app/build.gradle`) and is
not committed to the repo.

## Stack

- Gradle 8.14.5, AGP 8.13.2, Kotlin 1.9.23, JDK 21
- compileSdk 36, targetSdk 36, minSdk 26
- sherpa-onnx 1.13.6 (Supertonic support added in 1.12.29)
- Apache Commons Compress 1.26.2 (tar.bz2 extraction)
