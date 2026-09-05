#!/usr/bin/env python3
"""Генератор схем v2: СХЕМЫ-ПОЛЕЙ-V2.yaml → JSON Schema + Kotlin.

Правило ТЗ-BACKEND §2.6: «схемы видов — из YAML генерацией; вид вне YAML
не существует; поле вне схемы — отказ». Значит рукописных схем v2 не
бывает: этот генератор — единственный их источник, а сторож
(`--check`) валит сборку, если сгенерированное разошлось с YAML.

  python3 tools/v2/gen_schemas.py          # перегенерировать
  python3 tools/v2/gen_schemas.py --check  # сторож: расхождение — отказ

Куда пишет:
  schemas/v2/<code>.schema.json      — по одной схеме на вид
  schemas/v2/_core.schema.json       — ядро сущности (12 полей)
  core/kernel/src/main/kotlin/orbita/kernel/schema/GeneratedKinds.kt
"""
import argparse
import json
import pathlib
import re
import sys

import yaml

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ИСТОЧНИК = КОРЕНЬ / "docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml"
СХЕМЫ = КОРЕНЬ / "schemas/v2"
KOTLIN = КОРЕНЬ / "core/kernel/src/main/kotlin/orbita/kernel/schema/GeneratedKinds.kt"

ШАПКА = "СГЕНЕРИРОВАНО tools/v2/gen_schemas.py из docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml — руками не править"


def разобрать_тип(сырой: str) -> dict:
    """Тип поля YAML → фрагмент JSON Schema.

    Формы, встречающиеся в истине схем:
      str · text · int · num · bool · ts · date · id · json
      enum[a,b,c]          — перечисление
      ref kind             — ссылка на сущность вида
      ref[] kind           — множественная ссылка
      measure{...}         — величина с единицей (объект)
      [{a*,b?}]            — массив объектов с полями
      path md              — путь к файлу
      class[]              — массив строк-классов
    """
    t = (сырой or "").strip()
    if not t:
        return {"description": "тип не задан в истине схем"}

    # массив объектов: [{code*,name*,order?}]
    м = re.match(r"^\[\{(.+)\}\]$", t)
    if м:
        поля, обяз = {}, []
        for кусок in м.group(1).split(","):
            кусок = кусок.strip()
            if not кусок:
                continue
            имя = кусок.rstrip("*?")
            поля[имя] = {"type": ["string", "number", "boolean", "object", "array", "null"]}
            if кусок.endswith("*"):
                обяз.append(имя)
        узел = {"type": "array", "items": {"type": "object", "properties": поля}}
        if обяз:
            узел["items"]["required"] = обяз
        return узел

    if t.startswith("measure"):
        return {"$ref": "https://kis.local/schemas/v2/_measure.schema.json"}

    м = re.match(r"^enum\[(.+)\]$", t)
    if м:
        значения = [x.strip() for x in м.group(1).split(",") if x.strip()]
        return {"enum": значения}
    if t.startswith("enum"):
        # enum без перечня («enum по статусной модели вида») — строка со следом
        return {"type": "string", "description": t}

    м = re.match(r"^ref\[\]\s*(.*)$", t)
    if м:
        цель = м.group(1).strip()
        узел = {"type": "array", "items": {"type": "string"}}
        if цель:
            узел["items"]["description"] = f"ссылка на вид: {цель}"
        return узел

    м = re.match(r"^ref\s+(.+)$", t)
    if м:
        return {"type": "string", "description": f"ссылка на вид: {м.group(1).strip()}"}
    if t == "ref":
        return {"type": "string", "description": "ссылка на сущность"}

    if t.startswith("class"):
        return {"type": "array", "items": {"type": "string"}}
    if t.startswith("path"):
        return {"type": "string", "description": f"путь к файлу ({t})"}
    if t.startswith("json"):
        return {"description": "произвольная структура (json)"}
    if t.startswith("route"):
        return {"type": "string", "description": "маршрут интерфейса"}
    if t.startswith("{"):
        return {"type": "object", "description": t}

    простые = {
        "str": {"type": "string"},
        "str[]": {"type": "array", "items": {"type": "string"}},
        "text": {"type": "string"},
        "int": {"type": "integer"},
        "num": {"type": "number"},
        "bool": {"type": "boolean"},
        "ts": {"type": "string", "format": "date-time"},
        "date": {"type": "string", "format": "date"},
        "id": {"type": "string"},
    }
    if t in простые:
        return dict(простые[t])
    if t.endswith("[]") and t[:-2] in простые:
        return {"type": "array", "items": dict(простые[t[:-2]])}
    return {"description": t}


