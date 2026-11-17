# Lumen Camera (AI Photography Camera V1)

Offline-first Android photography coach. It has Guided and Continuous coaching in the viewfinder, post-capture critique, a non-destructive Film Lab, a Photography Academy and a local Library.

## Build & install
```
./gradlew testDebugUnitTest :domain:test :core:composition:test   # unit tests
./gradlew assembleDebug                                           # app/build/outputs/apk/debug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest                               # data-integrity tests (device)
```

## Modules
| Module | Role |
|---|---|
| `domain` | Pure-Kotlin contracts (SceneSnapshot, CompositionCandidate, CoachingRecommendation, …) |
| `core:composition` | Deterministic engine: candidates, ranking, alignment and hysteresis, opportunities, coaching state machine (unit-tested) |
| `core:camera` | CameraX use cases, capability probe, validated controls, RAW+JPEG capture, orientation, thermal, diagnostics |
| `core:vision` | ML Kit (objects, faces, labels) and custom CV (horizon, lines, exposure, sharpness, symmetry, separation) |
| `core:ai` | Model download/import and checksum, LiteRT-LM engine, prompts, response validator (safety boundary), critic, benchmark |
| `core:image` | Film profiles, renderer, non-destructive developer, MediaStore export |
| `core:data` | Room database (schema exported, migrations from v1), DataStore, file storage layout |
| `app` | Compose UI: camera, review, film lab, academy, library, settings |

## On-device AI
Gemma 4 E2B (`.litertlm`, 2.59 GB, Apache-2.0) is downloaded only when the user starts it, and the download is verified with SHA-256. You can also import the file from storage instead. Everything works without the model. Run **Settings → Manage model → Run benchmark** on the S23 before relying on it.

## Real-device validation checklist (S23)
- [ ] 15-minute Coach-mode session: preview stays smooth, no crash; note thermal state in Diagnostics
- [ ] Same session with the AI model loaded; check suggestion latency and that the model is released when the phone is hot
- [ ] Guided flow without help: choose an option, align, the cue appears, capture, review
- [ ] RAW toggle: a DNG is saved and linked, and export works
- [ ] Manual ISO, shutter, focus and white balance behave as expected on 0.6× / 1× / 3×
- [ ] Compare captures with the Samsung stock camera (HDR, night, multi-frame differences)
- [ ] Airplane mode: camera, coaching, critique, Film Lab, Academy and Library all work
- [ ] Film Lab: export to gallery; the original is unchanged; links survive an app restart
- [ ] Rotate to landscape: overlays and the level line stay correct; the saved photo is upright
