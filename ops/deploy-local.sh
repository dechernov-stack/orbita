#!/usr/bin/env bash
# Выкат на ЛОКАЛЬНЫЙ стенд (docker compose на машине инженера).
#
#   ops/deploy-local.sh              # api + web
#   ops/deploy-local.sh api          # только сервер
#   ops/deploy-local.sh web          # только клиент
#   ops/deploy-local.sh api web exchange
#
# Что делает по порядку:
#   1. гигиена сборщика: сеть с MTU 1380, пересоздание при разросшемся томе;
#   2. сборка нужных образов с тремя попытками (обрывы на VPN — норма);
#   3. пересоздание контейнеров (--force-recreate: тот же тег latest иначе
#      не подхватывается, и выкат «проходит», не меняя ничего);
#   4. проверка здоровья служб;
#   5. уборка висячих слоёв и анонимных томов.
#
# Скрипт НЕ трогает базу стенда, кэши сборки и чужие образы.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Провал обязан быть виден ПОСЛЕДНЕЙ строкой: вывод скрипта часто смотрят
# хвостом, а `| tail` съедает код возврата — упавшая сборка выглядела
# успешным выкатом (наблюдение живого прохода).
trap 'code=$?; [ $code -ne 0 ] && echo "!!! ВЫКАТ НЕ ВЫПОЛНЕН (код $code) — стенд остался на прежней версии"; exit $code' EXIT
# shellcheck source=ops/builder-hygiene.sh
. "$ROOT/ops/builder-hygiene.sh"

SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=(api web)

builder_hygiene

for svc in "${SERVICES[@]}"; do
  case "$svc" in
    api)
      build_retry api -t orbita-api:latest -f "$ROOT/ops/api.Dockerfile" --target api "$ROOT" ;;
    web)
      build_retry web -t orbita-web:latest -f "$ROOT/ops/web.Dockerfile" "$ROOT" ;;
    exchange)
      build_retry exchange -t orbita-exchange:latest -f "$ROOT/ops/exchange.Dockerfile" "$ROOT" ;;
    *)
      echo "!!! неизвестная служба: $svc (ожидаю api, web, exchange)" >&2; exit 2 ;;
  esac
done

echo "==> Пересоздание контейнеров: ${SERVICES[*]}"
( cd "$ROOT" && docker compose up -d --no-build --force-recreate "${SERVICES[@]}" )

echo "==> Здоровье служб"
sleep 20
( cd "$ROOT" && docker compose ps --format '    {{.Service}}	{{.Status}}' )

# Открытая ручка: живость API без учётки — та же, что в HEALTHCHECK образа
if printf '%s\n' "${SERVICES[@]}" | grep -qx api; then
  echo "==> Проверка API"
  curl -fsS -m 10 http://localhost:8080/api/auth/whoami | head -c 120; echo
fi

post_deploy_prune
echo "==> Выкат завершён"
