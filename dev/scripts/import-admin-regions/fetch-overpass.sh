#!/usr/bin/env bash
# Fetch Indonesian administrative boundaries from OpenStreetMap via Overpass API.
#
# Outputs (into ./data/, gitignored):
#   provinces.geojson       — admin_level=4 relations inside Indonesia
#                             (~38 rows, one per provinsi including DKI Jakarta)
#   kabupaten-kota.geojson  — admin_level=5 relations inside Indonesia
#                             (~514 rows: kabupaten + kota + the 5 DKI kotamadya
#                              + Kepulauan Seribu — no DKI special-case needed,
#                              they all appear at level 5 uniformly)
#
# Original scaffold fetched a third DKI-kotamadya file at admin_level=6, but
# that level is kecamatan (sub-district), not kotamadya — the DKI kotamadya
# are already at level 5 like every other kabupaten/kota. Verified against
# Overpass 2026-04 (Jakarta Pusat relation 7625977 has admin_level=5).
#
# Each file is GeoJSON FeatureCollection with one feature per OSM relation. The
# feature's `id` field is the stable OSM relation ID used as admin_regions.id.
# The `properties.name` field is the Indonesian-language name from OSM's `name` tag.
#
# Design Open Question 1 resolved to OSM; rationale + attribution in design.md.
# Attribution (ODbL): "Administrative boundaries © OpenStreetMap contributors,
# available under the Open Database License (ODbL)." — goes in V11 header.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/data"
mkdir -p "${DATA_DIR}"

OVERPASS_URL="${OVERPASS_URL:-https://overpass-api.de/api/interpreter}"
# Indonesia's OSM area ID: relation 304751 → area 3600304751 (OSM convention:
# area = 3600000000 + relation_id for relations promoted to areas). Verified
# 2026-04 via `relation(304751);out tags;` → {name: Indonesia, ISO3166-1: ID}.
# (The scaffold's original value 304716 pointed at an Indian relation; wrong country.)
INDONESIA_AREA="3600304751"

# Per-query timeout in seconds. Overpass default is 30s which is not enough for
# Indonesia-wide level-5 queries; 600s matches typical community practice for
# country-scale extractions.
TIMEOUT=600

# Overpass API etiquette: identify yourself via User-Agent so the operators can
# contact you if a query misbehaves. This is a one-shot script, not a service.
USER_AGENT="nearyou-id-admin-regions-import/1.0 (contact: aditrioka@gmail.com)"

fetch() {
    local name="$1"
    local query="$2"
    local out="${DATA_DIR}/${name}.geojson"

    echo ">> Fetching ${name}..."
    # Overpass returns native JSON (not GeoJSON). The generate-seed.py step
    # reads the `elements[].members[].geometry` structure and reassembles
    # MULTIPOLYGON from the way members. We don't convert to GeoJSON here
    # because Overpass-turbo-style conversion loses relation metadata.
    curl -s \
        --fail-with-body \
        -H "User-Agent: ${USER_AGENT}" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "data=${query}" \
        "${OVERPASS_URL}" \
        -o "${out}"

    # Sanity check: the response must be valid JSON with non-empty `elements`.
    local element_count
    element_count=$(jq '.elements | length' "${out}")
    if [[ "${element_count}" == "0" ]]; then
        echo "ERROR: ${name} returned zero elements. Query likely timed out on the server; retry later or raise TIMEOUT." >&2
        exit 1
    fi
    echo "   → ${out}: ${element_count} relations"
}

# Level-4: provinces. Output used to compute the national coastline
# (ST_Boundary(ST_Union(provinces))) for the coastal-kabupaten heuristic.
fetch "provinces" "
[out:json][timeout:${TIMEOUT}];
relation[admin_level=4][boundary=administrative][\"type\"=\"boundary\"](area:${INDONESIA_AREA});
out geom;
"

# Level-5: kabupaten/kota. This is the primary dataset; ~514 rows expected
# (includes the 5 DKI kotamadya + Kepulauan Seribu naturally — no DKI special-
# case query needed, verified Jakarta Pusat is at admin_level=5 alongside
# Kota Bandung / Kabupaten Bandung / etc.).
fetch "kabupaten-kota" "
[out:json][timeout:${TIMEOUT}];
relation[admin_level=5][boundary=administrative][\"type\"=\"boundary\"](area:${INDONESIA_AREA});
out geom;
"

echo ""
echo "Fetched two GeoJSONs into ${DATA_DIR}/"
echo "Next: python3 generate-seed.py --out <path-to-V12-or-V11-seed.sql>"
