#!/usr/bin/env python3
"""Генератор онтологии формирования: ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml → Kotlin.

Вторая истина поля знаний, рядом со схемами видов. Схемы говорят, КАКИЕ поля
есть у сущности; онтология — КАК понятие образуется из фактов, чем оно узнаётся
среди уже принятых (`identity`), что считается противоречием (`conflict_on`) и
без какой связи его нельзя принять (`must_link`).

Одни и те же правила нужны детерминированной сверке (:core:v2:knowledge) и
синтезу (:core:v2:ai). Второй копии правил быть не должно — она разойдётся с
истиной за неделю и разойдётся молча. Поэтому Kotlin получается генерацией, а
сторож (`--check`) валит сборку, когда сгенерированное разошлось с YAML.

  python3 tools/v2/gen_ontology.py          # перегенерировать
  python3 tools/v2/gen_ontology.py --check  # сторож: расхождение — отказ

Куда пишет:
  core/v2/knowledge/src/main/kotlin/orbita/knowledge/schema/GeneratedOntology.kt

Отпечаток: `ontologyVersion` = sha256 самого файла истины. Им помечается каждый
запуск синтеза (`synthesis_run.ontology_version`): по нему видно, по каким
правилам сделано предложение, и видно, что правила с тех пор изменились.
"""
import argparse
import hashlib
import pathlib
import sys

import yaml

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ИСТОЧНИК = КОРЕНЬ / "docs/tz/v2/ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml"
KOTLIN = КОРЕНЬ / "core/v2/knowledge/src/main/kotlin/orbita/knowledge/schema/GeneratedOntology.kt"

ШАПКА = (
    "СГЕНЕРИРОВАНО tools/v2/gen_ontology.py из docs/tz/v2/ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml"
    " — руками не править"
)

# Ранги доверия. Перечень закрыт: ранг вне его не существует ни у материала,
# ни у факта (СХЕМЫ-ПОЛЕЙ-V2.yaml, material.authority и fact.authority).
РАНГИ = ("mandatory", "expert", "reference", "doubtful")

# Ключи правила отбора фактов и их имена в Kotlin. Перечень закрытый: ключ,
# которого генератор не знает, молча пропасть не должен — иначе правило
# образования понятия окажется в YAML и не окажется в коде.
ПОРЯДОК_ОТБОРА = (
    ("kind", "kind"),
    ("predicate_in", "predicateIn"),
    ("subject", "subject"),
    ("subject_is", "subjectIs"),
    ("mark", "mark"),
    ("with", "with"),
    ("section_hint", "sectionHint"),
)
СПИСОЧНЫЕ_ОТБОРА = {"predicate_in"}

ОБЯЗАТЕЛЬНЫЕ_КЛЮЧИ = ("from_facts", "fields", "must_link", "conflict_on", "identity")
ИЗВЕСТНЫЕ_КЛЮЧИ = set(ОБЯЗАТЕЛЬНЫЕ_КЛЮЧИ) | {"note"}

# Разделы истины. Раздел, которого генератор не знает, пропадёт молча —
# а поставка правит этот файл целиком, и потеря раздела не видна глазом.
ИЗВЕСТНЫЕ_РАЗДЕЛЫ = {"ontology", "authority_ranks", "concepts", "reconciliation", "invariants"}


class Отказ(Exception):
    """Истина онтологии не разобрана — генерация не состоялась."""


def строка(значение) -> str:
    """Значение YAML → строковый литерал Kotlin."""
    текст = "" if значение is None else str(значение)
    экранированное = (
        текст.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
    )
    return f'"{экранированное}"'


def список(значения) -> str:
    if not значения:
        return "emptyList()"
    return "listOf(" + ", ".join(строка(x) for x in значения) + ")"


def карта(пары: dict, отступ: str) -> str:
    if not пары:
        return "emptyMap()"
    строки = ["mapOf("]
    for ключ, значение in пары.items():
        строки.append(f"{отступ}    {строка(ключ)} to {строка(значение)},")
    строки.append(f"{отступ})")
    return "\n".join(строки)


def правило_отбора(правило: dict, понятие: str) -> str:
    неизвестные = [к for к in правило if к not in dict(ПОРЯДОК_ОТБОРА)]
    if неизвестные:
        raise Отказ(
            f"понятие «{понятие}»: отбор фактов описан ключом {sorted(неизвестные)}, "
            "которого генератор не знает — допишите его в ПОРЯДОК_ОТБОРА и в FactRule"
        )
    части = []
    for ключ, имя in ПОРЯДОК_ОТБОРА:
        if ключ not in правило:
            continue
        значение = правило[ключ]
        если_список = ключ in СПИСОЧНЫЕ_ОТБОРА
        части.append(f"{имя} = " + (список(значение) if если_список else строка(значение)))
    return "FactRule(" + ", ".join(части) + ")"


