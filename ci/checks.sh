#!/usr/bin/env bash
# Полный набор проверок. Запускается в CI и локально перед коммитом.
# Числа проверок НЕ задаются в скрипте: они берутся из вывода эталонов.
# Захардкоженное число однажды скрыло эталон, который не выполнялся вовсе.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== схемы =="              ; python3 tools/validate_schemas.py
echo "== трассировка ТЗ =="     ; python3 tools/validate_trace.py
echo "== эталоны против схем ==" ; python3 tools/validate_spec_schema.py
echo "== обход кода клиента ==" ; python3 tools/validate_web_no_math.py
# Подключённость (STEP-16 §1.1): посчитанное обязано доходить до экрана.
# Проверка блокирующая с первого коммита шага — иначе код снова начнёт
# накапливаться мимо системы, а тесты останутся зелёными.
echo "== подключённость =="  ; python3 ci/wiring.py
echo "== идентификаторы =="   ; python3 ci/literals.py

# Круговой обмен ReqIF и сверка с XSD OMG (шаг 11.2, ADR-023). Требует пакета
# reqif==0.0.47 — CI его ставит. Локально без пакета пропуск объявляется вслух.
if python3 -c 'import reqif' 2>/dev/null; then
  echo "== ReqIF: круговой обмен и XSD OMG =="; python3 tools/check_reqif_roundtrip.py
else
  echo "== ReqIF: круговой обмен == ПРОПУЩЕНО: нет пакета reqif (pip install reqif==0.0.47)"
fi

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

# Клиент: типы обязаны сходиться с формами ответов API. Пропуск объявляется
# явно — молчаливо пропущенная проверка однажды уже скрыла невыполнявшийся эталон.
if [ -d web/node_modules ]; then
  echo "== типы клиента ==" ; (cd web && npm run --silent typecheck)
else
  echo "== типы клиента == ПРОПУЩЕНО: нет web/node_modules (npm ci в web/)"
fi
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
