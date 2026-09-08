#!/usr/bin/env python3
"""Сторож запросов шаблонов: `select` обязан называть вид РЕЕСТРА.

Дважды подряд шаблон документа спрашивал вид, которого в реестре нет:
MCReport §2.1 просил `variant` (в реестре `constellation_variant`), ConOps
§4 и §5 — `mode` и `operational_scenario`, которых нет вовсе. Раздел с
таким запросом не наполнится НИКОГДА, и это не видно: пустой раздел
неотличим от непрожитого. Оба случая нашлись живым прогоном, а не кодом.

Проверяется:
  1. `select` каждого запроса — вид из истины схем (СХЕМЫ-ПОЛЕЙ-V2.yaml);
  2. `expand` называет поле, а не вид (частая описка);
  3. `where` не пуст, если объявлен: пустой отбор — забытая мысль;
  4. сцены раздела существуют в шаблоне фазы: раздел не может ждать
     сцену, которой нет (так пропала сцена 9);
  5. поля `where` — поля вида по истине схем (плюс служебные `level_max`,
     `unresolved`): §10 MCReport отбирал `source_mark`, а запись хранила
     `mark` — раздел не наполнялся никогда (шип D). Запись факта теперь
     идёт и по схеме (`source_mark`, `source{material, anchor}`).

    python3 tools/validate_template_kinds.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

КОРЕНЬ = Path(__file__).resolve().parent.parent
ПОЛКИ = КОРЕНЬ / "docs/tz/v2/полки-порождённые"
ВИДЫ = КОРЕНЬ / "core/v2/kernel/src/main/kotlin/orbita/kernel/schema/GeneratedKinds.kt"
ШАБЛОН_ФАЗЫ = ПОЛКИ / "ШАБЛОН-ФАЗЫ-PRE-A-NASA.json"
СХЕМЫ = КОРЕНЬ / "docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml"
СЛУЖЕБНЫЕ_ПОЛЯ = {"code", "status", "id", "version", "level_max", "unresolved"}


def поля_видов() -> dict[str, set[str]]:
    """Поля каждого вида по истине схем; без PyYAML сторож полей молчит вслух."""
    try:
        import yaml  # type: ignore
    except ImportError:
        print("   PyYAML нет — поля запросов не проверены (pip install -r ci/requirements.txt)")
        return {}
    данные = yaml.safe_load(СХЕМЫ.read_text(encoding="utf-8"))
    return {к["code"]: {п["name"] for п in к.get("fields", [])} for к in данные.get("kinds", [])}


def виды_реестра() -> set[str]:
    текст = ВИДЫ.read_text(encoding="utf-8")
    return set(re.findall(r'KindSpec\("([a-z_0-9]+)"', текст))


def сцены_фазы() -> set[str]:
    д = json.loads(ШАБЛОН_ФАЗЫ.read_text(encoding="utf-8"))
    # «polka» сценой не является: так помечают выход на библиотечную полку.
    return {с["key"] for с in д.get("scenes", [])} | {"polka"}


def main() -> int:
    виды = виды_реестра()
    if not виды:
        print("перечень видов не разобран — сторож ослеп", file=sys.stderr)
        return 1
    сцены = сцены_фазы()
    поля = поля_видов()
    беды: list[str] = []
    шаблонов = запросов = 0

    for файл in sorted(ПОЛКИ.glob("ШАБЛОН-*.json")):
        данные = json.loads(файл.read_text(encoding="utf-8"))
        разделы = данные.get("sections")
        if not разделы:
            continue
        шаблонов += 1
        имя = файл.name
        for раздел in разделы:
            номер = раздел.get("no", "?")
            for с in раздел.get("scenes", []):
                if с not in сцены:
                    беды.append(
                        f"{имя} {номер}: ждёт сцену «{с}», которой нет в шаблоне фазы — "
                        "раздел не наполнится никогда",
                    )
            for элемент in раздел.get("elements", []):
                if элемент.get("kind") != "query":
                    continue
                запросов += 1
                код = элемент.get("code", "?")
                вид = элемент.get("select", "")
                if вид not in виды:
                    беды.append(
                        f"{имя} {номер} [{код}]: `select: {вид or '(пусто)'}` — "
                        "вида нет в истине схем, запрос не вернёт ничего никогда",
                    )
                разворот = элемент.get("expand")
                if разворот and разворот in виды:
                    беды.append(
                        f"{имя} {номер} [{код}]: `expand: {разворот}` называет ВИД, "
                        "а разворот берёт ПОЛЕ сущности",
                    )
                if "where" in элемент and not элемент["where"]:
                    беды.append(f"{имя} {номер} [{код}]: пустой `where` — забытая мысль")
                if вид in поля and not разворот:
                    # Только `where`: отбор по полю, которого нет в истине схем,
                    # не совпадёт никогда. Колонки статически не проверяются:
                    # хранилище ещё расходится со схемой (следующий шаг правила
                    # «один сериализатор на вид» — DTO из YAML), и пустоту
                    # колонки честно показывает живой прогон полноты разделов.
                    известные = поля[вид] | СЛУЖЕБНЫЕ_ПОЛЯ
                    for поле in (элемент.get("where") or {}):
                        if поле not in известные:
                            беды.append(
                                f"{имя} {номер} [{код}]: `where.{поле}` — у вида «{вид}» нет такого поля "
                                "в истине схем; отбор не совпадёт никогда",
                            )

    if беды:
        print("запросы шаблонов расходятся с реестром видов:", file=sys.stderr)
        for б in беды:
            print("  · " + б, file=sys.stderr)
        return 1
    print(f"запросы шаблонов: {запросов} запросов в {шаблонов} шаблонах — "
          f"все виды из истины схем, все сцены в шаблоне фазы")
    return 0


if __name__ == "__main__":
    sys.exit(main())
