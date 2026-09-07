#!/usr/bin/env python3
"""Сторож графа трассировки (ADR-046): граф рисует БИБЛИОТЕКА, координаты
не хранятся.

Решение владельца: @xyflow/react (MIT) + @dagrejs/dagre (MIT); elkjs — нет
(EPL). Соблазны те же, что у Ганта: дорисовать своё поверх библиотеки,
запомнить раскладку «как было», а потом обнаружить, что картинка врёт про
вчерашние связи. Проверяется:
  1. в экране графа нет собственной графики (svg/path/rect) и своей памяти
     координат (localStorage, «сохранить раскладку»);
  2. сервер графа не отдаёт координат: ни x, ни y в ответе — раскладку
     считает клиентская библиотека при показе;
  3. маршрута записи графа нет: он отдаётся GET, принимать ему нечего;
  4. библиотеки стоят версией в package.json, форка в репозитории нет,
     elkjs не подключён;
  5. в экранах v2 (web/src/v2) нет ручной отрисовки связей — граф и impact
     волны 4 идут той же библиотекой, что и трассировка v1;
  6. e2e-проверка графа идёт в ВИДИМОЙ вкладке. Библиотека обмеряет узлы
     наблюдателем размеров, а браузер не рисует фоновую вкладку — узлы
     остаются скрытыми, рёбра не появляются, и тест «граф пуст» выглядит
     дефектом кода. Полчаса живой проверки шипа B ушли на этот след;
     правило записано сюда, чтобы след не повторился.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCREEN = ROOT / "web/src/screens/TraceGraph.tsx"
SERVER = ROOT / "core/com/src/main/kotlin/orbita/com/api/TraceGraph.kt"
HTTP_API = ROOT / "core/com/src/main/kotlin/orbita/com/api/HttpApi.kt"
PKG = ROOT / "web/package.json"
V2 = ROOT / "web/src/v2"

OWN_DRAWING = [
    (re.compile(r"<svg\b"), "своё полотно поверх библиотечного"),
    (re.compile(r"<path\b"), "свои рёбра: рёбра рисует библиотека"),
    (re.compile(r"<rect\b"), "свои узлы: узлы рисует библиотека"),
    (re.compile(r"localStorage|sessionStorage"), "память раскладки в браузере — координаты не хранятся"),
    (re.compile(r"сохранить раскладку|saveLayout|onNodeDragStop"), "сохранение раскладки — граф вычисляется, не рисуется"),
]


# Правило e2e: снимок графа берут только в видимой вкладке. Проверяется
# по тексту e2e-сценариев — там, где они появятся.
E2E = [ROOT / "tools/shoot_v2_design.mjs"]
ВИДИМОСТЬ = re.compile(r"bringToFront|tabs_select|setViewport|foreground|видим")


def e2e_видимость(problems: list[str]) -> None:
    for файл in E2E:
        if not файл.exists():
            continue
        текст = файл.read_text(encoding="utf-8")
        if "react-flow" not in текст and "impact" not in текст.lower():
            continue
        if not ВИДИМОСТЬ.search(текст):
            problems.append(
                f"{файл.relative_to(ROOT)}: снимок графа без вывода вкладки на передний план — "
                "в фоновой вкладке узлы скрыты, а рёбра не рисуются",
            )


def main() -> int:
    problems: list[str] = []
    e2e_видимость(problems)
    screen = SCREEN.read_text(encoding="utf-8")
    for pattern, why in OWN_DRAWING:
        for m in pattern.finditer(screen):
            line = screen.count("\n", 0, m.start()) + 1
            problems.append(f"{SCREEN.relative_to(ROOT)}:{line}: {why} — «{m.group(0)}»")
    if "@xyflow/react" not in screen or "@dagrejs/dagre" not in screen:
        problems.append(f"{SCREEN.relative_to(ROOT)}: граф обязан рисоваться @xyflow/react с раскладкой @dagrejs/dagre")
    server = SERVER.read_text(encoding="utf-8")
    for m in re.finditer(r"\.put\(\"(x|y|width|height|position)\"", server):
        line = server.count("\n", 0, m.start()) + 1
        problems.append(f"{SERVER.relative_to(ROOT)}:{line}: сервер отдаёт координаты — раскладка принадлежит показу")
    api = HTTP_API.read_text(encoding="utf-8")
    if re.search(r"method == \"(POST|PUT|PATCH)\" && path == \"/views/trace-graph", api):
        problems.append("HttpApi.kt: маршрут записи графа — граф только читается")
    deps = json.loads(PKG.read_text(encoding="utf-8")).get("dependencies", {})
    for lib in ("@xyflow/react", "@dagrejs/dagre"):
        if lib not in deps:
            problems.append(f"web/package.json: зависимость {lib} пропала — граф рисовать нечем")
    if "elkjs" in deps:
        problems.append("web/package.json: elkjs (EPL) подключён — запрет GPL/EPL")
    for path in sorted((ROOT / "web/src").rglob("*")):
        if path.is_file() and ("xyflow" in path.name or "dagre" in path.name):
            problems.append(f"{path.relative_to(ROOT)}: копия библиотеки в репозитории — форк запрещён")
    # 5. Экраны v2: тот же запрет своей графики. Граф влияния (волна 4)
    # рисуется библиотекой — самодельная раскладка не переживёт первого
    # «переставьте узлы».
    if V2.is_dir():
        for файл in sorted(V2.rglob("*.tsx")):
            текст = файл.read_text(encoding="utf-8")
            графовый = "impact" in файл.name.lower() or "ImpactView" in текст or "api.impact" in текст
            for совпадение in re.finditer(r"<(line|path|polyline|marker)\b", текст):
                problems.append(
                    f"{файл.relative_to(ROOT)}: ручная отрисовка <{совпадение.group(1)}> — "
                    "рёбра рисует библиотека"
                )
            if графовый and "@xyflow/react" not in текст:
                problems.append(
                    f"{файл.relative_to(ROOT)}: экран графа не берёт @xyflow/react"
                )

    if problems:
        print("ГРАФ ТРАССИРОВКИ НАРУШАЕТ ПРАВИЛА (ADR-046):")
        for p in problems:
            print("  " + p)
        return 1
    print("граф трассировки: рисует библиотека, координат в модели и на сервере нет, форка нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
