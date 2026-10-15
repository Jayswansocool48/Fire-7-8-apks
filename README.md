# Apps

Android apps for the Fire 7 (7th gen) and Fire HD 8 (8th gen) on LineageOS.
Every time the code changes, GitHub rebuilds the APKs below automatically.

| App | Download | What it is |
| --- | --- | --- |
| **Inkwell** | [Inkwell.apk](Inkwell.apk) | Drawing app in the style of ibisPaint / Procreate |
| **Codeling** | [Codeling.apk](Codeling.apk) | Learn-to-code lessons with a creature that grows as you do |

## Install on the tablet
1. Open this repo in the tablet's browser and tap the APK you want.
2. Tap **Download** (or **View raw**), then open the file.
3. Allow installs from that source when LineageOS asks, then tap **Install**.

Or from a computer: `adb install -r Codeling.apk`

## Repo layout
| Folder | Contents |
| --- | --- |
| `inkwell/` | Inkwell source: native Kotlin, no libraries |
| `codeling/` | Android wrapper for Codeling; the web app itself is in `codeling/web-src/web/` |
| `tools/prepare-web.mjs` | Rewrites Codeling's JavaScript and CSS so it runs on older WebViews (Android 7) |

## Codeling notes
- Runs fully offline from inside the APK. Progress is saved on the tablet.
- The Python course downloads its Python runtime the first time, so it needs internet once.
- Documentation links open in the browser.
- The web files here are a copy of the `codeling` repo's `web/` folder (version 2.0.0).
  To update the APK after changing Codeling, copy the new `web/` folder into
  `codeling/web-src/web/` and push.
- On Android 7 the built-in web engine may be old. Codeling still runs, but if some screens
  look cramped, updating **Android System WebView** fixes it.

## Building locally
Open this folder in Android Studio, or run `./gradlew assembleRelease`.
APKs land in `inkwell/build/outputs/apk/` and `codeling/build/outputs/apk/`.
