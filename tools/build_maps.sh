#!/usr/bin/env bash
# Downloads (if needed) and converts maps from tools/maps.txt into app/src/main/assets/maps/.
#   bash tools/build_maps.sh              all maps
#   bash tools/build_maps.sh hamra souks  just these
# Needs Java 17+ (Gson is taken from the Gradle cache). Run from anywhere.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/.."
gson=$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name 'gson-2*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)
[ -n "$gson" ] || { echo "Gson not found in the Gradle cache; build the app once first."; exit 1; }
command -v cygpath >/dev/null && gson=$(cygpath -w "$gson")

ids=("$@")
[ ${#ids[@]} -gt 0 ] || mapfile -t ids < <(grep -vE '^\s*(#|$)' "$here/maps.txt" | awk '{print $1}')
for id in "${ids[@]}"; do
  read -r _ south west north east lat lon source terrain <<< "$(grep -E "^$id[[:space:]]" "$here/maps.txt")"
  [ -n "${source:-}" ] && [ "$source" != "-" ] || source="$id"
  bash "$here/fetch_osm.sh" "$source"
  [ "${terrain:-}" = "terrain" ] && bash "$here/fetch_elevation.sh" "$id"
  java -cp "$gson" "$here/OsmToCity.java" "$id" "$south" "$west" "$north" "$east" "$lat" "$lon" "$source" "${terrain:-flat}"
done
