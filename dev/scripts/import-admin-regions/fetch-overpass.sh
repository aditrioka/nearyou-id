#!/usr/bin/env bash
# Fetch Indonesian administrative boundaries from OpenStreetMap via Overpass API.
#
# Outputs (into ./data/, gitignored):
#   provinces.geojson       — admin_level=4 relations inside Indonesia
#   kabupaten-kota.geojson  — admin_level=5 relations inside Indonesia
#                             (EXCLUDES DKI Jakarta's level-5 — we use its
#                              level-6 children instead; filtered in generate-seed.py)
#   dki-kotamadya.geojson   — admin_level=6 relations inside DKI Jakarta
#                             (Jakarta Pusat/Utara/Selatan/Timur/Barat + Kepulauan Seribu)
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
# Indonesia's OSM area ID: relation 304716 → area 3600304716 (OSM convention:
# area = 3600000000 + relation_id for relations promoted to areas).
INDONESIA_AREA="3600304716"

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

# Level-5: kabupaten/kota. This is the primary dataset; ~510-520 rows expected.
# Note: OSM's DKI Jakarta is ALSO at admin_level=5 (as a 'kabupaten-equivalent'
# province), but generate-seed.py detects and skips it — we use the 5 kotamadya
# + Kepulauan Seribu at level 6 instead, per the product spec's DKI special-case.
fetch "kabupaten-kota" "
[out:json][timeout:${TIMEOUT}];
relation[admin_level=5][boundary=administrative][\"type\"=\"boundary\"](area:${INDONESIA_AREA});
out geom;
"

# Level-6 inside DKI: 5 kotamadya + Kepulauan Seribu.
# DKI area ID = 3600000000 + DKI's relation ID. To discover it without hardcoding,
# we first locate DKI's relation by name then substitute the area ID in a
# follow-up query. Overpass supports both in one multi-statement script.
fetch "dki-kotamadya" "
[out:json][timeout:${TIMEOUT}];
// Pin DKI's area (via its relation name — avoids hardcoding a relation ID
// that upstream might renumber).
area[admin_level=4][\"name\"=\"Daerah Khusus Ibukota Jakarta\"]->.dki;
relation[admin_level=6][boundary=administrative][\"type\"=\"boundary\"](area.dki);
out geom;
"

echo ""
echo "Fetched all three GeoJSONs into ${DATA_DIR}/"
echo "Next: python3 generate-seed.py --out <path-to-V11-or-V12-seed.sql>"
