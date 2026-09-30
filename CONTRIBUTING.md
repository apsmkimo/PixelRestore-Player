# Contributing to PixelRestore Player

Thanks for helping improve PixelRestore Player. The app is a local, on-device video player. Playback and image processing stay on the phone.

## License

This project is Apache-2.0. See [LICENSE](LICENSE). By submitting a contribution you agree it can be distributed under that license.

Jetpack Media3, Jetpack Compose, AndroidX, and Kotlin are also Apache-2.0. Do not add a dependency whose license is incompatible with Apache-2.0 (for example GPL) without an explicit maintainer decision recorded in the README.

## Hard rules

- Keep processing on-device. Do not add network access, accounts, analytics, crash reporters, or remote inference.
- Do not add AI or machine-learning libraries. That includes TensorFlow Lite, ONNX Runtime, PyTorch Mobile, ML Kit, downloaded model weights, and cloud vision or LLM APIs.
- Do not describe mosaic restoration as recovering pixels that the encoder permanently discarded. It is mosaic artifact reduction / visual restoration.
- If a control is visible but the filter is not actually running, label it **Not yet implemented** in the UI and in this repository's docs.
- Prefer a real OpenGL ES or CPU implementation over a stub.

## Development setup

- JDK 17 or newer (the project bytecode target is 17).
- Android SDK platform 36 and build-tools 36.x. Set `ANDROID_HOME` or create `local.properties` with `sdk.dir`.
- Android Studio with AGP 9.4 support (current stable Android Studio as of September 2026).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Pull requests

1. Branch from `main`.
2. Keep the change focused.
3. Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`.
4. Describe what the filter actually does. Name the shader or CPU function.
5. Update the README known-limitations section if behavior changes.

## Project layout

Kotlin sources live under `app/src/main/java/com/pixelrestore/player/`:

- `ui` — Compose player and settings
- `player` — Media3 playback and the CPU YUV fallback
- `processing` — `ProcessingManager` and the single active processor
- `gpu` — EGL, shaders, textures
- `device` — capability detection and frame-time tracking
- `settings` — DataStore preferences
