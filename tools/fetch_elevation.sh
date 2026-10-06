#!/usr/bin/env bash
# Downloads the ground heights for one map with hills (see OsmToCity's Terrain) into
# tools/data/elevation/: the free "Terrarium" elevation tiles of AWS Open Data (Mapzen, from
# SRTM and others), zoom 14, covering the map's area.
#   bash tools/fetch_elevation.sh <id>        (the area comes from tools/maps.txt)
set -eu
id="${1:?usage: fetch_elevation.sh <map id from tools/maps.txt>}"
here="$(cd "$(dirname "$0")" && pwd)"
line=$(grep -E "^$id[[:space:]]" "$here/maps.txt") || { echo "unknown map $id"; exit 1; }
read -r _ south west north east _ <<< "$line"
mkdir -p "$here/data/elevation"
cd "$here/data/elevation"
z=14
# One tile past each edge, for smoothing and bilinear sampling at the borders.
awk -v s="$south" -v w="$west" -v n="$north" -v e="$east" -v z=$z '
  function tx(lon) { return int((lon + 180) / 360 * 2^z) }
  function ty(lat) { r = lat * 3.14159265358979 / 180; return int((1 - log(sin(r) / cos(r) + 1 / cos(r)) / 3.14159265358979) / 2 * 2^z) }
  BEGIN { for (x = tx(w) - 1; x <= tx(e) + 1; x++) for (y = ty(n) - 1; y <= ty(s) + 1; y++) print x, y }' |
while read -r x y; do
  f="terrarium_${z}_${x}_${y}.png"
  [ -s "$f" ] && continue
  curl -s -f -m 60 -o "$f" "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png" || { echo "tile $x $y failed"; rm -f "$f"; exit 1; }
  echo "$id: elevation tile $x,$y"
done
echo "$id: elevation tiles ready"
