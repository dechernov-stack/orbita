#!/usr/bin/env python3
"""Сторож схемы потока (круг 4, ловушка 1): схема — вычисленная проекция.

Редактор схемы, ручные координаты и «сохранить раскладку» запрещены не
пожеланием, а механикой: как только раскладка становится хранимой, она
начинает расходиться с зависимостями задач — и картинка врёт молча, потому
что нарисована она правильно, просто про вчера.

Проверяется:
  1. клиент схемы не двигает и не измеряет узлы: перетаскивания, обмеров
     DOM и своей памяти координат в PhaseFlow.tsx нет;
  2. клиент не считает геометрию: арифметики над x/y/w/h нет — координаты
     приходят готовыми (продолжение обхода расчётов в клиенте);
  3. сервер схемы ничего не пишет: проекция читает состояние, а не заводит
     собственных объектов;
  4. маршрута сохранения раскладки не существует: схема отдаётся GET и
     принимать ей нечего.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT = ROOT / "web/src/screens/PhaseFlow.tsx"
SERVER = ROOT / "core/com/src/main/kotlin/orbita/com/api/PhaseFlow.kt"
HTTP_API = ROOT / "core/com/src/main/kotlin/orbita/com/api/HttpApi.kt"

# 1. рисование руками: перетаскивание, обмеры, своя память раскладки
DRAWING = [
    (r"\bdraggable\b", "перетаскивание узла — схема стала бы рисунком"),
    (r"\bonDrag[A-Z]?\w*\s*=", "перетаскивание узла — схема стала бы рисунком"),
    (r"\bonMouse(Down|Move)\s*=", "ручное позиционирование мышью"),
    (r"getBoundingClientRect", "обмер DOM: геометрию считает сервер, не экран"),
    (r"\b(local|session)Storage\b", "запомненная раскладка — вторая истина о потоке"),
    (r"useState[^\n]*\b(coords|positions|layout|раскладк)", "своя память координат"),
]

# 2. арифметика над координатами узла: они приходят готовыми
GEOMETRY = re.compile(r"\bn\.(x|y|w|h|label_x|label_y)\s*[-+*/]|[-+*/]\s*n\.(x|y|w|h)\b")

# 3. запись из проекции: схема читает состояние и ничего не заводит
WRITES = [r"\.create\(", r"\.update\(", r"boundary\.editing", r"INSERT\s", r"UPDATE\s"]


def main() -> int:
    problems: list[str] = []

    client = CLIENT.read_text(encoding="utf-8")
    for pattern, why in DRAWING:
        for m in re.finditer(pattern, client):
            line = client.count("\n", 0, m.start()) + 1
            problems.append(f"{CLIENT.relative_to(ROOT)}:{line}: {why} — «{m.group(0)}»")
    for m in GEOMETRY.finditer(client):
        line = client.count("\n", 0, m.start()) + 1
        problems.append(
            f"{CLIENT.relative_to(ROOT)}:{line}: клиент считает геометрию — «{m.group(0).strip()}»"
        )

    server = SERVER.read_text(encoding="utf-8")
    for pattern in WRITES:
        for m in re.finditer(pattern, server):
            line = server.count("\n", 0, m.start()) + 1
            problems.append(
                f"{SERVER.relative_to(ROOT)}:{line}: проекция пишет в модель — «{m.group(0).strip()}»"
            )

    api = HTTP_API.read_text(encoding="utf-8")
    for m in re.finditer(r'method == "(POST|PUT|PATCH|DELETE)"[^\n]*phase-flow', api):
        line = api.count("\n", 0, m.start()) + 1
        problems.append(
            f"ci · HttpApi.kt:{line}: маршрут сохранения раскладки — схема считается, а не хранится"
        )

    if problems:
        print("схема потока: раскладка обязана вычисляться, а не рисоваться", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    print(f"схема потока: вычисляемость держится, правил: {len(DRAWING) + len(WRITES) + 2}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
