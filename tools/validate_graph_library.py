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
     elkjs не подключён.
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

OWN_DRAWING = [
    (re.compile(r"<svg\b"), "своё полотно поверх библиотечного"),
    (re.compile(r"<path\b"), "свои рёбра: рёбра рисует библиотека"),
    (re.compile(r"<rect\b"), "свои узлы: узлы рисует библиотека"),
    (re.compile(r"localStorage|sessionStorage"), "память раскладки в браузере — координаты не хранятся"),
    (re.compile(r"сохранить раскладку|saveLayout|onNodeDragStop"), "сохранение раскладки — граф вычисляется, не рисуется"),
]


def main() -> int:
    problems: list[str] = []
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
    if problems:
        print("ГРАФ ТРАССИРОВКИ НАРУШАЕТ ПРАВИЛА (ADR-046):")
        for p in problems:
            print("  " + p)
        return 1
    print("граф трассировки: рисует библиотека, координат в модели и на сервере нет, форка нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
