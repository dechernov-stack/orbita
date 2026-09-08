#!/usr/bin/env bash
# Проход владельца по стенду: на время прохода хост стенда не делит
# процессор и базу с тестами ядра и выкатами.
#
#   ops/walkthrough.sh on      # объявить проход: test-local и deploy-local отказывают
#   ops/walkthrough.sh off     # проход окончен
#   ops/walkthrough.sh status
#
# Почему: 08.09 тесты ядра шли 1 ч 42 м, деля процессор с api, а каждое
# пересоздание api по секунде совпало с падением backend Postgres
# (docs/tz/v2/отчёты/СТЕНД-ПАДЕНИЯ-PG-08-09.md). Метка — файл; он не в git.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MARK="$ROOT/ops/.проход-владельца"

case "${1:-status}" in
  on)  date '+%Y-%m-%d %H:%M' > "$MARK"; echo "проход владельца объявлен ($(cat "$MARK")): тесты ядра и выкаты на стенд отказывают до 'off'" ;;
  off) rm -f "$MARK"; echo "проход окончен: тесты и выкаты разрешены" ;;
  status) if [ -f "$MARK" ]; then echo "идёт проход владельца с $(cat "$MARK")"; else echo "прохода нет"; fi ;;
  *) echo "ожидаю on | off | status" >&2; exit 2 ;;
esac
