#!/usr/bin/env python3
"""Сторож круга 5: Гант рисует БИБЛИОТЕКА, а не мы.

Решение владельца: frappe-gantt (MIT) — полосы, стрелки зависимостей,
перетаскивание дат и шкала идут из коробки. Соблазн два: начать дорисовывать
своё поверх (свои SVG-полосы и стрелки) и начать править чужое ядро. Оба
кончаются одинаково: библиотека обновится, а самострой останется.

Проверяется:
  1. в обёртке Ганта нет собственной графики — ни SVG-полос, ни стрелок, ни
     своей шкалы: чего не хватает из коробки, делается оверлеем или не
     делается вовсе;
  2. библиотека не форкнута: её исходников в репозитории нет, зависимость
     стоит версией в package.json;
  3. процентов выполнения не существует: сервер шлёт progress = 0, а полоса
     прогресса скрыта стилем (ловушка 4 задания).
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WRAP = ROOT / "web/src/screens/PhaseGantt.tsx"
SERVER = ROOT / "core/com/src/main/kotlin/orbita/com/api/PhaseGantt.kt"
CSS = ROOT / "web/src/ui/tokens.css"
PKG = ROOT / "web/package.json"

OWN_DRAWING = [
    (r"<svg\b", "своё полотно поверх библиотечного"),
    (r"<path\b", "свои стрелки: зависимости рисует библиотека"),
    (r"<rect\b", "свои полосы: полосы рисует библиотека"),
    (r"\bcolumn_width\s*\*", "свой пересчёт шкалы в пиксели"),
]


def main() -> int:
    problems: list[str] = []

    wrap = WRAP.read_text(encoding="utf-8")
    for pattern, why in OWN_DRAWING:
        for m in re.finditer(pattern, wrap):
            line = wrap.count("\n", 0, m.start()) + 1
            problems.append(f"{WRAP.relative_to(ROOT)}:{line}: {why} — «{m.group(0)}»")

    # 2. форк библиотеки: копии исходников в репозитории быть не должно
    for path in sorted((ROOT / "web/src").rglob("*")):
        if path.is_file() and "frappe" in path.name and path.suffix != ".ts":
            problems.append(f"{path.relative_to(ROOT)}: копия библиотеки в репозитории — форк запрещён")
    deps = json.loads(PKG.read_text(encoding="utf-8")).get("dependencies", {})
    if "frappe-gantt" not in deps:
        problems.append("web/package.json: зависимость frappe-gantt пропала — Гант рисовать нечем")
    elif not re.match(r"^[\^~]?\d+\.\d+\.\d+$", str(deps["frappe-gantt"])):
        problems.append(
            f"web/package.json: frappe-gantt подключён не версией, а «{deps['frappe-gantt']}» — "
            "ссылка на форк или архив запрещена"
        )

    # 3. процентов выполнения не существует
    server = SERVER.read_text(encoding="utf-8")
    if 'put("progress", 0)' not in server:
        problems.append(
            f"{SERVER.relative_to(ROOT)}: progress обязан быть нулём — процентов выполнения у задач нет"
        )
    if ".bar-progress" not in CSS.read_text(encoding="utf-8"):
        problems.append("web/src/ui/tokens.css: полоса прогресса библиотеки не скрыта — проценты полезут на экран")

    if problems:
        print("Гант: рисует библиотека, самострой и форк запрещены", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    print(f"Гант: библиотека на месте, самостроя нет (правил: {len(OWN_DRAWING) + 4})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