def схема_вида(вид: dict, ядро: list) -> dict:
    свойства, обязательные = {}, []
    for f in ядро:
        узел = разобрать_тип(f["type"])
        if f.get("note"):
            узел = dict(узел, description=(узел.get("description", "") + " " + f["note"]).strip())
        свойства[f["name"]] = узел
        if f.get("required"):
            обязательные.append(f["name"])
    for f in (вид.get("fields") or []):
        узел = разобрать_тип(f["type"])
        if f.get("note"):
            узел = dict(узел, description=(узел.get("description", "") + " " + f["note"]).strip())
        свойства[f["name"]] = узел
        if f.get("required"):
            обязательные.append(f["name"])
    схема = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": f"https://kis.local/schemas/v2/{вид['code']}.schema.json",
        "title": вид["code"],
        "description": (
            f"{вид.get('name', '')} — вид {вид['n']} слоя {вид.get('layer', '—')}. "
            f"Сцена рождения: {вид.get('born_in') or '—'}. "
            + (
                f"В истине схем описан группой «{вид['_group']}» — поля общие на группу. "
                if вид.get("_group") else ""
            )
            + ШАПКА
        ).strip(),
        "type": "object",
        "additionalProperties": False,
        "properties": свойства,
    }
    if обязательные:
        схема["required"] = обязательные
    return схема


def котлин(kinds: list) -> str:
    строки = [
        "// " + ШАПКА,
        "//",
        "// Перечень видов v2 — единственный законный источник для кода: вид, которого",
        "// здесь нет, не существует (ТЗ-BACKEND §2.6). Слой и сцена рождения нужны",
        "// сторожам: видимость по сцене и запрет зависимостей вверх по слоям.",
        "package orbita.kernel.schema",
        "",
        "/** Слой модели данных: L0 — полки, L5 — выпуск. */",
        "enum class Layer { L0, L1, L2, L3, L4, L5 }",
        "",
        "/**",
        " * Вид сущности v2.",
        " *",
        " * @property code машинный код вида — им же названа схема в schemas/v2",
        " * @property title имя вида по-русски: интерфейс показывает его, не код",
        " * @property layer слой; зависимости идут только вниз",
        " * @property bornIn сцена рождения; null — сущность полки, у неё сцены нет",
        " * @property statusModel статусная модель, если у вида она есть",
        " */",
        "data class KindSpec(",
        "    val code: String,",
        "    val title: String,",
        "    val layer: Layer,",
        "    val bornIn: String?,",
        "    val statusModel: String?,",
        "    val requiredFields: List<String>,",
        ")",
        "",
        "object GeneratedKinds {",
        "",
        "    val all: List<KindSpec> = listOf(",
    ]
    for k in kinds:
        слой = (k.get("layer") or "L0").split("/")[0]
        сцена = k.get("born_in")
        сцена = None if сцена in (None, "—", "-", "") else str(сцена)
        модель = k.get("status_model")
        модель = None if модель in (None, "—", "-", "") else str(модель)
        обяз = [f["name"] for f in (k.get("fields") or []) if f.get("required")]
        перечень = ", ".join(f'"{x}"' for x in обяз)
        сцена_kt = f'"{сцена}"' if сцена else "null"
        модель_kt = f'"{модель}"' if модель else "null"
        строки.append(
            f'        KindSpec("{k["code"]}", "{k.get("name", "")}", Layer.{слой}, '
            f'{сцена_kt}, {модель_kt}, listOf({перечень})),'
        )
    строки += [
        "    )",
        "",
        "    val byCode: Map<String, KindSpec> = all.associateBy { it.code }",
        "",
        "    /** Вид вне перечня не существует: отказ вместо тихого пропуска. */",
        "    fun of(code: String): KindSpec = byCode[code]",
        "        ?: error(\"вид «$code» не описан в СХЕМЫ-ПОЛЕЙ-V2.yaml — вид вне истины схем не существует\")",
        "}",
        "",
    ]
    return "\n".join(строки)


