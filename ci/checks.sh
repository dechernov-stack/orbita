#!/usr/bin/env bash
# Полный набор проверок. Запускается в CI и локально перед коммитом.
# Числа проверок НЕ задаются в скрипте: они берутся из вывода эталонов.
# Захардкоженное число однажды скрыло эталон, который не выполнялся вовсе.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== схемы =="              ; python3 tools/validate_schemas.py
echo "== трассировка ТЗ =="     ; python3 tools/validate_trace.py
echo "== эталоны против схем ==" ; python3 tools/validate_spec_schema.py

echo "== исполняемые эталоны =="
total=0
for f in spec/*.py; do
  out=$(python3 "$f" | tail -1)
  n=$(echo "$out" | grep -oE '[0-9]+' | head -1)
  printf '   %-32s %s\n' "$(basename "$f" .py)" "$out"
  total=$((total + n))
done
echo "   ВСЕГО ПРОВЕРОК: $total"

if [ -f gradlew ]; then
  echo "== сборка и тесты ядра ==" ; ./gradlew test
  echo "== регрессия производительности (TZ-COM-004) ==" ; ./gradlew perfCheck
fi
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
