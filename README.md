# Apps

Android apps for the Fire 7 (7th gen) and Fire HD 8 (8th gen) on LineageOS.
Every time the code changes, GitHub rebuilds the APKs below automatically.

| App | Download | What it is |
| --- | --- | --- |
| **Inkwell** | [Inkwell.apk](Inkwell.apk) | Drawing app in the style of ibisPaint / Procreate |
| **Codeling** | [Codeling.apk](Codeling.apk) | Learn-to-code lessons with a creature that grows as you do |
| **Deskmate** | [Deskmate.apk](Deskmate.apk) + [desktop program](../../releases/tag/deskmate-desktop) | Turns the tablet into a macro pad, system monitor, media remote, notes/timer and game dev panel for your PC |

## Install on the tablet
1. Open this repo in the tablet's browser and tap the APK you want.
2. Tap **Download** (or **View raw**), then open the file.
3. Allow installs from that source when LineageOS asks, then tap **Install**.

Or from a computer: `adb install -r Codeling.apk`

## Deskmate setup

**1. On your PC**, download the desktop program from the [deskmate-desktop release](../../releases/tag/deskmate-desktop):

| PC | File |
| --- | --- |
| Linux (Pop!_OS, Ubuntu, Fedora, Arch, …) | `deskmate-linux-x86_64` |
| Linux on ARM | `deskmate-linux-arm64` |
| Windows | `deskmate-windows-x64.exe` |

Linux, in a terminal:
```bash
cd ~/Downloads
chmod +x deskmate-linux-x86_64
mkdir -p ~/.local/bin && mv deskmate-linux-x86_64 ~/.local/bin/deskmate
deskmate --setup      # once: lets Deskmate press keys (asks for your password)
deskmate              # start it; the settings page opens in your browser
```
Windows: double-click `deskmate-windows-x64.exe` and click **Allow** if the firewall asks
(private networks). If SmartScreen warns about an unknown app, choose **More info → Run anyway**.

Tick **Start Deskmate when I log in** on the settings page so it's always ready.

**2. On the tablet**, open Deskmate, pick your PC and type the 6-digit code shown on the PC.

### What each tool needs on Linux
Everything works out of the box on Pop!_OS. On other distros, these optional packages fill in the extras:

| Feature | Package |
| --- | --- |
| Volume slider, mute, mic mute | `wireplumber` (has `wpctl`) or `pulseaudio-utils` (`pactl`) |
| Song title, reliable play/pause | `playerctl` |
| Clipboard sharing | `wl-clipboard` (Wayland) or `xclip` (X11) |
| GPU stats | NVIDIA driver (`nvidia-smi`) or AMD's `amdgpu` driver |

### Macro buttons
Edit them on the PC in the settings page (`http://127.0.0.1:47801`). Each button can press keys
(`ctrl+shift+t`, `super+ctrl+down`, `f5`), run a command, open a link or folder, or type text.
The tablet can only press the buttons you set up there, so nobody on your Wi-Fi can run anything else.

### Game Dev Panel
Every slider, switch or button change is sent to your game as one UDP message on the PC,
`{"name":"speed","value":3.5}`, to `127.0.0.1:47810` (change the port in the settings page).
Godot 4 listener; add it as an autoload:

```gdscript
extends Node
signal tweak(name: String, value: Variant)

var udp := PacketPeerUDP.new()

func _ready() -> void:
	udp.bind(47810, "127.0.0.1")

func _process(_delta: float) -> void:
	while udp.get_available_packet_count() > 0:
		var data = JSON.parse_string(udp.get_packet().get_string_from_utf8())
		if data is Dictionary:
			tweak.emit(data.name, data.value)
```
Then in any script: `Tweaks.tweak.connect(func(n, v): if n == "speed": speed = v)`.

## Repo layout
| Folder | Contents |
| --- | --- |
| `inkwell/` | Inkwell source: native Kotlin, no libraries |
| `codeling/` | Android wrapper for Codeling; the web app itself is in `codeling/web-src/web/` |
| `deskmate/` | Deskmate tablet app (Kotlin, no libraries) |
| `deskmate-desktop/` | Deskmate desktop program (Go, no dependencies; Linux and Windows) |
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
APKs land in each app's `build/outputs/apk/` folder.
Desktop program: `cd deskmate-desktop && go build` (Go 1.22+).
