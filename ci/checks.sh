#!/usr/bin/env bash
# Полный набор проверок. Запускается в CI и локально перед коммитом.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== схемы =="            ; python3 tools/validate_schemas.py
echo "== трассировка ТЗ =="   ; python3 tools/validate_trace.py
echo "== эталон: хранилище =="    ; python3 spec/storage_semantics.py      > /dev/null && echo "19 проверок пройдено"
echo "== эталон: требования ==" ; python3 spec/requirements_semantics.py > /dev/null && echo "23 проверки пройдено"
echo "== эталон: условия и бюджеты ==" ; python3 spec/constraint_semantics.py > /dev/null && echo "38 проверок пройдено"
echo "== эталон: полнота верификации ==" ; python3 spec/verification_semantics.py > /dev/null && echo "16 проверок пройдено"
echo "== эталон: карта спроса ==" ; python3 spec/demand_semantics.py     > /dev/null && echo "16 проверок пройдено"
echo "== эталон: баллистика ==" ; python3 spec/ballistics_semantics.py  > /dev/null && echo "39 проверок пройдено"
echo "== эталон: аппарат и канал ==" ; python3 spec/spacecraft_semantics.py > /dev/null && echo "34 проверки пройдено"

if [ -f gradlew ]; then
  echo "== сборка и тесты ядра =="
  ./gradlew test
  echo "== регрессия производительности (TZ-COM-004) =="
  ./gradlew perfCheck
fi
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
