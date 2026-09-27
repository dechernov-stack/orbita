#!/usr/bin/env python3
"""Сторож меток перечней (`enum_labels` истины схем; field_rules.enum_labels —
«значение без метки на экране — сторож»).

1. Ключ метки — путь перечня истины («вид.поле», вложенный — «вид.поле.подполе»,
   как его отдаёт генератор схем). Два ключа не перечни — и названы здесь
   поимённо с причиной (см. КЛЮЧИ_НЕ_ПЕРЕЧНИ).
2. Метка — значение своего перечня: слово у несуществующего значения — отказ.
3. Храповик слов: значение-код (с латиницей) без метки — отказ, кроме полей,
   названных в ЖДУТ_СЛОВ (слова — диффом владельца; поле, получившее слова,
   из перечня обязано уйти — храповик затягивается).

    python3 tools/validate_enum_labels.py            # сторож
    python3 tools/validate_enum_labels.py --table    # таблица «перечни без слов» (ПЕРЕЧНИ-БЕЗ-СЛОВ.md)

Самопроверка: каждое правило ловит подделку.
"""
from __future__ import annotations

import pathlib
import re
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent
ИСТИНА = КОРЕНЬ / "docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml"

# Ключи `enum_labels`, которые НЕ перечни истины, — и почему они законны.
КЛЮЧИ_НЕ_ПЕРЕЧНИ = {
    # Поле — ссылка в справочник методов (method_catalog, каталог priority), а не
    # перечень; метки называют коды каталога, и реестр требований показывает их словом.
    "requirement.priority": "ссылка в справочник method_catalog (каталог priority): метки — слова кодов каталога",
    # Статус записи — поле ядра по модели состояний вида (status_model), а не поле
    # вида; метки называют состояния, и реестр показывает «черновик», а не Draft.
    "requirement.status": "статус записи по модели состояний вида (поле ядра status): метки — слова состояний",
}

# Вложенные перечни, у которых слов ещё нет (26.09): слова — диффом владельца по
# ПЕРЕЧНИ-БЕЗ-СЛОВ.md. Поле, получившее слова, из перечня уходит.
ЖДУТ_СЛОВ = {
    "glossary_term.links.type", "normative_document.clauses.applies_to", "phase_template.scenes.track",
    "phase_template.scenes.instantiate_per", "phase_template.scenes.links.type",
    "phase_template.stage_output_dataset.baseline_kind", "pbs_template.nodes.kind", "interface_template.interfaces.type",
    "architecture_template.functions.layer", "model_template.models.tool_status", "questionnaire.fields.paths",
    "site_catalog.sites.type", "gate.expertise.control_items.maturity", "assignment.target.kind",
    "proposal.items.decision", "requirement.source.kind", "component.maturity_tailoring.action",
    "constellation_variant.subgroups.pattern", "release.print_files.format", "baseline.items.firmness",
    "verification_report.items.issue",
}


def перечни(истина: dict) -> dict[str, list[str]]:
    """Пути перечней истины и их значения — тем же разбором, что генератор схем."""
    sys.path.insert(0, str(КОРЕНЬ / "tools/v2"))
    from gen_schemas import перечни_поля, развернуть_группы  # noqa: PLC0415

    найдено: dict[str, list[str]] = {}
    for вид in развернуть_группы(истина["kinds"]):
        for поле in вид.get("fields") or []:
            for путь, значения in перечни_поля(поле["name"], поле.get("type") or ""):
                # Значение-след разбора («document_template*,maturity*» у союза) перечнем не считается.
                if all(re.fullmatch(r"[^\s,*\[\]{}]+", з) for з in значения):
                    найдено[f"{вид['code']}.{путь}"] = значения
    return найдено


def без_слов(перечни_: dict[str, list[str]], метки: dict[str, dict]) -> dict[str, list[str]]:
    """Значения-коды (с латиницей) без метки — по полям."""
    итог = {}
    for путь, значения in перечни_.items():
        нет = [з for з in значения if з not in (метки.get(путь) or {}) and re.search(r"[A-Za-z]", з)]
        if нет:
            итог[путь] = нет
    return итог


def беды(перечни_: dict[str, list[str]], метки: dict[str, dict], ждут: set[str]) -> list[str]:
    найдено = []
    for ключ, пары in метки.items():
        if ключ not in перечни_:
            if ключ not in КЛЮЧИ_НЕ_ПЕРЕЧНИ:
                найдено.append(f"«{ключ}» — не перечень истины: метке не к чему относиться (или назовите причину в КЛЮЧИ_НЕ_ПЕРЕЧНИ)")
            continue
        лишние = [к for к in (пары or {}) if к not in перечни_[ключ]]
        if лишние:
            найдено.append(f"«{ключ}»: метки у значений вне перечня — {', '.join(лишние)}")
    for путь, нет in без_слов(перечни_, метки).items():
        if путь not in ждут:
            найдено.append(f"«{путь}»: значения без слов — {', '.join(нет)} (слова — в enum_labels истины)")
    for путь in sorted(ждут):
        if путь not in без_слов(перечни_, метки):
            найдено.append(f"«{путь}» получил слова — снимите его из ЖДУТ_СЛОВ (храповик затягивается)")
    return найдено


def таблица(перечни_: dict[str, list[str]], метки: dict[str, dict]) -> tuple[str, int]:
    строки = [f"| `{п}` | {' · '.join('`' + з + '`' for з in нет)} | |" for п, нет in sorted(без_слов(перечни_, метки).items())]
    return "\n".join(["| вид.поле | значения без слов | слова |", "|---|---|---|", *строки]) + "\n", len(строки)


def самопроверка() -> None:
    перечни_ = {"a.x": ["one", "two"], "a.y": ["И", "В"], "b.z": ["on", "off"]}
    assert any("не перечень" in б for б in беды(перечни_, {"a.q": {"on": "да"}}, {"b.z", "a.x"})), "ключ-не-перечень не пойман"
    assert any("вне перечня" in б for б in беды(перечни_, {"a.x": {"one": "раз", "three": "три"}}, {"b.z"})), "лишняя метка не поймана"
    assert any("без слов" in б for б in беды(перечни_, {}, {"a.x"})), "код без слов не пойман"
    assert any("снимите его из ЖДУТ_СЛОВ" in б for б in беды(перечни_, {"a.x": {"one": "раз", "two": "два"}}, {"a.x", "b.z"})), "храповик не затянут"
    assert not беды(перечни_, {"a.x": {"one": "раз", "two": "два"}, "requirement.status": {"Draft": "черновик"}}, {"b.z"}), "чистое поймано зря"


def main() -> int:
    import yaml  # noqa: PLC0415

    самопроверка()
    истина = yaml.safe_load(ИСТИНА.read_text(encoding="utf-8"))
    метки = истина.get("enum_labels") or {}
    перечни_ = перечни(истина)
    if "--table" in sys.argv:
        текст, _ = таблица(перечни_, метки)
        print(текст, end="")
        return 0
    найдено = беды(перечни_, метки, ЖДУТ_СЛОВ)
    if найдено:
        print("метки перечней:")
        for б in найдено:
            print("  ", б)
        return 1
    print(f"метки перечней: {len(метки)} ключей — пути перечней истины, метки — значения своих перечней; "
          f"без слов ждут дифа {len(ЖДУТ_СЛОВ)} вложенных перечней")
    return 0


if __name__ == "__main__":
    sys.exit(main())
