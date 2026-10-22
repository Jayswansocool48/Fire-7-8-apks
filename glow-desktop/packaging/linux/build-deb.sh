#!/bin/sh
# Builds glow_<version>_<arch>.deb from a compiled binary.
#   packaging/linux/build-deb.sh <binary> <amd64|arm64> <version> <out-dir>
set -e
BIN=$1 ARCH=$2 VERSION=$3 OUT=$4
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(mktemp -d)
chmod 755 "$ROOT"
install -Dm755 "$BIN" "$ROOT/usr/bin/glow"
install -Dm644 "$HERE/glow.desktop" "$ROOT/usr/share/applications/glow.desktop"
install -Dm644 "$HERE/glow.png" "$ROOT/usr/share/icons/hicolor/256x256/apps/glow.png"
install -Dm644 "$HERE/60-glow-aitoled.rules" "$ROOT/usr/lib/udev/rules.d/60-glow-aitoled.rules"
install -Dm755 "$HERE/postinst" "$ROOT/DEBIAN/postinst"
SIZE=$(du -sk "$ROOT/usr" | cut -f1)
cat > "$ROOT/DEBIAN/control" <<CTRL
Package: glow
Version: $VERSION
Architecture: $ARCH
Maintainer: Jayswansocool48 <noreply@github.com>
Installed-Size: $SIZE
Section: utils
Priority: optional
Depends: bluez
Recommends: gstreamer1.0-tools, gstreamer1.0-pipewire, gstreamer1.0-plugins-base
Description: LED light control for AITOLED screen-sync lights and Bluetooth strips
 Syncs an AITOLED monitor backlight to your screen, controls LEDBLE /
 ELK-BLEDOM (Lotus Lantern) Bluetooth LED strips, and serves the Glow
 page that the Glow tablet app and any browser on your Wi-Fi can use.
CTRL
mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$ROOT" "$OUT/glow_${VERSION}_${ARCH}.deb"
rm -rf "$ROOT"
