#!/usr/bin/env bash
# Builds the whole-country street maps for RAFIKI Pin from OpenStreetMap (Geofabrik country extracts).
#
#   ./build.sh                       all four countries
#   ./build.sh kenya                 one country
#   ./build.sh test                  the small made-up test area (no download; see test/make-test-data.py)
#
# Needs Docker (uses the Planetiler image), or Java 21+ with USE_JAVA=1 (downloads planetiler.jar once).
# Writes out/<country>-<date>.pmtiles and out/countries.json; upload both to the R2 bucket (README.md).
# The first run also downloads the sea outlines (about 900 MB, from osmdata.openstreetmap.de) and each country
# (100-700 MB, from download.geofabrik.de); later runs reuse them from data/sources unless REFRESH=1.
set -euo pipefail
cd "$(dirname "$0")"
PLANETILER_VERSION=0.10.2
MEM=${MEM:-6g}
DATE=$(date -u +%Y-%m-%d)
mkdir -p out data/sources data/tmp
COUNTRIES=("$@"); [ ${#COUNTRIES[@]} -eq 0 ] && COUNTRIES=(kenya uganda tanzania malawi)

planetiler() {
  if [ -z "${USE_JAVA:-}" ] && command -v docker >/dev/null 2>&1; then
    docker run --rm -e JAVA_TOOL_OPTIONS="-Xmx$MEM" -v "$PWD":/work -w /work "ghcr.io/onthegomap/planetiler:$PLANETILER_VERSION" "$@"
  else
    [ -f planetiler.jar ] || curl -fL -o planetiler.jar "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar"
    java "-Xmx$MEM" -jar planetiler.jar "$@"
  fi
}

for c in "${COUNTRIES[@]}"; do
  case "$c" in kenya|uganda|tanzania|malawi|test) ;; *) echo "Unknown country: $c (kenya, uganda, tanzania, malawi or test)"; exit 1;; esac
  out="out/$c-$DATE.pmtiles"
  extra=(--download)
  if [ "$c" = test ]; then
    python3 test/make-test-data.py data/sources; extra=()
  elif [ -n "${REFRESH:-}" ]; then
    rm -f "data/sources/$c.osm.pbf"
  fi
  echo "== $c → $out"
  start=$(date +%s)
  planetiler generate-custom --schema=rafiki-country.yml --area="$c" --output="$out" --tmpdir=data/tmp --force "${extra[@]}"
  echo "== $c: $(du -h "$out" | cut -f1) in $(( $(date +%s) - start )) s"
done
node manifest.mjs out
echo "Done. Upload out/countries.json and the new .pmtiles files (README.md, step 3)."
