# Self-hosted OSRM (routing fallback)

Used when Yandex's Directions API times out or errors — see
`MapSettings.osrmBaseUrl` (admin panel: "Xarita sozlamalari" →
"OSRM zaxira server manzili"). Falls back further to `router.project-osrm.org`
(a public, no-SLA demo server) until this is set up.

## One-time setup (production host)

```
cd waygo_backend/osrm
./setup.sh
```

Downloads Uzbekistan's road network from Geofabrik (OpenStreetMap extract,
no account/API key needed) and pre-processes it into the `.osrm` files
`osrm-routed` serves from. Needs a few GB free disk and ~2GB free RAM during
processing (`osrm-partition`/`osrm-customize` are RAM-hungry) — the region
extract itself is under 100MB.

Then start the service and point the admin panel at it:

```
docker compose -f docker-compose.prod.yml up -d osrm
```

Admin panel → Xarita sozlamalari → OSRM zaxira server manzili:
`https://backend.waygo.uz/osrm` (routed through the gateway — see
`gateway.conf`'s `/osrm/` location).

## Keeping it current

OSRM serves whatever was in the extract at processing time — it does not
update itself as real roads change. Re-run `./setup.sh` periodically (e.g.
monthly, via cron) and restart the `osrm` service afterward:

```
docker compose -f docker-compose.prod.yml restart osrm
```

## Rolling back

Blank the "OSRM zaxira server manzili" field in the admin panel (or set it
back to `https://router.project-osrm.org`) to revert to the public demo
server without touching this service.
