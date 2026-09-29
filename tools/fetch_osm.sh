#!/usr/bin/env bash
# Downloads one map's OpenStreetMap data (via the Overpass API) into tools/data/<id>_*.json.
#   bash tools/fetch_osm.sh <id>        (the area comes from tools/maps.txt)
# The public Overpass servers are often busy, so each part is retried across mirrors until it
# succeeds. Map data © OpenStreetMap contributors, ODbL.
set -u
id="${1:?usage: fetch_osm.sh <map id from tools/maps.txt>}"
here="$(cd "$(dirname "$0")" && pwd)"
line=$(grep -E "^$id[[:space:]]" "$here/maps.txt") || { echo "unknown map $id"; exit 1; }
read -r _ south west north east _ _ _ <<< "$line"
BBOX="$south,$west,$north,$east"
mkdir -p "$here/data"
cd "$here/data"

SERVERS=(https://overpass-api.de/api/interpreter https://overpass.kumi.systems/api/interpreter https://overpass.private.coffee/api/interpreter)

declare -A PARTS
PARTS[buildings]="way[\"building\"]($BBOX);relation[\"building\"]($BBOX);way[\"amenity\"=\"place_of_worship\"]($BBOX);"
PARTS[roads]="way[\"highway\"]($BBOX);way[\"area:highway\"]($BBOX);"
PARTS[areas]="way[\"leisure\"~\"park|garden|pitch|playground|marina\"]($BBOX);way[\"landuse\"~\"grass|recreation_ground|religious|construction\"]($BBOX);way[\"natural\"~\"coastline|water|tree_row|scrub|wood|sand|beach|bare_rock\"]($BBOX);way[\"place\"=\"square\"]($BBOX);way[\"amenity\"=\"parking\"]($BBOX);way[\"man_made\"~\"pier|breakwater\"]($BBOX);node[\"natural\"=\"tree\"]($BBOX);"

for part in buildings roads areas; do
  out="${id}_$part.json"
  if [ -s "$out" ] && head -c 20 "$out" | grep -q '{'; then echo "$id $part: already downloaded"; continue; fi
  query="[out:json][timeout:90];(${PARTS[$part]});out geom tags;"
  done=0
  for attempt in $(seq 1 15); do
    for server in "${SERVERS[@]}"; do
      curl -s -m 150 -A "BeirutRunGame/1.0 (map build for an Android game)" -H "Accept: application/json" \
        --data-urlencode "data=$query" -o "$out.tmp" "$server"
      if head -c 20 "$out.tmp" | grep -q '{' && grep -q '"elements"' "$out.tmp"; then
        mv "$out.tmp" "$out"
        echo "$id $part: OK from $server (attempt $attempt, $(wc -c < "$out") bytes)"
        done=1
        break 2
      fi
      echo "$id $part: $server busy (attempt $attempt)"
    done
    sleep 30
  done
  rm -f "$out.tmp"
  [ "$done" = 1 ] || { echo "$id $part: FAILED after retries"; exit 1; }
done
