#!/bin/bash
set -e

# ─── Dev branch deploy script ───────────────────────────────────────────────
# Deploys the 'dev' branch to dev.waygo.uz (port 8082)
# Does NOT affect production (backend.waygo.uz)
# ─────────────────────────────────────────────────────────────────────────────

PROJECT_DIR="/var/www/waygo-backend"
cd "$PROJECT_DIR"

echo "🔄 Pulling latest 'dev' branch..."
git fetch origin dev
git checkout dev
git reset --hard origin/dev

echo "📦 Building dev container..."
docker compose -f docker-compose.dev.yml build app_dev

echo "🚀 Starting dev container..."
docker compose -f docker-compose.dev.yml up -d app_dev

echo "⏳ Waiting for dev app to become healthy..."
MAX_ATTEMPTS=40
ATTEMPT=1
HEALTHY=0

set +e
while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
    echo "Checking dev health (Attempt $ATTEMPT/$MAX_ATTEMPTS)..."
    CONTAINER_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' waygo_backend_app_dev 2>/dev/null)

    if [ -n "$CONTAINER_IP" ]; then
        curl -s --fail "http://$CONTAINER_IP:8080/api/v1/config/app-version" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            echo "✅ Dev app is healthy at IP $CONTAINER_IP!"
            HEALTHY=1
            break
        fi
    fi

    # Also try localhost:8082 directly
    curl -s --fail "http://127.0.0.1:8082/api/v1/config/app-version" >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "✅ Dev app is healthy on port 8082!"
        HEALTHY=1
        break
    fi

    echo "Waiting 3 seconds..."
    sleep 3
    ATTEMPT=$((ATTEMPT+1))
done
set -e

if [ $HEALTHY -ne 1 ]; then
    echo "❌ Dev app failed health check! Logs:"
    docker compose -f docker-compose.dev.yml logs app_dev --tail=50
    exit 1
fi

# Create uploads dir if not exists
mkdir -p /var/www/waygo-uploads-dev
chown -R www-data:www-data /var/www/waygo-uploads-dev

echo ""
echo "✅ Dev deployment completed successfully!"
echo "🌐 Available at: https://dev.waygo.uz"
echo ""
