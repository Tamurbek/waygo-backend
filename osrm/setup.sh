#!/usr/bin/env bash
# One-time setup for the self-hosted OSRM (routing) instance.
#
# Run this ONCE on the production host, in this directory
# (waygo_backend/osrm), before starting the `osrm` service in
# docker-compose.prod.yml. It downloads Uzbekistan's road network from
# Geofabrik (an OpenStreetMap extract mirror, no account/API key needed)
# and pre-processes it into the .osrm files osrm-routed serves from.
#
# Re-run it periodically (e.g. monthly, via cron) to pick up real-world
# road changes — OSRM serves whatever was in the extract at processing
# time, it does not update itself.
#
# Disk/RAM note: the Uzbekistan .osm.pbf itself is under 100MB, but
# osrm-partition/osrm-customize are RAM-hungry — budget at least 2GB free
# RAM and a few GB free disk for a country-sized extract.
set -euo pipefail
cd "$(dirname "$0")"

REGION_URL="https://download.geofabrik.de/asia/uzbekistan-latest.osm.pbf"
PBF_FILE="uzbekistan-latest.osm.pbf"
OSRM_IMAGE="osrm/osrm-backend"

echo "==> Downloading $REGION_URL"
curl -L -o "$PBF_FILE" "$REGION_URL"

echo "==> Extracting (osrm-extract, car profile)"
docker run --rm -t -v "$PWD:/data" "$OSRM_IMAGE" \
  osrm-extract -p /opt/car.lua "/data/$PBF_FILE"

echo "==> Partitioning (osrm-partition)"
docker run --rm -t -v "$PWD:/data" "$OSRM_IMAGE" \
  osrm-partition "/data/${PBF_FILE%.osm.pbf}.osrm"

echo "==> Customizing (osrm-customize)"
docker run --rm -t -v "$PWD:/data" "$OSRM_IMAGE" \
  osrm-customize "/data/${PBF_FILE%.osm.pbf}.osrm"

echo "==> Done. Start/restart the osrm service:"
echo "    docker compose -f docker-compose.prod.yml up -d osrm"
echo "==> Then point the admin panel's 'OSRM zaxira server manzili' at:"
echo "    https://backend.waygo.uz/osrm   (routed through the gateway, see gateway.conf)"
