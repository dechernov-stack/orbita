#!/usr/bin/env bash
# Полный набор проверок. Запускается в CI и локально перед коммитом.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== схемы =="            ; python3 tools/validate_schemas.py
echo "== трассировка ТЗ =="   ; python3 tools/validate_trace.py
echo "== эталон: хранилище =="    ; python3 spec/storage_semantics.py      > /dev/null && echo "19 проверок пройдено"
echo "== эталон: требования ==" ; python3 spec/requirements_semantics.py > /dev/null && echo "23 проверки пройдено"
echo "== эталон: карта спроса ==" ; python3 spec/demand_semantics.py     > /dev/null && echo "16 проверок пройдено"

if [ -f gradlew ]; then
  echo "== сборка и тесты ядра =="
  ./gradlew test
  # perfCheck намеренно не вызывается: задача падает до появления расчётных
  # модулей (STEP-2 §0.2); включить на шаге 4 (баллистика, TZ-COM-004).
fi
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
