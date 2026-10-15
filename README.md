# Inkwell

A lightweight ibisPaint/Procreate-style drawing app, built for the Fire 7 (7th gen) and
Fire HD 8 (8th gen) running LineageOS. Native Kotlin, no libraries, minSdk 21.

## Install
Download **[Inkwell.apk](Inkwell.apk)** from this repo onto the tablet, open it, and allow
installs from that source when LineageOS asks. It's rebuilt automatically every time the
code changes.

## Features
- Brushes: ink pen, grainy pencil, marker (even opacity within a stroke), airbrush, eraser
- Stabilizer (stroke smoothing), stylus pressure if your pen reports it
- Up to 16 layers: add, delete, reorder, merge down, clear, hide, opacity, rename
- Paint bucket fill and eyedropper
- Color wheel with presets and recent colors
- Pan / pinch-zoom / rotate with two fingers, flip canvas
- 2-finger tap = undo, 3-finger tap = redo (memory-capped history)
- Autosaves when you leave the app; export PNG to `Pictures/Inkwell`
- Import a photo as a layer for tracing

## Build on Pop!_OS

### Option A: Android Studio (easiest)
1. Install Android Studio (Pop!_Shop / Flathub, or the tarball from developer.android.com).
2. **File → Open** and choose this `Inkwell` folder. Let Gradle sync; it downloads
   the SDK pieces it needs. Accept any SDK licence prompts.
3. Plug in the tablet (see "Prepare the tablet"), pick it in the device menu, press **Run ▶**.

### Option B: command line
Needs JDK 17 and the Android SDK (Android Studio installs one at `~/Android/Sdk`).

```bash
export ANDROID_HOME=$HOME/Android/Sdk
cd Inkwell
chmod +x gradlew
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a smaller, faster build use `./gradlew assembleRelease` instead; the APK lands in
`app/build/outputs/apk/release/` (signed with the debug key so it installs directly).

## Prepare the tablet (LineageOS)
1. Settings → About tablet → tap **Build number** 7 times.
2. Settings → System → Developer options → turn on **Android debugging**.
3. Connect USB, run `adb devices`, and accept the prompt on the tablet.

If `adb devices` says `no permissions`, install the udev rules and replug:
`sudo apt install android-sdk-platform-tools-common`.

## Gestures
| Action | Gesture |
|---|---|
| Draw | 1 finger or stylus |
| Move / zoom / rotate | 2 fingers |
| Undo / redo | tap with 2 / 3 fingers |
| Brush size & opacity | drag the left sliders up/down |
| Pick brush, stabilizer | tap the brush button again |
| Rename layer | tap the selected layer |

## Code map
| File | What it does |
|---|---|
| `Brush.kt` | Brush presets and the stroke renderer (dabs → alpha buffer → layer) |
| `CanvasView.kt` | Drawing surface, touch and gestures, cached layer compositing |
| `PaintDocument.kt` | Layers, merge, fill, eyedropper, flatten |
| `History.kt` | Undo/redo with a memory budget |
| `ProjectStore.kt` | Autosave and PNG export |
| `MainActivity.kt` | Toolbar, sliders, color picker, menus |
| `LayersPanel.kt` | Layers list |
| `Widgets.kt`, `Icons.kt` | Custom buttons, sliders, color picker, code-drawn icons |
