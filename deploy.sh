#!/bin/bash
set -e

# Load project path (defaults to current dir)
PROJECT_DIR="/var/www/waygo-backend"
cd "$PROJECT_DIR"

# 1. Determine which container is currently serving traffic in upstream.conf
if [ ! -f upstream.conf ]; then
    echo "upstream.conf not found. Creating default pointing to app_blue..."
    echo "upstream backend { server app_blue:8080; }" > upstream.conf
fi

if grep -q "app_blue" upstream.conf; then
    ACTIVE="blue"
    INACTIVE="green"
else
    ACTIVE="green"
    INACTIVE="blue"
fi

echo "Active deployment: app_$ACTIVE"
echo "Targeting deployment: app_$INACTIVE"

# Check if the legacy container 'waygo_backend_app' is running and binds to host port 8080
if docker ps --format '{{.Names}}' | grep -q "^waygo_backend_app$"; then
    echo "Legacy container 'waygo_backend_app' detected. Preparing migration..."
    LEGACY_MIGRATION=1
else
    LEGACY_MIGRATION=0
fi

# 2. Ensure infrastructure services (db, redis, db-backup) are running
echo "Ensuring db, redis, and db-backup are running..."
docker compose -f docker-compose.prod.yml up -d db redis db-backup

if [ "$LEGACY_MIGRATION" -eq 0 ]; then
    echo "Ensuring gateway is running..."
    docker compose -f docker-compose.prod.yml up -d gateway
fi

# 3. Build and start the INACTIVE container
echo "Building and starting app_$INACTIVE..."
docker compose -f docker-compose.prod.yml up -d --build "app_$INACTIVE"

# 4. Wait for the new container to become healthy
echo "Waiting for app_$INACTIVE to become healthy..."
MAX_ATTEMPTS=40
ATTEMPT=1
HEALTHY=0

while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
    echo "Checking health of app_$INACTIVE (Attempt $ATTEMPT/$MAX_ATTEMPTS)..."
    # Execute wget and capture exit code, print output for debugging
    if docker compose -f docker-compose.prod.yml exec -T redis wget -S -O- "http://app_$INACTIVE:8080/api/v1/config/app-version" 2>&1; then
        echo "app_$INACTIVE is healthy!"
        HEALTHY=1
        break
    fi
    echo "Waiting 3 seconds..."
    sleep 3
    ATTEMPT=$((ATTEMPT+1))
done

if [ $HEALTHY -ne 1 ]; then
    echo "Error: app_$INACTIVE failed health check! Printing container logs..."
    docker compose -f docker-compose.prod.yml logs "app_$INACTIVE"
    # Stop the failed container to save resources
    docker compose -f docker-compose.prod.yml stop "app_$INACTIVE"
    exit 1
fi

# 5. Swap Nginx upstream to the new version
echo "Updating Nginx upstream config..."
echo "upstream backend { server app_$INACTIVE:8080; }" > upstream.conf

if [ "$LEGACY_MIGRATION" -eq 1 ]; then
    echo "Migrating: Stopping legacy 'waygo_backend_app' to release port 8080..."
    docker stop waygo_backend_app || true
    docker rm waygo_backend_app || true
    
    echo "Starting gateway..."
    docker compose -f docker-compose.prod.yml up -d gateway
    echo "Migration completed."
else
    # 6. Reload Nginx config inside the gateway container
    echo "Reloading Nginx in gateway container..."
    docker compose -f docker-compose.prod.yml exec -T gateway nginx -s reload
    
    # 7. Stop the old container
    echo "Stopping old app_$ACTIVE container..."
    docker compose -f docker-compose.prod.yml stop "app_$ACTIVE"
fi

echo "✅ Zero Downtime Deployment to app_$INACTIVE completed successfully!"
