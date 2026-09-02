#!/usr/bin/env python3
"""Сторож печати (шип 0 «трёх пакетов»): служебных ключей латиницей в
печатном документе не существует как класса.

Сверка SEMP с каноном нашла в PDF «kind: mission_intent; held: false» —
служебные ключи в продуктовом документе. Печать теперь идёт через
PrintHumanizer: каждая запись вставки — предложение по-русски. Сторож держит
три вещи, и держит их механически:

  1. в PrintRenderer нет сырого сшивания «$k: значение» — записи печатаются
     только через PrintHumanizer.line;
  2. каждый ключ, который генератор кладёт в записи вставок (Documents.kt,
     бюджеты ModelSnapshot), имеет русскую подпись в словаре PrintHumanizer —
     ключ без подписи напечатался бы латиницей;
  3. подписи словаря — по-русски, а не латиница под другим именем.

Живой текст сторожит сервер: выпуск отказывает документу, в строках которого
PrintHumanizer.serviceKeys нашёл ключ (тест PrintHumanTest).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RENDERER = ROOT / "core/out/src/main/kotlin/orbita/out/PrintRenderer.kt"
HUMANIZER = ROOT / "core/out/src/main/kotlin/orbita/out/PrintHumanizer.kt"
EMITTERS = [
    ROOT / "core/out/src/main/kotlin/orbita/out/Documents.kt",
    ROOT / "core/out/src/main/kotlin/orbita/out/ModelSnapshot.kt",
]

# ключи, которые генератор кладёт в записи разделов
EMIT = re.compile(r'\.(?:put|putArray|putObject|withArray|set<[^>]+>)\("([a-z_]+)"')
LABEL = re.compile(r'"([a-z_]+)"\s+to\s+"([^"]+)"')
RAW = re.compile(r'"\$k:\s|\$\{k\}[:=]')


def main() -> int:
    problems: list[str] = []
    renderer = RENDERER.read_text(encoding="utf-8")
    for m in RAW.finditer(renderer):
        line = renderer.count("\n", 0, m.start()) + 1
        problems.append(f"{RENDERER.relative_to(ROOT)}:{line}: сырое сшивание ключей в печати — записи идут через PrintHumanizer")

    humanizer = HUMANIZER.read_text(encoding="utf-8")
    labels_block = humanizer.split("val LABELS", 1)[1].split("val VALUES", 1)[0]
    labels = dict(LABEL.findall(labels_block))
    emitted: set[str] = set()
    for f in EMITTERS:
        emitted |= set(EMIT.findall(f.read_text(encoding="utf-8")))
    # поля, которые печать не показывает вовсе (служебные, не записи)
    skipped = {"template", "title", "source", "expects", "items", "inserts_fingerprint", "text",
               "lifecycle", "id", "sections", "project", "options", "budgets", "constellation_compare",
               # поля РАЗДЕЛА, а не записи: режим, устаревание и диф печать не показывает как поля
               "mode", "text_stale", "text_diff"}
    missing = sorted(k for k in emitted - skipped if k not in labels)
    if missing:
        problems.append("ключи записей без русской подписи в PrintHumanizer.LABELS: " + ", ".join(missing))
    latin = sorted(k for k, v in labels.items() if re.fullmatch(r"[A-Za-z0-9 ,.%/′-]+", v) and not re.search(r"[A-Z]{2,}", v))
    if latin:
        problems.append("подписи словаря латиницей (это не перевод): " + ", ".join(latin))

    if problems:
        print("печать: служебные ключи латиницей запрещены", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    print(f"печать: словарь покрывает ключи генератора ({len(emitted - skipped)}), сырого сшивания нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
