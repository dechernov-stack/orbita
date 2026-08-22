#!/usr/bin/env python3
"""Проверка подключённости: посчитанное обязано доходить до экрана.

Пятнадцать шагов принимались по вызову функции из теста, и так вышло, что
маршруты отдают матрицы, а экрана у них нет: тест зелёный независимо от того,
подключён элемент или нет (STEP-16 §0).

Две проверки:

  маршруты  — каждый строковый литерал маршрута в HttpApi.kt имеет потребителя
              в web/src/api/*.ts;
  функции   — каждая публичная функция в core/out/src/main и в
              core/bal/.../VizData.kt имеет вызывающего вне src/test.

Исключения — в ci/unwired.txt, по строке на элемент:

    <элемент> · <причина> · <дата>

Проверка падает в трёх случаях: элемент не подключён и в списке отсутствует;
элемент есть в списке, но исчез из кода (устаревшее исключение); строка списка
без причины или без даты.

Счётчиков здесь нет намеренно: зашитое число однажды уже скрыло эталон, который
не выполнялся вовсе (ci/checks.sh, шестой шаг).

Запуск:

    python3 ci/wiring.py           проверка (код 1 при расхождении)
    python3 ci/wiring.py --init    заполнить ci/unwired.txt текущим состоянием
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HTTP_API = ROOT / "core/com/src/main/kotlin/orbita/com/api/HttpApi.kt"
WEB_API_DIR = ROOT / "web/src/api"
VIEW_SOURCES = [
    ROOT / "core/out/src/main",
    ROOT / "core/bal/src/main/kotlin/orbita/bal/VizData.kt",
    # coverageByTarget назван в задании поимённо (STEP-16 §2.1), а лежит рядом
    # с VizData.kt. Проверка обязана его видеть, иначе вычеркнуть строку будет
    # нечем — и «подключено» останется словом в отчёте.
    ROOT / "core/bal/src/main/kotlin/orbita/bal/Coverage.kt",
]
UNWIRED = ROOT / "ci/unwired.txt"

SEPARATOR = "·"
# Литерал маршрута: начинается со слэша и буквы. "/api" и "/" — не маршруты,
# а основание пути и его остаток.
ROUTE_LITERAL = re.compile(r'"(/[a-z][A-Za-z0-9/_.{}-]*)"')
NOT_A_ROUTE = {"/api", "/api/"}
# Объявление функции. private/internal/local — не публичная поверхность модуля.
FUN_DECL = re.compile(r"^(?P<indent>\s*)(?P<mods>[\w\s@]*?)\bfun\b\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?(?P<name>\w+)\s*[(<]")
NON_PUBLIC = ("private", "internal", "protected", "override")


@dataclass(frozen=True)
class Element:
    """Элемент, подключённость которого проверяется."""

    key: str  # то, что попадает в ci/unwired.txt
    where: str  # человекочитаемое место объявления


def route_literals() -> list[Element]:
    """Маршруты, объявленные в HttpApi.kt."""
    found: dict[str, Element] = {}
    for number, line in enumerate(HTTP_API.read_text(encoding="utf-8").splitlines(), start=1):
        code = line.split("//", 1)[0]
        for literal in ROUTE_LITERAL.findall(code):
            if literal in NOT_A_ROUTE or literal in found:
                continue
            found[literal] = Element(key=f"маршрут {literal}", where=f"HttpApi.kt:{number}")
    return list(found.values())


def route_is_consumed(route: str) -> bool:
    """Есть ли у маршрута потребитель в клиенте."""
    for source in sorted(WEB_API_DIR.glob("*.ts")):
        if route in source.read_text(encoding="utf-8"):
            return True
    return False


def kotlin_files(target: Path) -> list[Path]:
    if target.is_file():
        return [target]
    return sorted(target.rglob("*.kt"))


def view_functions() -> list[Element]:
    """Публичные функции представления."""
    found: list[Element] = []
    seen: set[str] = set()
    for source_root in VIEW_SOURCES:
        for source in kotlin_files(source_root):
            for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
                match = FUN_DECL.match(line.split("//", 1)[0])
                if not match:
                    continue
                if any(modifier in match.group("mods") for modifier in NON_PUBLIC):
                    continue
                name = match.group("name")
                if name in seen:
                    continue
                seen.add(name)
                relative = source.relative_to(ROOT)
                found.append(Element(key=f"функция {name}", where=f"{relative}:{number}"))
    return found


def callers_outside_tests(name: str) -> bool:
    """Есть ли вызов функции вне src/test."""
    call = re.compile(rf"\b{re.escape(name)}\s*\(")
    declaration = re.compile(rf"\bfun\b\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?{re.escape(name)}\s*[(<]")
    for source in sorted((ROOT / "core").rglob("*.kt")):
        if "/src/test/" in str(source):
            continue
        for line in source.read_text(encoding="utf-8").splitlines():
            code = line.split("//", 1)[0]
            if declaration.search(code):
                continue
            if call.search(code):
                return True
    return False


def unwired_elements() -> list[Element]:
    """Всё, что посчитано, но никуда не выведено."""
    unwired = [element for element in route_literals() if not route_is_consumed(element.key.removeprefix("маршрут "))]
    unwired += [element for element in view_functions() if not callers_outside_tests(element.key.removeprefix("функция "))]
    return unwired


def read_exceptions() -> tuple[dict[str, str], list[str]]:
    """Список исключений и претензии к его строкам."""
    if not UNWIRED.exists():
        return {}, []
    exceptions: dict[str, str] = {}
    complaints: list[str] = []
    for number, raw in enumerate(UNWIRED.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split(SEPARATOR)]
        if len(parts) != 3 or not all(parts):
            complaints.append(f"{UNWIRED.name}:{number}: строка без причины или без даты: {line}")
            continue
        element, reason, day = parts
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
            complaints.append(f"{UNWIRED.name}:{number}: дата не вида ГГГГ-ММ-ДД: {day}")
            continue
        exceptions[element] = reason
    return exceptions, complaints


def initialize() -> int:
    """Заполнить список текущим состоянием: руками он разойдётся с кодом."""
    today = date.today().isoformat()
    lines = [
        "# Элементы, посчитанные, но не доведённые до экрана.",
        "# Формат: <элемент> · <причина> · <дата>. Заполняется `ci/wiring.py --init`,",
        "# правится вычёркиванием: подключить или удалить, третьего исхода нет (STEP-16 §2.1).",
        "",
    ]
    for element in unwired_elements():
        lines.append(f"{element.key} {SEPARATOR} не подключено на момент постановки Шага 16 ({element.where}) {SEPARATOR} {today}")
    UNWIRED.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{UNWIRED.relative_to(ROOT)}: записано состояние на {today}")
    return 0


def check() -> int:
    exceptions, complaints = read_exceptions()
    problems = list(complaints)

    unwired = unwired_elements()
    unwired_keys = {element.key for element in unwired}
    for element in unwired:
        if element.key not in exceptions:
            problems.append(f"не подключено и нет в {UNWIRED.name}: {element.key} ({element.where})")

    known_keys = {element.key for element in route_literals()} | {element.key for element in view_functions()}
    for key in exceptions:
        if key not in known_keys:
            problems.append(f"устаревшее исключение (элемент исчез из кода): {key}")
        elif key not in unwired_keys:
            problems.append(f"устаревшее исключение (элемент уже подключён): {key}")

    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print("подключённость: расхождений нет")
    return 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--init":
        return initialize()
    if len(sys.argv) > 1:
        print(__doc__, file=sys.stderr)
        return 2
    return check()


if __name__ == "__main__":
    raise SystemExit(main())
