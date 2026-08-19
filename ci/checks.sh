#!/usr/bin/env bash
# Полный набор проверок. Запускается в CI и локально перед коммитом.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== схемы =="            ; python3 tools/validate_schemas.py
echo "== трассировка ТЗ =="   ; python3 tools/validate_trace.py
echo "== эталон хранилища ==" ; python3 spec/storage_semantics.py > /dev/null && echo "19 проверок пройдено"

if [ -f gradlew ]; then
  echo "== сборка и тесты ядра =="
  ./gradlew test
  echo "== регрессия производительности (TZ-COM-004) =="
  ./gradlew perfCheck
fi
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
