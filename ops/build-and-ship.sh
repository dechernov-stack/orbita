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

# Гигиена сборщика — общая с локальным выкатом: сеть с MTU 1380 и
# пересоздание builder'а, когда его том состояния перерос порог.
# shellcheck source=ops/builder-hygiene.sh
. "$ROOT/ops/builder-hygiene.sh"
builder_hygiene
# Двоичные файлы Typst — в контекст сборки (ops/typst-fetch.sh), не из ghcr.io.
"$ROOT/ops/typst-fetch.sh"

echo "==> Сборка образов (linux/amd64, builder $BUILDER)"
# Сборка — через build_retry (ops/builder-hygiene.sh), как у локального выката:
# три попытки на обрыв сети, лог каждой сборки в /tmp/orbita-build-<имя>.log
# (оркестратор режет вывод задачи, и причина отказа терялась — 24.09), а с
# BUILDER=default ключ --builder не передаётся вовсе: «default» — это ещё и
# имя контекста Docker, и buildx на нём отказывал.
build_retry api      --platform linux/amd64 -t orbita-api:latest      -f "$ROOT/ops/api.Dockerfile" --target api  "$ROOT"
build_retry seed     --platform linux/amd64 -t orbita-seed:latest     -f "$ROOT/ops/api.Dockerfile" --target seed "$ROOT"
build_retry exchange --platform linux/amd64 -t orbita-exchange:latest -f "$ROOT/ops/exchange.Dockerfile" "$ROOT"
build_retry web      --platform linux/amd64 -t orbita-web:latest      -f "$ROOT/ops/web.Dockerfile" "$ROOT"
# StrictDoc-канал (ADR-049/064): профиль strictdoc на сервере включается
# COMPOSE_PROFILES=strictdoc в /opt/orbita/.env — образ обязан быть на месте.
build_retry strictdoc --platform linux/amd64 -t orbita-strictdoc:latest -f "$ROOT/ops/strictdoc.Dockerfile" "$ROOT"

echo "==> Перенос кода на $SERVER:$DEST"
$SSH "mkdir -p $DEST"
rsync -az --delete \
  --exclude .git --exclude web/node_modules --exclude web/dist \
  --exclude .gradle --exclude '**/build' --exclude .env \
  --exclude docker-compose.override.yml \
  -e "ssh -i $SSH_KEY -o BatchMode=yes" "$ROOT/" "root@$SERVER:$DEST/"

echo "==> Перенос образов (docker save | load)"
docker save orbita-api:latest orbita-seed:latest orbita-exchange:latest orbita-web:latest orbita-strictdoc:latest \
  | gzip | $SSH 'gunzip | docker load'

echo "==> build-and-ship завершён"
