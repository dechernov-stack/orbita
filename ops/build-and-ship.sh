#!/usr/bin/env bash
# Сборка образов стенда ЛОКАЛЬНО (linux/amd64) + перенос кода и образов на
# сервер. НЕ запускает контейнеры — это шаг deploy_project серверного воркера.
# Образец — conductor-orchestrator/scripts/build-and-ship.sh: на сервере 4 ГБ,
# сборка Gradle/Vite там невозможна (OOM), поэтому local_build на Mac-воркере.
#
# Сборка идёт через buildx-builder в сети с MTU 1380 (orbita-mtu): на VPN
# загрузка зависимостей в сети по умолчанию рвётся (Illegal packet size).
set -euo pipefail

SERVER="${SERVER:-216.57.108.107}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/kabanchik_assist}"
DEST="${DEST:-/opt/orbita}"
SSH="ssh -i $SSH_KEY -o BatchMode=yes root@$SERVER"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILDER="${BUILDER:-orbita-mtu}"

docker network inspect orbita-mtu1380 > /dev/null 2>&1 \
  || docker network create --opt com.docker.network.driver.mtu=1380 orbita-mtu1380
docker buildx inspect "$BUILDER" > /dev/null 2>&1 \
  || docker buildx create --name "$BUILDER" --driver docker-container --driver-opt network=orbita-mtu1380

echo "==> Сборка образов (linux/amd64, builder $BUILDER)"
docker buildx build --builder "$BUILDER" --platform linux/amd64 --load \
  -t orbita-api:latest      -f "$ROOT/ops/api.Dockerfile" --target api  "$ROOT"
docker buildx build --builder "$BUILDER" --platform linux/amd64 --load \
  -t orbita-seed:latest     -f "$ROOT/ops/api.Dockerfile" --target seed "$ROOT"
docker buildx build --builder "$BUILDER" --platform linux/amd64 --load \
  -t orbita-exchange:latest -f "$ROOT/ops/exchange.Dockerfile" "$ROOT"
docker buildx build --builder "$BUILDER" --platform linux/amd64 --load \
  -t orbita-web:latest      -f "$ROOT/ops/web.Dockerfile" "$ROOT"

echo "==> Перенос кода на $SERVER:$DEST"
$SSH "mkdir -p $DEST"
rsync -az --delete \
  --exclude .git --exclude web/node_modules --exclude web/dist \
  --exclude .gradle --exclude '**/build' --exclude .env \
  --exclude docker-compose.override.yml \
  -e "ssh -i $SSH_KEY -o BatchMode=yes" "$ROOT/" "root@$SERVER:$DEST/"

echo "==> Перенос образов (docker save | load)"
docker save orbita-api:latest orbita-seed:latest orbita-exchange:latest orbita-web:latest \
  | gzip | $SSH 'gunzip | docker load'

echo "==> build-and-ship завершён"
