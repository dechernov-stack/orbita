#!/usr/bin/env python3
"""Сторож ADR-044: носители живут в ОДНОМ дереве — составе системы.

Модель аппарата растворена в узле КА: величины — параметрами узлов, структура
— профилем, контракт собирается из поддерева. Соблазн — вернуть второй состав:
снова читать объект spacecraft «по-быстрому» или ссылаться из сценария на
модель, а не на вхождение. Проверяется:
  1. в живом коде (ядро и клиент) нет ссылки spacecraft_ref — только в
     миграциях и исторических документах;
  2. живой код не читает объекты типа spacecraft напрямую (firstOrNull по типу,
     список по типу) — путь один: Carriers → CarrierAssembly;
  3. сборщик не пересчитывает единицы молча: в CarrierAssembly нет умножений
     и делений над значениями параметров.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIVE_DIRS = [ROOT / "core", ROOT / "web/src"]
SKIP_PARTS = ("/src/test/", "/node_modules/", "/build/")
ASSEMBLY = ROOT / "core/out/src/main/kotlin/orbita/out/CarrierAssembly.kt"

FORBIDDEN = [
    (re.compile(r"\bspacecraft_ref\b"), "ссылка на модель аппарата: сценарий ссылается на вхождение КА (carrier_ref)"),
    (re.compile(r"""type\s*==\s*["']spacecraft["']"""), "чтение объекта spacecraft напрямую — модель собирается из узла КА (Carriers)"),
    (re.compile(r"""list\(\s*['"]spacecraft['"]\s*\)"""), "список моделей аппарата — узлы КА берутся из дерева состава"),
]


def live_files():
    for base in LIVE_DIRS:
        for p in base.rglob("*"):
            if not p.is_file() or p.suffix not in {".kt", ".ts", ".tsx"}:
                continue
            s = str(p)
            if any(part in s for part in SKIP_PARTS):
                continue
            yield p


def main() -> int:
    problems: list[str] = []
    for p in live_files():
        text = p.read_text(encoding="utf-8", errors="replace")
        for pattern, why in FORBIDDEN:
            for m in pattern.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                problems.append(f"{p.relative_to(ROOT)}:{line}: {why} — «{m.group(0)}»")
    # 3. сборщик не пересчитывает единицы: значение либо в ожидаемой единице, либо претензия
    # Величина из величин допустима только ЯВНО: строка несёт маркер
    # «вычислено явно, с происхождением» — и сборка обязана положить о ней
    # запись в computed (энергия из заряда и напряжения, Δv суммой манёвров).
    asm = ASSEMBLY.read_text(encoding="utf-8")
    marker = "вычислено явно, с происхождением"
    for m in re.finditer(r"\.first\s*[*/]\s*|\bvalue\s*[*/]\s*[0-9.]|\+=\s*v\b|\+\s*v\)", asm):
        line = asm.count("\n", 0, m.start()) + 1
        text = asm.splitlines()[line - 1]
        if marker not in text:
            problems.append(f"{ASSEMBLY.relative_to(ROOT)}:{line}: арифметика над значением параметра без маркера «{marker}» — единицы не пересчитываются молча")
    if marker in asm and "computed +=" not in asm:
        problems.append(f"{ASSEMBLY.relative_to(ROOT)}: помеченные вычисления есть, а записи computed нет — происхождение обязано быть словами")
    if problems:
        print("ОДНО ДЕРЕВО НОСИТЕЛЕЙ НАРУШЕНО (ADR-044):")
        for pr in problems:
            print("  " + pr)
        return 1
    print("одно дерево носителей: ссылок на модель аппарата в живом коде нет, сборка без пересчёта единиц")
    return 0


if __name__ == "__main__":
    sys.exit(main())
