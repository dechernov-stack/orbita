#!/usr/bin/env python3
"""Сторожа текста интерфейса (шип 3 прогона 04.09).

1. `undefined` / `NaN` в ВИДИМОМ тексте — 0 (Б5-02): пустое поле показывается
   пометой «— не задан», а не служебным словом языка.
2. Имя вида ЛАТИНИЦЕЙ в видимом тексте — 0: «выход не создан (conops)»,
   «3 AP · 5 FC · 23 FN» человеку ничего не говорят. Вид называется
   по-русски: словарь `enum-labels-ru.json` (группа `object_kind`) на
   сервере, `ui/countPhrase.ts` — в клиенте.

Видимый текст — это строковые литералы и текстовые узлы JSX. Код (вызовы,
ключи, className, сравнения кодов) сторожем не трогается: там латинские имена
законны.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web/src"
ИСТОЧНИКИ = (ROOT / "web/src", ROOT / "core/com/src/main/kotlin", ROOT / "core/req/src/main/kotlin")

ЛИТЕРАЛ = re.compile(r"'([^'\\\n]{2,})'|\"([^\"\\\n]{2,})\"|`([^`\\]{2,})`")
JSX_ТЕКСТ = re.compile(r">\s*([^<>{}\n]{3,})\s*<")
СЛУЖЕБНОЕ_СЛОВО = re.compile(r"\b(undefined|NaN)\b")
# «(conops)», «· conops ·» — вид как слово текста
ВИД_В_СКОБКАХ = re.compile(r"[(«]\s*([a-z][a-z_]{3,})\s*[)»]")
# «3 AP», «14 SM» — префикс идентификатора вместо слова
ПРЕФИКС_ПОСЛЕ_ЧИСЛА = re.compile(r"\b\d+\s+([A-Z]{2,3})\b")
ПРЕФИКСЫ = {
    "ND", "SV", "RQ", "CM", "IF", "SC", "EV", "VA", "RSK", "CO", "FN", "FC", "OC", "LC",
    "ME", "AR", "SM", "MG", "AL", "CE", "OD", "RF", "WB", "AP", "SD", "DT", "DI", "SK", "TL", "DN",
}


def виды() -> set[str]:
    p = ROOT / "core/req/src/main/resources/orbita/req/enum-labels-ru.json"
    d = json.loads(p.read_text(encoding="utf-8"))
    return set((d.get("groups") or d).get("object_kind", {}))


def видимый_текст(строка: str) -> list[str]:
    куски = [g for m in ЛИТЕРАЛ.finditer(строка) for g in m.groups() if g]
    куски += JSX_ТЕКСТ.findall(строка)
    return куски


# Б5-03: виды извлечения ПО ТИПУ заменены единым смысловым разбором (Д2) —
# кнопки с ними на карточке документа ведут в отказ «нет профиля службы»
ВИДЫ_ИЗВЛЕЧЕНИЯ = re.compile(r"mission_to_(stakeholders|typical_risks)")
КАРТОЧКА_ДОКУМЕНТА = ("screens/StartPath.tsx", "screens/DocParse.tsx", "screens/LibraryKnowledge.tsx")


def main() -> int:
    словарь = виды()
    if not словарь:
        print("в словаре нет группы object_kind", file=sys.stderr)
        return 1
    находки: list[str] = []
    файлов = 0
    for корень in ИСТОЧНИКИ:
        for путь in sorted(list(корень.rglob("*.tsx")) + list(корень.rglob("*.kt"))):
            файлов += 1
            отн = str(путь.relative_to(ROOT))
            for n, строка in enumerate(путь.read_text(encoding="utf-8").splitlines(), start=1):
                код = строка.split("//")[0]
                for текст in видимый_текст(код):
                    # текст без кириллицы — это ключ, путь или класс, не речь
                    if not re.search(r"[А-Яа-яЁё]", текст):
                        continue
                    if СЛУЖЕБНОЕ_СЛОВО.search(текст):
                        находки.append(f"{отн}:{n}: служебное слово языка в тексте — «{текст.strip()[:70]}»")
                    for имя in ВИД_В_СКОБКАХ.findall(текст):
                        if имя in словарь:
                            находки.append(f"{отн}:{n}: вид латиницей «{имя}» в тексте — «{текст.strip()[:70]}»")
                    for префикс in ПРЕФИКС_ПОСЛЕ_ЧИСЛА.findall(текст):
                        if префикс in ПРЕФИКСЫ:
                            находки.append(f"{отн}:{n}: префикс вида «{префикс}» вместо слова — «{текст.strip()[:70]}»")
    for имя in КАРТОЧКА_ДОКУМЕНТА:
        путь = WEB / имя
        if not путь.exists():
            continue
        for n, строка in enumerate(путь.read_text(encoding="utf-8").splitlines(), start=1):
            if строка.strip().startswith(("//", "*", "/*")):
                continue
            if ВИДЫ_ИЗВЛЕЧЕНИЯ.search(строка):
                находки.append(
                    f"{имя}:{n}: вид извлечения по типу на карточке документа — "
                    "разбор один («Разобрать документ», Д2)"
                )
    if находки:
        print("текст интерфейса говорит служебными словами:", file=sys.stderr)
        for x in находки:
            print("  " + x, file=sys.stderr)
        return 1
    print(f"текст интерфейса: служебных слов и латинских имён видов нет (просканированы {файлов} файлов)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
