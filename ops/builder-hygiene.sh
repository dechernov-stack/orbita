#!/usr/bin/env bash
# Гигиена сборщика buildx — общая для локального выката и переброски на сервер.
#
# Зачем. Сборка идёт через builder в сети с MTU 1380 (на VPN загрузка
# зависимостей в сети по умолчанию рвётся: «Illegal packet size»,
# «bad_record_mac»). У этого builder'а есть свой том состояния, и он растёт
# ПРИМЕРНО НА ПОЛГИГАБАЙТА ЗА СБОРКУ: за день активной работы — 5–6 ГБ.
# `docker builder prune` его не освобождает; лечится только пересозданием
# builder'а. Проверено дважды за один день живого прогона: диск кончался,
# и первым делом помогало именно это.
#
# Здесь: сеть и builder создаются, если их нет; если том состояния перерос
# порог — builder пересоздаётся (кэш слоёв теряется, первая сборка после
# этого дольше — это дешевле, чем кончившийся диск).
set -euo pipefail

BUILDER="${BUILDER:-orbita-mtu}"
NETWORK="${NETWORK:-orbita-mtu1380}"
# Порог тома состояния, ГБ. Меньше 2 держать смысла нет: кэш слоёв полезен.
BUILDER_STATE_LIMIT_GB="${BUILDER_STATE_LIMIT_GB:-2}"

ensure_network() {
  docker network inspect "$NETWORK" > /dev/null 2>&1 \
    || docker network create --opt com.docker.network.driver.mtu=1380 "$NETWORK" > /dev/null
}

create_builder() {
  docker buildx create --name "$BUILDER" \
    --driver docker-container \
    --driver-opt network="$NETWORK" \
    --buildkitd-flags '--allow-insecure-entitlement=network.host' > /dev/null
}

# Размер тома состояния в гигабайтах (целое, вниз). Том живёт внутри VM
# Docker Desktop, поэтому меряем изнутри контейнера.
builder_state_gb() {
  local vol="buildx_buildkit_${BUILDER}0_state"
  docker volume inspect "$vol" > /dev/null 2>&1 || { echo 0; return; }
  docker run --rm -v "$vol":/v alpine du -sm /v 2>/dev/null \
    | awk '{print int($1/1024)}' || echo 0
}

builder_hygiene() {
  ensure_network
  if ! docker buildx inspect "$BUILDER" > /dev/null 2>&1; then
    echo "==> Сборщик $BUILDER отсутствует — создаю"
    create_builder
    return
  fi
  local gb
  gb="$(builder_state_gb)"
  if [ "${gb:-0}" -ge "$BUILDER_STATE_LIMIT_GB" ]; then
    echo "==> Том сборщика разросся до ${gb} ГБ (порог ${BUILDER_STATE_LIMIT_GB}) — пересоздаю $BUILDER"
    docker buildx rm "$BUILDER" > /dev/null 2>&1 || true
    ensure_network
    create_builder
  else
    echo "==> Том сборщика: ${gb} ГБ — в пределах порога ${BUILDER_STATE_LIMIT_GB} ГБ"
  fi
}

# Сборка с повторами: обрыв загрузки зависимостей на VPN — не отказ сборки,
# а состояние сети. Три попытки закрывают его в подавляющем большинстве
# случаев (наблюдение живого прогона: со второй попытки идёт успешно).
build_retry() {
  local tag="$1"; shift
  local attempt
  for attempt in 1 2 3; do
    if docker buildx build --builder "$BUILDER" \
        --allow network.host --load "$@" > /tmp/orbita-build-"$tag".log 2>&1; then
      echo "==> $tag: собран"
      return 0
    fi
    echo "==> $tag: попытка $attempt не удалась ($(grep -m1 -oE 'Could not download [^ ]+|bad_record_mac|Illegal packet size' /tmp/orbita-build-"$tag".log | head -1))"
    sleep 8
  done
  echo "!!! $tag: сборка не удалась трижды — лог /tmp/orbita-build-$tag.log" >&2
  return 1
}

# Уборка ПОСЛЕ выката: висячие слои (каждая пересборка latest оставляет
# предыдущий слой ничьим) и анонимные тома снесённых одноразовых стендов.
# Именованные кэши (gradle) и базы не трогаются — они дороже места.
post_deploy_prune() {
  echo "==> Уборка: висячие образы"
  docker image prune -f | tail -1
  echo "==> Уборка: анонимные тома-сироты"
  local removed=0
  local vol anon
  while read -r vol; do
    [ -z "$vol" ] && continue
    anon="$(docker volume inspect "$vol" 2>/dev/null \
      | grep -c 'com.docker.volume.anonymous' || true)"
    if [ "$anon" != "0" ]; then
      docker volume rm "$vol" > /dev/null 2>&1 && removed=$((removed + 1))
    fi
  done < <(docker volume ls -qf dangling=true)
  echo "    снято анонимных томов: $removed"
  df -h / | tail -1 | awk '{print "==> Свободно на диске: " $4}'
}
