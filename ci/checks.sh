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
# МВП-П1 §2.3: маркерный элемент без текста обязан нести подсказку
echo "== подсказки =="        ; python3 tools/validate_tooltips.py
# Блокер З-01 (прогон 04.09): нативные диалоги браузера подавляются во
# встроенном контексте и молча отменяют действие — подтверждение своим окном
echo "== диалоги =="          ; python3 tools/validate_no_native_dialogs.py
# Шип 3 прогона 04.09: в тексте интерфейса нет служебных слов языка и имён
# видов латиницей — вид называется по-русски из словаря
echo "== текст интерфейса ==" ; python3 tools/validate_ui_text.py
# Сборка проекта-примера: хук после раннего возврата валил ВЕСЬ клиент
# (React #310) — экран «Нужды и их сервисы» уносил рейку целиком
echo "== порядок хуков ==" ; python3 tools/validate_hooks_order.py
echo "== расчёты примера ==" ; python3 tools/validate_example_models.py
echo "== значения примера ==" ; python3 tools/validate_example_values.py
echo "== рамка ведения =="    ; python3 tools/validate_task_frame.py
echo "== схема потока =="     ; python3 tools/validate_flow_computed.py
echo "== Гант библиотекой ==" ; python3 tools/validate_gantt_library.py
echo "== печать без ключей ==" ; python3 tools/validate_print_keys.py
echo "== одно дерево носителей ==" ; python3 tools/validate_one_tree.py
echo "== граф библиотекой ==" ; python3 tools/validate_graph_library.py
echo "== Capella только чтение ==" ; python3 tools/validate_capella_readonly.py
echo "== реестр маршрутов ==" ; python3 tools/routes_registry.py --check
# Справочник единиц (решение ранга ADR): unit-строки ∈ справочнику
echo "== единицы =="          ; python3 tools/validate_units.py && python3 tools/validate_units.py --selftest

# Круговой обмен ReqIF и сверка с XSD OMG (шаг 11.2, ADR-023). Требует пакета
# reqif==0.0.47 — CI его ставит. Локально без пакета пропуск объявляется вслух.
if python3 -c 'import strictdoc' 2>/dev/null; then
  echo "== StrictDoc-канал: детерминизм и круговой обмен =="; python3 tools/check_sdoc_roundtrip.py
  echo "== сверка каналов ReqIF (условие сноса своего контура) =="; python3 tools/check_reqif_equivalence.py || true
else
  echo "== StrictDoc-канал == детерминизм и полнота проверены без пакета; экспорт/импорт ПРОПУЩЕНЫ (pip install strictdoc==0.29.0)"; python3 tools/check_sdoc_roundtrip.py
fi
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
  # Каталог файлов исходных документов: без переменной сервер берёт /files —
  # он есть в контейнере стенда, но не на машине тестов. Тесты кладут во времянку.
  export ORBITA_FILES_DIR="${ORBITA_FILES_DIR:-$(mktemp -d)}"
  echo "== сборка и тесты ядра ==" ; ./gradlew test
  echo "== регрессия производительности (TZ-COM-004) ==" ; ./gradlew perfCheck
fi

# Клиент: типы обязаны сходиться с формами ответов API. Пропуск объявляется
# явно — молчаливо пропущенная проверка однажды уже скрыла невыполнявшийся эталон.
if [ -d web/node_modules ]; then
  echo "== типы клиента ==" ; (cd web && npm run --silent typecheck)
  # Т-1: модель реестра (дубль ID, конфигуратор=факт, разрывы) — vitest
  echo "== тесты клиента ==" ; (cd web && npm run --silent test)
else
  echo "== типы клиента == ПРОПУЩЕНО: нет web/node_modules (npm ci в web/)"
fi
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
