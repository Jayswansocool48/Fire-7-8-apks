#!/usr/bin/env bash
# Adds three.js (Kiln's 3D viewer) to web-src/web/vendor before the APK is built.
# The web app itself is copied from the Kiln repo; this only fetches the 3D library, same version as Kiln's setup.sh.
set -e; cd "$(dirname "$0")/web-src/web"; V=0.160.0
MIRRORS=("https://cdn.jsdelivr.net/npm/three@$V" "https://unpkg.com/three@$V")
FILES=(build/three.module.js)
for f in loaders/GLTFLoader controls/OrbitControls controls/TransformControls utils/BufferGeometryUtils \
         utils/TextureUtils environments/RoomEnvironment exporters/GLTFExporter; do
  FILES+=("examples/jsm/$f.js"); done
mkdir -p vendor/addons/{loaders,controls,utils,environments,exporters}
for f in "${FILES[@]}"; do
  out="vendor/${f#build/}"; out="${out/examples\/jsm/addons}"
  [ -s "$out" ] && continue
  ok=""; for m in "${MIRRORS[@]}"; do curl -fsSL "$m/$f" -o "$out" && { ok=1; break; }; done
  [ -n "$ok" ] || { echo "Could not download $f"; exit 1; }
done
echo "three.js $V ready in web-src/web/vendor"
