# CameraX Pro & AR Filter Engine for Android

A complete native Android camera application built with Jetpack CameraX 1.3.0, GPUImage OpenGL ES filters, and Google MediaPipe Face Mesh AR.

## Features Included
- **Jetpack CameraX**: ImageCapture and VideoCapture with Start, Pause, Resume, and Stop.
- **Real-Time GPU Shaders**: GPUImage filter presets (Cinematic, Vintage, B&W, HDR, Cyberpunk) and live exposure compensation.
- **MediaPipe AR Lenses**: 468 3D landmark tracking with low-latency AR Visors and Face Meshes.
- **MediaStore Scoped Storage**: Automatic saving to device `Pictures/` and `Movies/` galleries.
- **Pro Controls**: Tap-to-focus metering, pinch-to-zoom, flash modes, countdown timer, and burst mode.

## Opening in Android Studio
1. Clone or download this repository.
2. Open **Android Studio** (Hedgehog 2023.1.1 or newer).
3. Select **File > Open** and choose the root directory of this project.
4. Let Gradle sync dependencies and click **Run** to launch on a physical Android device or emulator (API 24+).
