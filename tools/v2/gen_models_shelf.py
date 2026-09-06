#!/usr/bin/env python3
"""Полка моделей системы — ИЗ ПОСТАВКИ, а не из головы.

Таблица `ШАБЛОН-МОДЕЛИ-СИСТЕМЫ.md` — единственный источник: номер, имя,
вопрос, входы, выходы, точка, инструмент. Здесь она разворачивается в
полку `model_template`, которую берёт проект.

  python3 tools/v2/gen_models_shelf.py            # записать полку
  python3 tools/v2/gen_models_shelf.py --check    # сторож: полка не разошлась
"""
import argparse
import json
import pathlib
import re
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ИСТОЧНИК = КОРЕНЬ / "docs/tz/v2/ШАБЛОН-МОДЕЛИ-СИСТЕМЫ.md"
ПОЛКА = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ПОЛКА-МОДЕЛИ-СИСТЕМЫ.json"

# Класс миссии выбирает обязательный поднабор (правило 5 поставки).
ОБЯЗАТЕЛЬНЫЕ_НОО_IOT = {
    "SRR": ["М1", "М2", "М3", "М4", "М6", "М8", "М10", "М12", "М13", "М14"],
    "SDR": ["М5", "М7", "М9"],
}


def очистить(ячейка: str) -> str:
    """Убирает разметку жирного и лишние пробелы — в данных её место нет."""
    return re.sub(r"\*\*|`", "", ячейка).strip()


def собрать() -> dict:
    текст = ИСТОЧНИК.read_text(encoding="utf-8")
    модели = []
    for строка in текст.splitlines():
        if not строка.startswith("| М"):
            continue
        ячейки = [очистить(я) for я in строка.strip("|").split("|")]
        # Подстроки потоков (М3а–е) свой вопрос не повторяют — у них шесть
        # колонок вместо семи. Формат поставки, а не ошибка разбора.
        if len(ячейки) == 6:
            код, имя, входы, выходы, точка, инструмент = ячейки
            вопрос = ""
        elif len(ячейки) >= 7:
            код, имя, вопрос, входы, выходы, точка, инструмент = ячейки[:7]
        else:
            continue
        # Подмодели («М2а · абонентская») несут родителя в коде.
        родитель = код[:2] if len(код) > 2 and код[2] in "абвгде" else None
        модели.append({
            "code": код,
            "name": имя.lstrip("· ").strip(),
            "parent": родитель,
            "question": вопрос,
            "inputs_text": входы,
            "outputs_text": выходы,
            "required_to": точка.split("(")[0].strip().split(",")[0].strip(),
            "tool_status": (
                "build" if "построить" in инструмент
                else "proxy" if "прокси" in инструмент
                else "calc"
            ),
            # Поток и линк привязаны к интерфейсу PBS (правило 3а поставки).
            "interface_bound": bool(re.match(r"^М[23][абвгде]$", код)),
        })
    return {
        "kind": "model_template",
        "code": "MDL-9001",
        "version": 1,
        "title": "Минимальный набор моделей системы (класс НОО-IoT)",
        "source": "docs/tz/v2/ШАБЛОН-МОДЕЛИ-СИСТЕМЫ.md",
        "rule": "модель — ответ на инженерный вопрос; готовность = «модель дала ответ», а не наличие файла",
        "required_by_gate": ОБЯЗАТЕЛЬНЫЕ_НОО_IOT,
        "models": модели,
    }


if __name__ == "__main__":
    разбор = argparse.ArgumentParser(description="полка моделей системы из поставки")
    разбор.add_argument("--check", action="store_true", help="сторож: полка совпадает с поставкой")
    аргументы = разбор.parse_args()

    полка = собрать()
    новое = json.dumps(полка, ensure_ascii=False, indent=2) + "\n"
    if аргументы.check:
        прежнее = ПОЛКА.read_text(encoding="utf-8") if ПОЛКА.exists() else ""
        if прежнее != новое:
            print("полка моделей разошлась с поставкой — перегенерируйте: python3 tools/v2/gen_models_shelf.py")
            sys.exit(1)
        print(f"полка моделей: {len(полка['models'])} записей совпадают с поставкой")
        sys.exit(0)
    ПОЛКА.write_text(новое, encoding="utf-8")
    print(f"полка моделей: {len(полка['models'])} записей → {ПОЛКА.relative_to(КОРЕНЬ)}")
