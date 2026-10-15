#!/bin/sh
# Builds deskmate_<version>_<arch>.deb from a compiled binary.
#   packaging/linux/build-deb.sh <binary> <amd64|arm64> <version> <out-dir>
set -e
BIN=$1 ARCH=$2 VERSION=$3 OUT=$4
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(mktemp -d)
chmod 755 "$ROOT"
install -Dm755 "$BIN" "$ROOT/usr/bin/deskmate"
install -Dm644 "$HERE/deskmate.desktop" "$ROOT/usr/share/applications/deskmate.desktop"
install -Dm644 "$HERE/deskmate.png" "$ROOT/usr/share/icons/hicolor/256x256/apps/deskmate.png"
install -Dm644 "$HERE/60-deskmate-uinput.rules" "$ROOT/usr/lib/udev/rules.d/60-deskmate-uinput.rules"
install -Dm644 "$HERE/deskmate-uinput.conf" "$ROOT/usr/lib/modules-load.d/deskmate-uinput.conf"
install -Dm755 "$HERE/postinst" "$ROOT/DEBIAN/postinst"
SIZE=$(du -sk "$ROOT/usr" | cut -f1)
cat > "$ROOT/DEBIAN/control" <<CTRL
Package: deskmate
Version: $VERSION
Architecture: $ARCH
Maintainer: Jayswansocool48 <noreply@github.com>
Installed-Size: $SIZE
Section: utils
Priority: optional
Recommends: wl-clipboard | xclip, playerctl
Description: Stream Deck-style companion for the Deskmate tablet app
 Turns an Android tablet into a Stream Deck for this PC: hotkeys, apps,
 media keys, live system stats, a focus timer and a game dev panel.
CTRL
mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$ROOT" "$OUT/deskmate_${VERSION}_${ARCH}.deb"
rm -rf "$ROOT"
