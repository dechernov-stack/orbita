#!/usr/bin/env python3
"""Сторож порядка хуков клиента.

Находка сборки проекта-примера: экран «Нужды и их сервисы» валил ВЕСЬ
клиент — рейка исчезала, работа обрывалась. Причина не в данных: `useSort`
стоял НИЖЕ ранних возвратов `if (!rows) return <Загрузка…>`. На первом
рендере хуков было восемь, на втором (данные пришли) — десять, и React
падал с ошибкой #310 «Rendered more hooks than during the previous render».

Правило React простое и нарушается незаметно: хуки вызываются в одном и
том же порядке при каждом рендере, а значит — ДО любого раннего возврата.
Сторож это и проверяет: в теле компонента (функция с заглавной буквы,
файл `.tsx`) после первого `return` верхнего уровня не должно быть ни
одного вызова `useЧто-то`.

    python3 tools/validate_hooks_order.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

КОРЕНЬ = Path(__file__).resolve().parent.parent
КЛИЕНТ = КОРЕНЬ / "web/src"

НАЧАЛО_КОМПОНЕНТА = re.compile(r"^(?:export\s+)?(?:function\s+[A-ZА-ЯЁ]|const\s+[A-ZА-ЯЁ][\w]*\s*=\s*\()")
РАННИЙ_ВОЗВРАТ = re.compile(r"^  if\s*\(.*\breturn\b")
ВЫЗОВ_ХУКА = re.compile(r"^  (?:const|let)?\s*.*\buse[A-Z]\w*\s*\(")


def проверить(файл: Path) -> list[str]:
    беды: list[str] = []
    в_компоненте = False
    возврат: int | None = None
    имя = ""
    for номер, строка in enumerate(файл.read_text(encoding="utf-8").split("\n"), 1):
        if НАЧАЛО_КОМПОНЕНТА.match(строка):
            в_компоненте, возврат = True, None
            имя = строка.strip()[:60]
        if not в_компоненте:
            continue
        if РАННИЙ_ВОЗВРАТ.match(строка):
            возврат = номер
        elif возврат and ВЫЗОВ_ХУКА.match(строка):
            беды.append(
                f"{файл.relative_to(КОРЕНЬ)}:{номер}: хук вызван после раннего "
                f"возврата (строка {возврат}) в «{имя}» — на первом приходе "
                f"данных клиент падает с React #310:\n      {строка.strip()[:90]}")
            возврат = None      # об одном компоненте — одна жалоба
    return беды


def main() -> int:
    файлы = sorted(КЛИЕНТ.rglob("*.tsx"))
    беды = [b for f in файлы for b in проверить(f)]
    if беды:
        print("порядок хуков: НАРУШЕН", file=sys.stderr)
        for b in беды:
            print("  ·", b, file=sys.stderr)
        print("\n  Починка: поднять вызов хука ВЫШЕ ранних возвратов, а вход "
              "хука посчитать безопасно (`rows ?? []`).", file=sys.stderr)
        return 1
    print(f"порядок хуков: файлов {len(файлы)}, хуков после раннего возврата нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
