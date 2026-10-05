#!/usr/bin/env bash
# Builds the people walking in the street (assets/models/pedestrians/) from Mixamo downloads:
#
#   tools/models/pedestrians/characters/<name>.fbx   one per person (any Mixamo character)
#   tools/models/pedestrians/animations/*.fbx        Walking, Running, Standing Idle, Talking On A Cell Phone
#
# Each character is converted with FBX2glTF, cut to about a third of its triangles and given
# 1024 px WebP textures with gltfpack (they're animated on the phone, so they must be light),
# and has its bones renamed to the plain "mixamorig:" names the animations use. The animations
# are shared by everyone and stripped to the bones' motion.
#
#   bash tools/build_pedestrians.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=tools/models/pedestrians
OUT=app/src/main/assets/models/pedestrians
TMP=build/pedestrians
# The plain gson jar Gradle downloaded (not its instrumented copies).
GSON=$(find ~/.gradle/caches/modules-2 -name "gson-2.*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -1)
# Fraction of each character's triangles to keep.
KEEP=${KEEP:-0.3}

mkdir -p "$TMP" "$OUT/animations"

for fbx in "$SRC"/characters/*.fbx; do
  name=$(basename "$fbx" .fbx | tr '[:upper:]' '[:lower:]' | sed 's/_nonpbr$//; s/[^a-z0-9]/_/g')
  echo "== $name"
  tools/bin/FBX2glTF.exe -b -i "$fbx" -o "$TMP/$name" > /dev/null
  tools/bin/gltfpack.exe -i "$TMP/$name.glb" -o "$TMP/${name}_small.glb" -noq -si "$KEEP" -tw -tl 1024 -tl normal,attrib 32
  mkdir -p "$OUT/$name"
  java -cp "$GSON" tools/RenameMixamoBones.java "$TMP/${name}_small.glb" "$OUT/$name/character.glb"
done

# Shared animations: the file each clip is made from.
anim() {
  local from="$SRC/animations/$1.fbx" to="$2"
  [ -f "$from" ] || { echo "missing $from"; return; }
  tools/bin/FBX2glTF.exe -b -i "$from" -o "$TMP/anim_$to" > /dev/null
  java -cp "$GSON" tools/StripAnimation.java "$TMP/anim_$to.glb" "$OUT/animations/$to.glb"
}
anim "Standing Idle" idle
anim "Talking On A Cell Phone" phone
anim "Walking" walk
# "Running.fbx" turned out to be a running flip; "Running (1).fbx" is the run.
anim "Running (1)" run

ls -la "$OUT"/*/
