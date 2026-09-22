#!/usr/bin/env python3
"""Сторожа дизайна шипа 2 (ЗАДАНИЕ-ШИП-2-ДИЗАЙН, «Токены и сторожа»).

1. Капслок в подписях: слово из четырёх и более ЗАГЛАВНЫХ кириллических букв в
   видимом тексте — отказ («ПОСТАНОВ…» из макета Stitch — ровно тот шум,
   который вычищаем). Латинские аббревиатуры (MCR, KDP-A, TRL, QoS) — не подписи.
2. Усечение без подсказки: класс с `text-overflow: ellipsis` в CSS обязан
   стоять на элементе с title — иначе обрезанное имя нечем прочитать.
3. Декоративные строки: «// V.04», «0x00», «_EMPTY_» и подобные метаданные-
   украшения в видимом тексте — отказ (тест действия не пройден).
4. Третья колонка у инженера: рамка мероприятия (`.v2-act2`) — поверхность +
   узкая рейка, сетка из трёх колонок в её CSS запрещена.
5. Список исключений пуст — намеренно: исключение вписывается сюда только
   со ссылкой на решение владельца.

Самопроверка: каждое правило ловит подделку.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

КОРЕНЬ = Path(__file__).resolve().parent.parent
WEB = КОРЕНЬ / "web" / "src" / "v2"
CSS = WEB / "tokens.css"
ИСКЛЮЧЕНИЯ: set[str] = set()

# Видимый текст: текстовые узлы JSX (>текст<) и строковые литералы в JSX-выражениях.
ТЕКСТ_JSX = re.compile(r">([^<>{}]+)<")
СТРОКИ = re.compile(r"""(?<![\w.])(['"])((?:(?!\1).){3,}?)\1""")
КАПСЛОК = re.compile(r"\b[А-ЯЁ]{4,}\b")
# Технические аббревиатуры кириллицей — не капслок подписи, а имя вещи
# (узел БЦВМ, ГАИС «ЭРА-ГЛОНАСС»): это словарь, не исключение из правила.
АББРЕВИАТУРЫ = {"БЦВМ", "НСКУ", "ГАИС", "ГЛОНАСС", "ОБПУ", "СПЭП", "БРТК", "МКА"}
ДЕКОР = re.compile(r"//\s*V\.\d|0x[0-9A-Fa-f]{2,}|_[A-Z]{3,}_|\b[A-Z]{3,}_[A-Z]{3,}\b")


def видимый_текст(текст: str) -> list[tuple[int, str]]:
    """Строки видимого текста с номером строки: узлы JSX и литералы title/aria-label/текста."""
    найдено: list[tuple[int, str]] = []
    for номер, строка in enumerate(текст.splitlines(), 1):
        if строка.lstrip().startswith(("//", "*", "/*", "import ", "export type", "type ")):
            continue
        for м in ТЕКСТ_JSX.finditer(строка):
            найдено.append((номер, м.group(1)))
        for м in СТРОКИ.finditer(строка):
            найдено.append((номер, м.group(2)))
    return найдено


def капслок(путь: Path, текст: str) -> list[str]:
    беды = []
    for номер, кусок in видимый_текст(текст):
        if any(с not in АББРЕВИАТУРЫ for с in КАПСЛОК.findall(кусок)):
            беды.append(f"{путь.relative_to(КОРЕНЬ)}:{номер}: капслок в подписи — «{кусок.strip()[:60]}»")
    return беды


def декор(путь: Path, текст: str) -> list[str]:
    беды = []
    for номер, кусок in видимый_текст(текст):
        if ДЕКОР.search(кусок):
            беды.append(f"{путь.relative_to(КОРЕНЬ)}:{номер}: декоративная строка — «{кусок.strip()[:60]}»")
    return беды


def усечение(css: str, файлы: dict[Path, str]) -> list[str]:
    """Классы с ellipsis: их элементы обязаны нести title."""
    беды = []
    классы = set()
    for блок in re.finditer(r"([^{}]+)\{([^}]*)\}", css):
        if "text-overflow: ellipsis" in блок.group(2) or "text-overflow:ellipsis" in блок.group(2):
            for имя in re.findall(r"\.([a-z0-9_-]+)", блок.group(1)):
                классы.add(имя)
    for путь, текст in файлы.items():
        for имя in классы:
            for м in re.finditer(r"<(\w+)[^>]*className=[\"'`][^\"'`]*\b%s\b[^>]*>" % re.escape(имя), текст):
                тег = м.group(0)
                if "title=" not in тег:
                    номер = текст.count("\n", 0, м.start()) + 1
                    беды.append(f"{путь.relative_to(КОРЕНЬ)}:{номер}: усечение «{имя}» без title")
    return беды


def третья_колонка(css: str) -> list[str]:
    беды = []
    for блок in re.finditer(r"(\.v2-act2[^{}]*)\{([^}]*)\}", css):
        м = re.search(r"grid-template-columns:\s*([^;]+);", блок.group(2))
        if not м:
            continue
        колонок = len([к for к in re.split(r"\s+", м.group(1).strip()) if к and not к.startswith("minmax(") or к.startswith("minmax(")])
        # repeat(...) и minmax(...) считаются одной колонкой каждая
        колонок = len(re.findall(r"minmax\([^)]*\)|repeat\([^)]*\)|[^\s]+", м.group(1).strip()))
        if колонок >= 3:
            беды.append(f"tokens.css: {блок.group(1).strip()} — {колонок} колонки: у инженера поверхность + рейка, третьей не бывает")
    return беды


def проверить(css: str, файлы: dict[Path, str]) -> list[str]:
    беды: list[str] = []
    for путь, текст in файлы.items():
        if ".test." in путь.name:
            continue
        беды += капслок(путь, текст)
        беды += декор(путь, текст)
    беды += усечение(css, файлы)
    беды += третья_колонка(css)
    return [б for б in беды if not any(и in б for и in ИСКЛЮЧЕНИЯ)]


def самопроверка() -> None:
    подделка = {Path(КОРЕНЬ / "web/src/v2/подделка.tsx"): '<span title="x">ПОСТАНОВКА</span>\n<b>PORTFOLIO_REGISTRY // V.04</b>\n<i className="v2-cut">имя</i>'}
    css = ".v2-cut { text-overflow: ellipsis; }\n.v2-act2 { grid-template-columns: 1fr 240px 200px; }"
    беды = проверить(css, подделка)
    assert any("капслок" in б for б in беды), "самопроверка: капслок не пойман"
    assert any("декоративная" in б for б in беды), "самопроверка: декоративная строка не поймана"
    assert any("усечение" in б for б in беды), "самопроверка: усечение без title не поймано"
    assert any("третьей не бывает" in б for б in беды), "самопроверка: третья колонка не поймана"
    assert not проверить(".v2-act2 { grid-template-columns: 1fr 128px; }", {Path(КОРЕНЬ / "web/src/v2/чисто.tsx"): '<span title="MCR">точка MCR · KDP-A</span>'}), "самопроверка: чистое поймано зря"


def main() -> int:
    самопроверка()
    файлы = {п: п.read_text(encoding="utf-8") for п in sorted(WEB.glob("*.tsx"))}
    беды = проверить(CSS.read_text(encoding="utf-8"), файлы)
    if беды:
        print("сторожа дизайна (шип 2):")
        for б in беды:
            print("  ", б)
        return 1
    print(f"сторожа дизайна: капслок, усечение, декор, третья колонка — чисто (файлов {len(файлы)}, исключений {len(ИСКЛЮЧЕНИЯ)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
