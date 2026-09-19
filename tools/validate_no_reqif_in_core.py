#!/usr/bin/env python3
"""Сторож «REQ-IF в нашем коде — 0» (ответ владельца 09.09, ШИП-F-ПРИНЯТ).

Формат обмена знает служба обмена (ops/exchange, библиотека reqif) и StrictDoc
(ops/strictdoc); ядро (core/**/src/main) получает объекты с атрибутами по
именам и не держит ни имён стандарта (`ReqIF.*`), ни тегов XML
(`SPEC-OBJECT`, `SPEC-RELATION`, `REQ-IF`). Слово «ReqIF» в комментариях и
адрес маршрута /import/reqif — не формат, они разрешены.
Самопроверка: нарочная строка ловится.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PATTERN = re.compile(r"SPEC-OBJECT|SPEC-RELATION|SPECIFICATION-TYPE|ATTRIBUTE-DEFINITION|ReqIF\.[A-Za-z]|REQ-IF")


# Сгенерированные из истины таблицы — не наш код, а слова владельца, перенесённые
# дословно: у вида «выгрузка» примечание поля само называет профиль обмена
# («атрибуты ReqIF.ForeignID/Name/…»). Логики формата там нет и быть не может —
# генератор переносит текст, поэтому файлы Generated*.kt из правила исключены.
# Правило остаётся в силе для всего написанного руками.
СГЕНЕРИРОВАНО = re.compile(r"/schema/Generated[A-Za-z]*\.kt$")


def scan(root: pathlib.Path) -> list[str]:
    hits = []
    for f in sorted(root.glob("core/**/src/main/**/*.kt")):
        if "/build/" in str(f) or СГЕНЕРИРОВАНО.search(str(f)):
            continue
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if PATTERN.search(line):
                hits.append(f"{f.relative_to(root)}:{n}: {line.strip()[:100]}")
    return hits


def main() -> int:
    hits = scan(ROOT)
    if hits:
        print("формат обмена просочился в ядро (REQ-IF в нашем коде должен быть 0):")
        for h in hits:
            print("  ", h)
        return 1
    assert PATTERN.search('val x = "SPEC-OBJECT"'), "самопроверка: нарочная строка не поймана"
    assert not PATTERN.search("// ReqIF штатным экспортом; /import/reqif"), "самопроверка: слово в комментарии не нарушение"
    assert СГЕНЕРИРОВАНО.search("core/v2/kernel/src/main/kotlin/orbita/kernel/schema/GeneratedKinds.kt"), \
        "самопроверка: сгенерированная таблица истины опознаётся исключением"
    assert not СГЕНЕРИРОВАНО.search("core/v2/exchange/src/main/kotlin/orbita/exchange/internal/SdocPayload.kt"), \
        "самопроверка: рукописный файл исключением не считается"
    print("формат обмена в ядре: 0 упоминаний тегов и имён стандарта (самопроверка: нарочная строка поймана)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