def проверить(истина: dict) -> None:
    """Отказ вместо тихого пропуска: разобранное обязано быть полным."""
    неизвестные_разделы = [к for к in истина if к not in ИЗВЕСТНЫЕ_РАЗДЕЛЫ]
    if неизвестные_разделы:
        raise Отказ(
            f"раздел {sorted(неизвестные_разделы)} генератору неизвестен — "
            "в Kotlin он не попадёт; допишите перенос раздела"
        )
    ранги = истина.get("authority_ranks") or {}
    неизвестные = [р for р in ранги if р not in РАНГИ]
    if неизвестные:
        raise Отказ(f"ранг доверия {sorted(неизвестные)} вне перечня {list(РАНГИ)}")
    пропущенные = [р for р in РАНГИ if р not in ранги]
    if пропущенные:
        raise Отказ(f"ранг доверия {пропущенные} не описан в онтологии")

    понятия = истина.get("concepts") or {}
    if not понятия:
        raise Отказ("в онтологии нет ни одного понятия — формировать нечего")
    for код, понятие in понятия.items():
        лишние = [к for к in понятие if к not in ИЗВЕСТНЫЕ_КЛЮЧИ]
        if лишние:
            raise Отказ(f"понятие «{код}» описано ключом {sorted(лишние)} вне перечня")
        нет = [к for к in ОБЯЗАТЕЛЬНЫЕ_КЛЮЧИ if к not in понятие]
        if нет:
            raise Отказ(
                f"понятие «{код}» не объявило {нет}; пустой перечень пишется явно «[]» — "
                "умолчания здесь нет, иначе правило потеряется молча"
            )
        опознание = понятие["identity"] or {}
        for ключ in ("key", "semantic", "threshold"):
            if ключ not in опознание:
                raise Отказ(f"понятие «{код}»: в identity нет «{ключ}» — узнать дубль нечем")
        if not опознание["key"]:
            raise Отказ(f"понятие «{код}»: identity.key пуст — ключ дубля не назван")
        порог = float(опознание["threshold"])
        if not 0 < порог <= 1:
            raise Отказ(f"понятие «{код}»: порог близости {порог} вне (0, 1]")