def мера_схема() -> dict:
    """Величина: значение либо диапазон, всегда с единицей и оператором."""
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://kis.local/schemas/v2/_measure.schema.json",
        "title": "measure",
        "description": (
            "Величина с единицей: либо значение, либо диапазон min/max. "
            "Единица обязательна — величина без единицы ошибка (правило проекта). " + ШАПКА
        ),
        "type": "object",
        "additionalProperties": False,
        "required": ["unit"],
        "properties": {
            "op": {
                "enum": ["=", ">=", "<=", ">", "<", "~"],
                "description": "оператор сравнения: показатель «не менее» отличается от «равно»",
            },
            "value": {"type": "number"},
            "min": {"type": "number"},
            "max": {"type": "number"},
            "unit": {"type": "string", "description": "ссылка на вид unit — единица из справочника"},
        },
    }


def развернуть_группы(kinds: list) -> list:
    """Строка истины схем может описывать НЕСКОЛЬКО видов сразу.

    Две записи поставки — «typical_requirement · typical_risk ·
    stakeholder_profile» и «account · role · right» — перечисляют по три
    вида в одном коде: у них общий набор полей и общая роль. Схему нельзя
    назвать таким кодом (в имени файла пробелы и точки), и вид с пробелом
    в коде не существует.

    Разворачиваем в отдельные виды с общими полями и оставляем след группы
    в описании — чтобы расхождение поставки было видно, а не замазано.
    Вопрос владельцу об этом стоит в отчёте волны 0.
    """
    развёрнутые = []
    for вид in kinds:
        код = (вид.get("code") or "").strip()
        if "·" not in код:
            развёрнутые.append(вид)
            continue
        части = [x.strip() for x in код.split("·") if x.strip()]
        for часть in части:
            копия = dict(вид)
            копия["code"] = часть
            копия["_group"] = код
            развёрнутые.append(копия)
    return развёрнутые


def собрать() -> dict:
    истина = yaml.safe_load(ИСТОЧНИК.read_text(encoding="utf-8"))
    истина["kinds"] = развернуть_группы(истина["kinds"])
    файлы = {"_core.schema.json": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://kis.local/schemas/v2/_core.schema.json",
        "title": "core",
        "description": "Ядро сущности: поля, которые есть у каждого вида. " + ШАПКА,
        "type": "object",
        "properties": {f["name"]: разобрать_тип(f["type"]) for f in истина["core_fields"]},
        "required": [f["name"] for f in истина["core_fields"] if f.get("required")],
    }, "_measure.schema.json": мера_схема()}
    for вид in истина["kinds"]:
        файлы[f"{вид['code']}.schema.json"] = схема_вида(вид, истина["core_fields"])
    return файлы, истина["kinds"]


def текст(схема: dict) -> str:
    return json.dumps(схема, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def main() -> int:
    разбор = argparse.ArgumentParser(description="генератор схем v2 из истины YAML")
    разбор.add_argument("--check", action="store_true", help="сторож: расхождение с YAML — отказ")
    аргументы = разбор.parse_args()

    файлы, kinds = собрать()
    код = котлин(kinds)

    if аргументы.check:
        расхождения = []
        на_диске = {p.name for p in СХЕМЫ.glob("*.json")} if СХЕМЫ.exists() else set()
        лишние = на_диске - set(файлы)
        if лишние:
            расхождения.append(f"схемы без вида в YAML: {sorted(лишние)}")
        for имя, схема in файлы.items():
            путь = СХЕМЫ / имя
            if not путь.exists():
                расхождения.append(f"нет файла: schemas/v2/{имя}")
            elif путь.read_text(encoding="utf-8") != текст(схема):
                расхождения.append(f"разошлось с YAML: schemas/v2/{имя}")
        if not KOTLIN.exists():
            расхождения.append(f"нет файла: {KOTLIN.relative_to(КОРЕНЬ)}")
        elif KOTLIN.read_text(encoding="utf-8") != код:
            расхождения.append(f"разошлось с YAML: {KOTLIN.relative_to(КОРЕНЬ)}")
        if расхождения:
            print("схемы v2: сгенерированное разошлось с истиной схем —")
            for x in расхождения[:20]:
                print("  ", x)
            print("   перегенерируйте: python3 tools/v2/gen_schemas.py")
            return 1
        print(f"схемы v2: {len(файлы)} файлов и Kotlin-перечень совпадают с истиной YAML")
        return 0

    СХЕМЫ.mkdir(parents=True, exist_ok=True)
    for устаревший in СХЕМЫ.glob("*.json"):
        if устаревший.name not in файлы:
            устаревший.unlink()
    for имя, схема in файлы.items():
        (СХЕМЫ / имя).write_text(текст(схема), encoding="utf-8")
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN.write_text(код, encoding="utf-8")
    print(f"схемы v2: записано {len(файлы)} файлов в schemas/v2 и перечень {len(kinds)} видов в Kotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
