#!/usr/bin/env python3
"""Сторож: нативных диалогов браузера в клиенте нет (блокер З-01, 04.09).

`window.confirm`/`alert`/`prompt` во встроенном контексте подавляются: браузер
возвращает «нет», и действие молча не происходит — человек видит мёртвую
кнопку. Так пропало назначение заданий на прогоне ПМИ-4. Подтверждение и ввод
причины — своим окном (`ui/Confirm.tsx`), которое живёт в разметке экрана.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web/src"
ЗАПРЕТ = re.compile(r"\bwindow\.(confirm|alert|prompt)\s*\(")
# своё окно объясняет запрет в комментарии — там имена упоминаются законно
ИСКЛЮЧЕНИЯ = {"ui/Confirm.tsx"}


def main() -> int:
    находки: list[str] = []
    файлов = 0
    for путь in sorted(WEB.rglob("*.ts*")):
        отн = str(путь.relative_to(WEB))
        if отн in ИСКЛЮЧЕНИЯ:
            continue
        файлов += 1
        for n, строка in enumerate(путь.read_text(encoding="utf-8").splitlines(), start=1):
            без_комментария = строка.split("//")[0]
            if ЗАПРЕТ.search(без_комментария):
                находки.append(f"{отн}:{n}: {строка.strip()[:100]}")
    if находки:
        print("нативные диалоги браузера в клиенте (подавляются во встроенном контексте):", file=sys.stderr)
        for x in находки:
            print("  " + x, file=sys.stderr)
        print("  подтверждение и ввод причины — через ui/Confirm.tsx (ConfirmBox + useConfirm)", file=sys.stderr)
        return 1
    print(f"диалоги: нативных confirm/alert/prompt нет (просканированы {файлов} файлов)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
