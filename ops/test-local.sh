#!/usr/bin/env bash
# Полный прогон тестов ядра на машине разработчика.
#
# JDK на хосте нет и не заводится: тесты идут в контейнере gradle:8.10-jdk21,
# репозиторий монтируется, кэш gradle живёт в томе (иначе каждый прогон качает
# зависимости заново). База тестов — контейнер orbita-testdb.
#
#   ops/test-local.sh                     весь набор с --rerun-tasks
#   ops/test-local.sh --tests '*PhaseFlowTest*'   один класс
set -euo pipefail
cd "$(dirname "$0")/.."

DB="${ORBITA_TEST_DB_CONTAINER:-orbita-testdb}"
if ! docker ps --format '{{.Names}}' | grep -qx "$DB"; then
  echo "!!! контейнер базы тестов '$DB' не запущен" >&2
  echo "    поднимите его и повторите: docker start $DB" >&2
  exit 1
fi

args=("$@")
[ ${#args[@]} -eq 0 ] && args=(--rerun-tasks)

docker run --rm --link "$DB":testdb \
  -e ORBITA_TEST_DB_URL='jdbc:postgresql://testdb:5432/orbita_test' \
  -e ORBITA_TEST_DB_USER=orbita -e ORBITA_TEST_DB_PASSWORD=orbita \
  -e ORBITA_FILES_DIR=/tmp/orbita-files \
  -v "$PWD":/workspace -v orbita-gradle-cache:/home/gradle/.gradle \
  -w /workspace gradle:8.10-jdk21 gradle --no-daemon test "${args[@]}"
