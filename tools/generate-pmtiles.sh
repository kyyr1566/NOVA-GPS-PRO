#!/usr/bin/env bash
set -euo pipefail
# Generate PMTiles for Iraqi governorates via planetiler + osmium
# Usage: ./generate-pmtiles.sh [workdir]
# Requires: osmium, java 17, wget, gh

WORKDIR=${1:-./work}
mkdir -p "$WORKDIR" && cd "$WORKDIR"

echo "=== Download Iraq OSM ==="
if [ ! -f iraq-latest.osm.pbf ]; then
  wget https://download.geofabrik.de/asia/iraq-latest.osm.pbf -O iraq-latest.osm.pbf
fi

echo "=== Download planetiler ==="
if [ ! -f planetiler.jar ]; then
  wget https://github.com/onthegomap/planetiler/releases/download/v0.7.0/planetiler.jar -O planetiler.jar
fi

# bbox west,south,east,north per MapPackageManifest
declare -A BBOX=(
  [baghdad]="43.9,32.95,45.2,33.65"
  [basra]="46.8,29.8,48.2,31.1"
  [nineveh]="41.6,35.2,43.8,37.1"
  [erbil]="43.2,35.6,44.8,37.0"
  [najaf]="43.5,30.9,45.0,32.45"
  [karbala]="43.4,32.4,44.6,33.2"
  [kirkuk]="43.6,34.8,44.9,35.8"
  [anbar]="38.9,32.3,43.5,34.7"
  [diyala]="44.3,33.5,45.7,34.8"
  [dhiqar]="45.5,30.6,47.0,31.8"
  [sulaymaniyah]="44.5,35.0,46.0,36.2"
  [babylon]="44.0,32.1,44.9,33.0"
  [wasit]="44.8,32.0,46.5,33.1"
  [maysan]="46.4,31.2,47.6,32.2"
  [muthanna]="43.8,29.6,46.2,31.5"
  [qadisiyah]="44.3,31.3,45.3,32.2"
  [dohuk]="42.5,36.6,43.5,37.4"
  [saladin]="42.8,33.8,44.5,35.5"
  [halabja]="45.4,35.0,46.1,35.4"
)

for id in "${!BBOX[@]}"; do
  bbox=${BBOX[$id]}
  echo "=== $id bbox $bbox ==="
  if [ ! -f "$id.osm.pbf" ]; then
    osmium extract -b "$bbox" iraq-latest.osm.pbf -o "$id.osm.pbf" --overwrite
  fi
  java -Xmx6g -jar planetiler.jar \
    --area=iraq --bounds="$bbox" \
    --output="$id.pmtiles" --nodemap-type=sparsearray --maxzoom=14 --minzoom=0 --force
  ls -lh "$id.pmtiles"
  sha256sum "$id.pmtiles" >> pmtiles-sha256.txt
done

echo "=== Iraq combined ==="
java -Xmx6g -jar planetiler.jar --area=iraq --output=iraq.pmtiles --maxzoom=14 --minzoom=0 --force
ls -lh iraq.pmtiles
sha256sum iraq.pmtiles >> pmtiles-sha256.txt

echo "=== BRouter segments Iraq (6) ==="
mkdir -p segments4
for seg in E35_N30 E40_N30 E45_N30 E35_N35 E40_N35 E45_N35; do
  if [ ! -f "segments4/${seg}.rd5" ]; then
    wget https://brouter.de/brouter/segments4/${seg}.rd5 -O "segments4/${seg}.rd5" || echo "warn $seg failed, try mirror"
  fi
  sha256sum "segments4/${seg}.rd5" >> routing-sha256.txt || true
done

cat pmtiles-sha256.txt
cat routing-sha256.txt
ls -lh *.pmtiles segments4/

echo "=== Upload to GitHub Releases (requires gh auth) ==="
echo "gh release create maps-v1 --title 'Offline Maps v1' --notes '19 governorates PMTiles'"
echo "gh release upload maps-v1 *.pmtiles --clobber"
echo "gh release create routing-v1 --title 'Routing v1' --notes '6 Iraq rd5'"
echo "gh release upload routing-v1 segments4/*.rd5 --clobber"
