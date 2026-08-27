#!/usr/bin/env python3
"""Ни одного идентификатора объекта в продуктовом коде (Шаг 16 §3.2).

Зашитый идентификатор подставляет чужой проект молча: на пустой базе экран
выглядит сломанным, на демо-базе рабочий проект получает демо-данные.

Обход: регулярное выражение по `core/*/src/main` и `web/src`. Исключения —
в `ci/allowed-literals.txt`, по строке на вхождение:

    <файл>:<идентификатор> · <причина> · <дата>

Правила те же, что у ci/unwired.txt (§1.1): проверка падает, когда вхождение
не описано, когда строка списка устарела (вхождения больше нет) и когда в
строке нет причины или даты. Счётчиков нет.

    python3 ci/literals.py           проверка
    python3 ci/literals.py --init    заполнить список текущим состоянием
"""
from __future__ import annotations

import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWED = ROOT / "ci/allowed-literals.txt"
SEPARATOR = "·"
ID_RE = re.compile(r"\b(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI)-\d{4}\b")


def product_sources() -> list[Path]:
    files: list[Path] = []
    for module in sorted((ROOT / "core").iterdir()):
        main = module / "src/main"
        if main.is_dir():
            files += sorted(main.rglob("*.kt"))
    for ext in ("*.ts", "*.tsx"):
        # тесты клиента — фикстуры, не продуктовый код: та же граница,
        # по которой обход берёт у ядра только src/main
        files += sorted(p for p in (ROOT / "web/src").rglob(ext) if ".test." not in p.name)
    return files


def occurrences() -> dict[str, str]:
    """файл:идентификатор → первое место (файл:строка)."""
    found: dict[str, str] = {}
    for source in product_sources():
        relative = source.relative_to(ROOT)
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
            for m in ID_RE.finditer(line):
                key = f"{relative}:{m.group(0)}"
                found.setdefault(key, f"{relative}:{number}")
    return found


def read_exceptions() -> tuple[dict[str, str], list[str]]:
    if not ALLOWED.exists():
        return {}, []
    exceptions: dict[str, str] = {}
    complaints: list[str] = []
    for number, raw in enumerate(ALLOWED.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split(SEPARATOR)]
        if len(parts) != 3 or not all(parts):
            complaints.append(f"{ALLOWED.name}:{number}: строка без причины или без даты: {line}")
            continue
        key, reason, day = parts
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
            complaints.append(f"{ALLOWED.name}:{number}: дата не вида ГГГГ-ММ-ДД: {day}")
            continue
        exceptions[key] = reason
    return exceptions, complaints


def initialize() -> int:
    today = date.today().isoformat()
    lines = [
        "# Идентификаторы объектов в продуктовом коде — только оснастка, с причинами.",
        "# Формат: <файл>:<идентификатор> · <причина> · <дата>. Заполняется",
        "# `ci/literals.py --init`, правится вычёркиванием (STEP-16 §3.2).",
        "",
    ]
    for key, where in sorted(occurrences().items()):
        lines.append(f"{key} {SEPARATOR} не разобрано на момент постановки Шага 16 ({where}) {SEPARATOR} {today}")
    ALLOWED.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{ALLOWED.relative_to(ROOT)}: записано состояние на {today}")
    return 0


def check() -> int:
    exceptions, problems = read_exceptions()
    found = occurrences()
    for key, where in sorted(found.items()):
        if key not in exceptions:
            problems.append(f"идентификатор в продуктовом коде без исключения: {key} ({where})")
    for key in exceptions:
        if key not in found:
            problems.append(f"устаревшее исключение (вхождение исчезло): {key}")
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print("идентификаторы: расхождений нет")
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
