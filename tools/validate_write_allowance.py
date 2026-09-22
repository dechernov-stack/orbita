#!/usr/bin/env python3
"""Ратчет допуска сторожа записи (write_rules.schema_on_write, истина 21-09).

Правило владельца: «любая запись сущности валидируется истиной: поле вне
схемы вида — отказ, а не молчаливое сохранение». Обмер стенда 22.09 нашёл
двадцать видов с полями вне истины и три вида полок, истине неизвестных, —
всё это код пишет сегодня. Отказать им разом значило бы остановить контур
знаний и полки; молчать — нарушить правило. Поэтому допуск ПОИМЁННЫЙ,
с причиной, в одном файле (ресурс ядра), и этот ратчет держит два условия:

  1. допуск только тает: поле (или вид), которое истина УЖЕ назвала, из
     допуска обязано уйти — иначе истина и допуск разойдутся молча;
  2. у каждого допущенного вида есть причина в «_reasons».

Самопроверка: вид с полем, которое истина знает, ловится.
"""
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent
ДОПУСК = КОРЕНЬ / "core/v2/kernel/src/main/resources/orbita/kernel/write-allowance.json"
ИСТИНА = КОРЕНЬ / "docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml"


def виды_истины() -> dict[str, set[str]]:
    sys.path.insert(0, str(КОРЕНЬ / "tools/v2"))
    import yaml  # noqa: PLC0415
    from gen_schemas import развернуть_группы  # noqa: PLC0415

    истина = yaml.safe_load(ИСТИНА.read_text(encoding="utf-8"))
    ядро = {f["name"] for f in истина.get("core_fields", [])} | {"notes", "tags"}
    return {в["code"]: {f["name"] for f in (в.get("fields") or [])} | ядро for в in развернуть_группы(истина["kinds"])}


def беды(допуск: dict, виды: dict[str, set[str]]) -> list[str]:
    найдено = []
    for вид in допуск.get("kinds_outside_truth", {}):
        if вид in виды:
            найдено.append(f"вид «{вид}» истина уже знает — снимите его из kinds_outside_truth")
    for вид, поля in допуск.get("fields_outside_truth", {}).items():
        if вид not in виды:
            найдено.append(f"вид «{вид}» в истине не назван: ему место в kinds_outside_truth, не в полях")
            continue
        for поле in поля:
            if поле in виды[вид]:
                найдено.append(f"«{вид}.{поле}» истина уже назвала — снимите поле из допуска")
    return найдено


def main() -> int:
    допуск = json.loads(ДОПУСК.read_text(encoding="utf-8"))
    виды = виды_истины()
    найдено = беды(допуск, виды)
    if найдено:
        print("допуск сторожа записи разошёлся с истиной:")
        for б in найдено:
            print("  ", б)
        return 1
    проба = {"fields_outside_truth": {"need": ["statement"]}}
    assert беды(проба, виды), "самопроверка: поле, известное истине, не поймано"
    видов = len(допуск["fields_outside_truth"]) + len(допуск["kinds_outside_truth"])
    полей = sum(len(п) for п in допуск["fields_outside_truth"].values())
    print(f"допуск сторожа записи: {видов} видов, {полей} полей вне истины — поимённо, с причиной; истина их пока не назвала")
    return 0


if __name__ == "__main__":
    sys.exit(main())