def котлин(истина: dict, версия: str) -> str:
    строки = [
        "// " + ШАПКА,
        "//",
        "// Правила образования понятий постановки: чем понятие узнаётся среди",
        "// принятых (identity), что считается противоречием (conflict_on) и без какой",
        "// связи его нельзя принять (must_link). Понятие, которого здесь нет, не",
        "// образуется: придумывать понятия в коде запрещено (ЗАДАНИЕ-ЗНАНИЯ-V2).",
        "package orbita.knowledge.schema",
        "",
        "/**",
        " * Чем понятие узнаётся среди уже принятых.",
        " *",
        " * @property key ключ дубля — ступень 1 сверки, без токенов",
        " * @property semantic что сравнивает вторая ступень, когда ключ не совпал",
        " * @property threshold порог близости смысла; ниже порога — не дубль",
        " */",
        "data class ConceptIdentity(",
        "    val key: List<String>,",
        "    val semantic: String,",
        "    val threshold: Double,",
        ")",
        "",
        "/**",
        " * Отбор фактов, из которых образуется понятие.",
        " *",
        " * Пустое поле означает «не ограничиваем», а не «пусто»: правило отбирает",
        " * факты по тем признакам, которые названы.",
        " */",
        "data class FactRule(",
        "    val kind: String? = null,",
        "    val predicateIn: List<String> = emptyList(),",
        "    val subject: String? = null,",
        "    val subjectIs: String? = null,",
        "    val mark: String? = null,",
        "    /** Признак, который факт обязан нести: horizon/year, date, designation. */",
        "    val with: String? = null,",
        "    val sectionHint: String? = null,",
        ")",
        "",
        "/**",
        " * Понятие постановки.",
        " *",
        " * @property fields поле понятия → откуда оно берётся из фактов",
        " * @property mustLink связи, без которых понятие не принимается (нехватка)",
        " * @property conflictOn поля, различие в которых — противоречие, а не дополнение",
        " */",
        "data class Concept(",
        "    val code: String,",
        "    val fromFacts: List<FactRule>,",
        "    val fields: Map<String, String>,",
        "    val mustLink: List<String>,",
        "    val conflictOn: List<String>,",
        "    val identity: ConceptIdentity,",
        "    val note: String? = null,",
        ")",
        "",
        "object GeneratedOntology {",
        "",
        "    /**",
        "     * Отпечаток истины онтологии (sha256 файла). Им помечается каждый запуск",
        "     * синтеза: по нему видно, по каким правилам сделано предложение.",
        "     */",
        f'    const val ontologyVersion: String = "{версия}"',
        "",
        "    /** Ранги доверия по убыванию веса — ранг подсказывает, решает человек. */",
        "    val authorityRanks: List<String> = listOf("
        + ", ".join(f'"{р}"' for р in РАНГИ)
        + ")",
        "",
        "    /** Что значит ранг — словами, для подсказки в противоречии. */",
    ]
    ранги = истина["authority_ranks"]
    строки.append("    val authorityNotes: Map<String, String> = " + карта(
        {р: ранги[р] for р in РАНГИ}, "    "
    ))
    строки += [
        "",
        "    val concepts: List<Concept> = listOf(",
    ]
    for код, понятие in истина["concepts"].items():
        строки.append("        Concept(")
        строки.append(f"            code = {строка(код)},")
        отборы = понятие["from_facts"] or []
        if отборы:
            строки.append("            fromFacts = listOf(")
            for правило in отборы:
                строки.append(f"                {правило_отбора(правило, код)},")
            строки.append("            ),")
        else:
            строки.append("            fromFacts = emptyList(),")
        поля = понятие["fields"] or {}
        строки.append("            fields = " + карта(поля, "            ") + ",")
        строки.append(f"            mustLink = {список(понятие['must_link'])},")
        строки.append(f"            conflictOn = {список(понятие['conflict_on'])},")
        опознание = понятие["identity"]
        строки.append("            identity = ConceptIdentity(")
        строки.append(f"                key = {список(опознание['key'])},")
        строки.append(f"                semantic = {строка(опознание['semantic'])},")
        строки.append(f"                threshold = {float(опознание['threshold'])!r},")
        строки.append("            ),")
        if понятие.get("note"):
            строки.append(f"            note = {строка(понятие['note'])},")
        строки.append("        ),")
    строки += [
        "    )",
        "",
        "    val byCode: Map<String, Concept> = concepts.associateBy { it.code }",
        "",
        "    /** Понятие вне онтологии не образуется: отказ вместо тихого пропуска. */",
        "    fun of(code: String): Concept = byCode[code]",
        "        ?: error(",
        '            "понятие «$code» не описано в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml — " +',
        '                "понятие вне онтологии не образуется"',
        "        )",
        "",
        "    /** Вердикты сверки и правила прав — словами истины, для текста экрана. */",
    ]
    строки.append("    val reconciliation: Map<String, String> = " + карта(
        истина.get("reconciliation") or {}, "    "
    ))
    строки += [
        "",
        "    /** Чего синтез и сверка не вправе нарушить ни при каком ответе модели. */",
        "    val invariants: List<String> = listOf(",
    ]
    for правило in истина.get("invariants") or []:
        строки.append(f"        {строка(правило)},")
    строки += [
        "    )",
        "}",
        "",
    ]
    return "\n".join(строки)


def собрать() -> tuple[str, int]:
    сырое = ИСТОЧНИК.read_bytes()
    истина = yaml.safe_load(сырое.decode("utf-8"))
    проверить(истина)
    # Отпечаток берётся с БАЙТОВ файла: пробел в комментарии истины — тоже
    # правка правил, и код обязан быть перегенерирован вместе с ней.
    return котлин(истина, hashlib.sha256(сырое).hexdigest()), len(истина["concepts"])


def main() -> int:
    разбор = argparse.ArgumentParser(description="генератор онтологии формирования из истины YAML")
    разбор.add_argument("--check", action="store_true", help="сторож: расхождение с YAML — отказ")
    аргументы = разбор.parse_args()

    if not ИСТОЧНИК.exists():
        print(f"онтология формирования: нет истины {ИСТОЧНИК.relative_to(КОРЕНЬ)}")
        return 1
    try:
        код, понятий = собрать()
    except Отказ as отказ:
        print(f"онтология формирования: {отказ}")
        return 1

    if аргументы.check:
        if not KOTLIN.exists():
            print(f"онтология формирования: нет файла {KOTLIN.relative_to(КОРЕНЬ)}")
            print("   перегенерируйте: python3 tools/v2/gen_ontology.py")
            return 1
        if KOTLIN.read_text(encoding="utf-8") != код:
            print("онтология формирования: сгенерированное разошлось с истиной YAML —")
            print(f"   {KOTLIN.relative_to(КОРЕНЬ)}")
            print("   перегенерируйте: python3 tools/v2/gen_ontology.py")
            return 1
        print("онтология формирования: Kotlin совпадает с истиной YAML")
        return 0

    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN.write_text(код, encoding="utf-8")
    print(f"онтология формирования: записано {понятий} понятий в {KOTLIN.relative_to(КОРЕНЬ)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
