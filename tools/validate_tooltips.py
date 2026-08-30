#!/usr/bin/env python3
"""Сторож подсказок (МВП-П1 §2.3): элемент без текста обязан нести title.

Правила (по классам-маркерам, кнопкам-иконкам и неактивным контролам):
 1. Маркерный элемент (статусная точка, маркер состояния) без title — отказ.
 2. Кнопка, чьё содержимое начинается с <svg> (иконка без подписи), без
    title — отказ.
 3. Ф-11 (правило-класс, родня «мёртвой ссылке»): НЕАКТИВНЫЙ контрол обязан
    нести причину и путь оживления. Кнопка с disabled без title — отказ:
    инженер, наткнувшийся на серую кнопку, обязан узнать, почему она серая
    и что сделать, чтобы она ожила. Голый disabled запрещён.

Скан — по открывающему тегу целиком (до «>»), тесты не сканируются.
"""
import re
import sys
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent / "web" / "src"

# классы-маркеры: элемент рисует состояние цветом/формой, текста не несёт
MARKER_CLASSES = [
    "ops__state",
    "gr-st ",
    'gr-st"',
    "gr-st$",
    "dot status-",
    "lc2-dot",
]

failures: list[str] = []

for path in sorted(WEB.rglob("*.tsx")):
    if ".test." in path.name:
        continue
    text = path.read_text(encoding="utf-8")
    # открывающие теги span/button целиком (JSX-атрибуты бывают многострочными)
    for m in re.finditer(r"<(span|button)\b", text):
        depth = 0
        i = m.start()
        while i < len(text):
            c = text[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            elif c == ">" and depth == 0:
                break
            i += 1
        tag = text[m.start():i + 1]
        line = text.count("\n", 0, m.start()) + 1
        has_title = "title=" in tag or "title:" in tag
        if m.group(1) == "span":
            if any(
                (cls.rstrip("$") in tag if not cls.endswith("$") else re.search(r'className="[^"]*gr-st"', tag))
                for cls in MARKER_CLASSES
            ) and not has_title:
                failures.append(f"{path.relative_to(WEB.parent.parent)}:{line}: маркер без подсказки — {tag[:80]}…")
        else:
            after = text[i + 1:i + 40].lstrip()
            if after.startswith("<svg") and not has_title:
                failures.append(f"{path.relative_to(WEB.parent.parent)}:{line}: кнопка-иконка без подсказки")
            # Ф-11: серая кнопка обязана объяснить себя. disabled={false}
            # и disabled={undefined} — не запрет, их не считаем.
            disabled = re.search(r"disabled(=\{(?!false\}|undefined\})|\s|>|/)", tag)
            if disabled and not has_title:
                failures.append(
                    f"{path.relative_to(WEB.parent.parent)}:{line}: "
                    f"неактивная кнопка без причины и пути оживления — {tag[:80]}…",
                )

if failures:
    print("подсказки: элементы без title (правило §2.3 — маркер обязан нести расшифровку):")
    for f in failures:
        print(" ", f)
    sys.exit(1)
print(f"подсказки: маркеры и кнопки-иконки несут title (просканированы {len(list(WEB.rglob('*.tsx')))} файлов)")
