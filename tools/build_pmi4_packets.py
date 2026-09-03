#!/usr/bin/env python3
"""Раскладка пакетов ПМИ-4 из поставки внешнего контура (реестр 3.zip).

Поставка описывает сущности СВОИМИ словами: «категория: проектное»,
«приоритет: высокий», «measure: {unit, op, value}». Система принимает пакет
по схеме вида (`prompt-package-kinds` → `target_schema`), и разложить одно в
другое — работа Code, а не разборщика: правило 1 реестра поставки.

Правила раскладки соблюдаются здесь и проверяются вставкой (Pmi4PacketsTest):
  1. `kind` сопоставляется с реестром видов ИС; свой вид не выдумывается.
  2. Поля вне схемы уходят в `note`/`rationale`, а не теряются молча.
  3. Ссылки `@КОД` остаются как есть — их разрешает канал при взятии.
  4. Ссылки между пакетами — по `code` поставки; он же уходит в `tags`
     («код поставки»), чтобы после перекодировки при акцепте связь читалась.
  5. Числа без единицы и величины без происхождения НЕ достраиваются: ловушки
     поставки обязаны сработать, а не быть починенными раскладкой.

Запуск: python3 tools/build_pmi4_packets.py [--check]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs/tz/manual-run-4/поставка-пми4"
OUT = ROOT / "docs/tz/manual-run-4/пакеты"

DRAFT = {"status": "Draft", "version": "1"}

# ── словари раскладки ───────────────────────────────────────────────────────
КАТЕГОРИЯ = {
    "функциональное": "functional",
    "характеристика": "performance",
    "проектное": "performance",
    "интерфейсное": "interface",
    "сценарное": "operational",
    "операционное": "operational",
    "надёжность": "reliability",
    "безопасность": "safety",
    "среда": "environmental",
    "ограничение": "constraint",
    "регуляторное": "constraint",
    "системное": "performance",
}
ПРИОРИТЕТ = {"высокий": "high", "средний": "medium", "низкий": "low"}
УРОВЕНЬ = {"проект": "project", "система": "system", "системные": "system",
           "сценарные": "system", "элемент": "element"}
МЕТОД = {
    "анализ": "analysis", "испытание": "test", "демонстрация": "demonstration",
    "инспекция": "inspection", "расчёт": "analysis", "моделирование": "analysis",
}
РОЛЬ_НУЖДЫ = {
    "заказчик": "customer", "оператор": "operator", "потребитель": "end_user",
    "регулятор": "regulator", "ведомство": "agency", "партнёр": "partner",
    "поставщик": "partner", "учреждаемый": "agency",
}
ОПЕРАТОР = {"<=": "le", ">=": "ge", "<": "lt", ">": "gt", "=": "eq", "==": "eq"}
КАТЕГОРИЯ_РИСКА = {
    "технический": "technical", "программный": "technical", "регуляторный": "technical",
    "стоимостной": "cost", "календарный": "schedule", "безопасность": "safety",
}
СТРАТЕГИЯ = {"снижение": "mitigate", "принятие": "accept", "передача": "transfer",
             "уклонение": "avoid", "избежание": "avoid"}
КЛАСС_QOS = {"A'": "A_prime", "A′": "A_prime", "B'": "B_prime", "B′": "B_prime",
             "C'": "C_prime", "C′": "C_prime"}
# MOE сервиса — закрытый перечень схемы: показатель выбирается по смыслу
# измерителя поставки, а новый не выдумывается
MOE_ПО_ЕДИНИЦЕ = {
    "мин": "reaction_time_probability",
    "ч": "age_of_information",
    "сут": "age_of_information",
    "с": "reaction_time_probability",
    "1": "delivery_probability_daily",
    "%": "delivery_probability_daily",
}


def читать(имя: str) -> dict:
    return json.loads((SRC / имя).read_text(encoding="utf-8"))


def якоря(эл: dict) -> list[str]:
    """Якорь блока: «s2#2.1», «s5#7» — секция плюс подраздел или строка."""
    out = []
    for a in эл.get("anchors", []) or ([эл["source"]] if эл.get("source") else []):
        код = a.get("anchor", "")
        хвост = a.get("sub") or a.get("row")
        out.append(f"{код}#{хвост}" if код and хвост else код)
    return [x for x in out if x]


def происхождение(эл: dict, вид_метки: bool = True) -> dict:
    """Происхождение предложения службы: пакет, якоря и метка достоверности."""
    prov = {"source": "ai_proposed", "ai": {"prompt_package_id": "ПМИ-4", "accepted": False}}
    заметки = []
    if вид_метки and эл.get("source_mark"):
        заметки.append("метка источника: " + ", ".join(эл["source_mark"]))
    if якоря(эл):
        заметки.append("якоря: " + ", ".join(якоря(эл)))
    return prov, "; ".join(заметки)


def величина(m: dict | None, источник: dict | None = None) -> dict | None:
    """Величина с происхождением. Происхождение ставится ТОЛЬКО когда источник
    назван поставкой: число без источника обязано отсеяться каналом, а не быть
    починенным раскладкой (ловушка RQ-P-13)."""
    if not m or m.get("value") is None:
        return None
    q = {"value": m.get("value"), "unit": m.get("unit") or "1"}
    if источник:
        # число ВЗЯТО ИЗ ДОКУМЕНТА: основание — импорт с названным набором
        # данных (правило основания: ai_proposed основанием не считается)
        якорь = ""
        if isinstance(источник, dict):
            якорь = источник.get("anchor", "")
        elif isinstance(источник, list) and источник:
            якорь = (источник[0] or {}).get("anchor", "")
        q["provenance"] = {
            "source": "imported",
            "import": {
                "dataset": "Записка миссии — Национальная спутниковая IoT-платформа"
                           + (f", блок {якорь}" if якорь else ""),
                "dataset_version": "поставка ПМИ-4 от 03.09.2026",
                "retrieved_at": "2026-09-03",
                "terms": "материал владельца проекта",
            },
        }
    return q


def слова(текст: str) -> set[str]:
    """Значимые слова текста с грубой нормализацией окончаний — для поиска пары."""
    из = []
    for ч in текст.lower().replace("«", " ").replace("»", " ").split():
        ч = ч.strip(".,;:()[]—–-!?\"'")
        if len(ч) > 4:
            из.append(ч[: max(5, len(ч) - 3)])
    return set(из)


def похоже(текст: str, кандидаты: list[tuple[str, str]], порог: int = 2) -> str | None:
    """Лучший кандидат по пересечению значимых слов; ниже порога — пары нет.
    Это ПРЕДЛОЖЕНИЕ пакета: инженер видит его и принимает или правит."""
    искомое = слова(текст)
    лучший, лучшее = None, 0
    for ref, текст_кандидата in кандидаты:
        общих = len(искомое & слова(текст_кандидата))
        if общих > лучшее:
            лучший, лучшее = ref, общих
    return лучший if лучшее >= порог else None


# ── Р01 · замысел ───────────────────────────────────────────────────────────
def poле_якоря(поле: dict) -> list[str]:
    """Якоря поля замысла — строками «s2#2.1», как их ждёт схема черновика."""
    out = []
    for a in поле.get("sources", []) or поле.get("anchors", []):
        if isinstance(a, str):
            out.append(a)
            continue
        код = a.get("anchor", "")
        хвост = a.get("sub") or a.get("row")
        out.append(f"{код}#{хвост}" if код and хвост else код)
    return [x for x in out if x]


def р01(src: dict) -> dict:
    """Замысел идёт схемой черновика; поля вне схемы уходят в note."""
    note_marks: list[str] = []
    note = src.get("note", "")
    if src.get("anchors_mode"):
        note = (note + " · " if note else "") + f"якоря: {src['anchors_mode']}"
    поля = {}
    for имя, поле in src["intent"].items():
        чистое = {"text": поле["text"]}
        якорьки = poле_якоря(поле)
        if якорьки:
            чистое["anchors"] = якорьки
        # метка достоверности в схеме черновика места не имеет — она уходит
        # общей строкой примечания, а не теряется
        if поле.get("source_mark"):
            note_marks.append(f"{имя}: метка {', '.join(поле['source_mark'])}")
        поля[имя] = чистое
    out = {"kind": src["kind"], "intent": поля}
    if src.get("source_document"):
        out["source_document"] = src["source_document"]
    if note_marks:
        note = (note + " · " if note else "") + "; ".join(note_marks)
    if note:
        out["note"] = note
    return out


# ── Р02 · цели и нужды ──────────────────────────────────────────────────────
def р02_цели(src: dict) -> dict:
    items = []
    for n, g in enumerate(src["goals"], start=1):
        prov, заметка = происхождение(g)
        item = {
            "id": f"MG-{9000 + n:04d}",
            "kind": "goal",
            "statement": g["statement"],
            "lifecycle": dict(DRAFT),
            "provenance": prov,
        }
        цель_величина = величина(g.get("measure"), g.get("anchors"))
        if цель_величина:
            item["moe"] = [{
                "id": f"MOE-{9000 + n:04d}",
                "name": (g.get("measure_text") or g["statement"])[:60],
                "target": цель_величина,
            }]
        куски = [x for x in (g.get("measure_text"), заметка,
                             f"горизонт: {g['horizon']}" if g.get("horizon") else None) if x]
        if куски:
            item["program_link"] = " · ".join(куски)
        items.append(item)
    return {"kind": "mission_to_goals", "items": items}


def р02_нужды(src: dict) -> dict:
    items = []
    for n, need in enumerate(src["needs"], start=1):
        prov, заметка = происхождение(need)
        роль = РОЛЬ_НУЖДЫ.get((need.get("role") or "").lower(), "end_user")
        item = {
            "id": f"ND-{9000 + n:04d}",
            "statement": need["statement"],
            "stakeholder": {"name": need.get("stakeholder", "не назван"), "role": роль},
            "lifecycle": dict(DRAFT),
            "provenance": prov,
        }
        ограничения = [x for x in (
            f"класс потребителя: {need['qos_class']}" if need.get("qos_class") else None,
            заметка or None,
        ) if x]
        if ограничения:
            item["constraints"] = ограничения
        items.append(item)
    return {"kind": "mission_to_needs", "items": items}


# ── Р03 · сервисы ───────────────────────────────────────────────────────────
def р03(src: dict) -> dict:
    """Сервис следует из нужды. Ссылка поставки — либо ЧУЖАЯ (проект PJ-0001 из
    среза: она нарочно не имеет пары и уходит в диалог сопоставления Г-01),
    либо названа формулировкой — тогда пара ищется среди нужд Р02 по тексту."""
    нужды = читать("Р02-цели-нужды.json")["needs"]
    по_формулировке = {n["statement"]: f"ND-{9000 + i:04d}" for i, n in enumerate(нужды, start=1)}
    items = []
    for n, s in enumerate(src["items"], start=1):
        prov, заметка = происхождение(s)
        мера = s.get("measure") or {}
        имя_moe = MOE_ПО_ЕДИНИЦЕ.get(мера.get("unit", ""), "service_availability")
        профиль = {
            "consumer_class": КЛАСС_QOS.get(s.get("qos_class", ""), "A_prime"),
            "moe": [{
                "id": f"MOE-{9100 + n:04d}",
                "name": имя_moe,
                "target": величина(мера, s.get("anchors")) or {
                    "value": 0, "unit": "1",
                    "provenance": {"source": "ai_proposed",
                                   "ai": {"prompt_package_id": "ПМИ-4", "accepted": False}},
                },
            }],
        }
        описание = " · ".join(x for x in (s.get("target_text"), заметка) if x)
        ссылка = s.get("need_ref") or {}
        # чужой идентификатор берётся как есть: пары ему в проекте нет, и
        # диалог сопоставления обязан это показать, а не мы — подставить
        нужда = ссылка.get("id") or по_формулировке.get(ссылка.get("statement", ""))
        item = {
            "id": f"SV-{9000 + n:04d}",
            "name": s["name"],
            "traces_up": [нужда] if нужда else [],
            "qos_profiles": [профиль],
            "lifecycle": dict(DRAFT),
            "provenance": prov,
        }
        if описание:
            item["description"] = описание
        items.append(item)
    return {"kind": "needs_to_services", "items": items}


# ── Р04 · Р08 · Р10 — требования ────────────────────────────────────────────
def источники_трассировки() -> list[tuple[str, str]]:
    """Кандидаты трассировки: цели и нужды Р02, сервисы Р03 — черновыми id той
    же раскладки. Пара ищется по тексту: пакет ПРЕДЛАГАЕТ связь, инженер
    подтверждает её при акцепте."""
    цн = читать("Р02-цели-нужды.json")
    серв = читать("Р03-сервисы.json")
    из = [(f"MG-{9000 + i:04d}", g["statement"] + " " + (g.get("measure_text") or ""))
          for i, g in enumerate(цн["goals"], start=1)]
    из += [(f"ND-{9000 + i:04d}", nd["statement"]) for i, nd in enumerate(цн["needs"], start=1)]
    из += [(f"SV-{9000 + i:04d}", sv["name"] + " " + (sv.get("target_text") or ""))
           for i, sv in enumerate(серв["items"], start=1)]
    return из


def требование(r: dict, n: int, уровень: str, начало: int) -> dict:
    prov, заметка = происхождение(r)
    # «проектное» и «системное» — не категории нашей модели, а уровни: с
    # измеримым показателем это характеристика, без него — функциональное
    сырая = r.get("category", "")
    категория = КАТЕГОРИЯ.get(сырая, "functional")
    if сырая in ("проектное", "системное") and (r.get("mop") or {}).get("value") is None:
        категория = "functional"
    item = {
        "id": f"RQ-{начало + n:04d}",
        "level": уровень,
        "statement": r["statement"],
        "category": категория,
        "traces_up": [],
        "verification_events": [],
        "owner": "вед. СИ",
        "lifecycle": dict(DRAFT),
        "provenance": prov,
    }
    if r.get("title"):
        item["title"] = r["title"]
    if r.get("priority"):
        item["priority"] = ПРИОРИТЕТ.get(r["priority"], "medium")
    if r.get("acceptance_criteria"):
        item["acceptance_criteria"] = r["acceptance_criteria"]
    # мера: имя показателя — из заголовка, оператор словарём, величина с
    # происхождением ТОЛЬКО при названном источнике. Мера без числа не строится:
    # качественный показатель — не MOP, и выдумывать ему значение незачем
    m = r.get("mop") or {}
    значение = величина(m, r.get("source"))
    if значение and m.get("op"):
        item["mop"] = {
            "name": (r.get("title") or r["statement"])[:60],
            "operator": ОПЕРАТОР.get(m["op"], "le"),
            "value": значение,
        }
    elif m.get("value") is not None and m.get("op"):
        # величина без источника: ловушка поставки — идёт как есть и отсеивается
        item["mop"] = {
            "name": (r.get("title") or r["statement"])[:60],
            "operator": ОПЕРАТОР.get(m["op"], "le"),
            "value": {"value": m["value"], "unit": m.get("unit") or "1"},
        }
    if r.get("verification_method"):
        метод = МЕТОД.get(r["verification_method"], "analysis")
        событие = {
            "id": f"VE-{начало + n:04d}",
            "method": метод,
            "kind": "preliminary",
            "level": "system",
            "status": "planned",
            "closes": False,
        }
        # порядок проверки — из критерия приёмки поставки; средство названо
        # там же, иначе событие неполно и уйдёт в доработку (и правильно)
        if r.get("acceptance_criteria"):
            событие["approach"] = r["acceptance_criteria"]
        if метод in ("test", "analysis"):
            событие["means"] = {
                "test": "стенд пилота: выборка сообщений и журнал доставки",
                "analysis": "расчётная модель системы (М1–М3) и журнал прогона",
            }[метод]
        item["verification_events"] = [событие]
    основания = [x for x in (r.get("rationale"), заметка) if x]
    if основания:
        item["rationale"] = " · ".join(основания)
    метки = list(r.get("tags", []))
    if r.get("code"):
        метки.append(f"код поставки: {r['code']}")
    if метки:
        item["tags"] = метки
    # ссылки поставки: derives_from по коду, realized_by/allocated_to кодами «@»
    for rel in r.get("relations", []) or []:
        вид, цель = rel.get("kind"), rel.get("target")
        if not цель:
            continue
        if вид == "realized_by":
            item.setdefault("realized_by", []).append(цель)
        elif вид == "satisfied_by":
            item.setdefault("satisfied_by", []).append(цель)
        elif вид == "derives_from":
            item.setdefault("derives_from", []).append(цель)
    цели = r.get("allocated_to")
    if цели:
        # поставка называет носителя строкой либо списком строк; стык отличается
        # префиксом кода полки интерфейсов
        if isinstance(цели, str):
            цели = [цели]
        # цепочка носителем не бывает: сценарий покрывается связью realized_by,
        # а не распределением на состав (ADR-050)
        носители = [a for a in цели if not str(a).startswith("@FC-")]
        цепочки = [a for a in цели if str(a).startswith("@FC-")]
        if носители:
            item["allocated_to"] = [
                {"interface": a} if str(a).startswith("@IF-") else {"component": a}
                for a in носители
            ]
        for c in цепочки:
            item.setdefault("realized_by", []).append(c)
    if r.get("normative_basis"):
        # норматив поставки назван реквизитами, а не идентификатором NR-NNNN:
        # он появится при акцепте урожая, поэтому реквизиты идут строкой
        nb = r["normative_basis"]
        if isinstance(nb, dict) and nb.get("ref"):
            item["normative_basis"] = nb
        else:
            текст = nb if isinstance(nb, str) else " · ".join(str(v) for v in nb.values())
            item["rationale"] = (item.get("rationale", "") + " · " if item.get("rationale") else "") + \
                f"нормативное основание: {текст}"
    пара = похоже(f"{r.get('title', '')} {r['statement']} {r.get('rationale', '')}", ИСТОЧНИКИ)
    if not пара:
        # вторая ступень — по СЕКЦИИ записки: цели живут в §5, нужды в §2,
        # сервисы в §4; требование из того же блока следует из них
        секция = (r.get("source") or {}).get("anchor", "")
        пара = {"s5": "MG-9001", "s2": "ND-9001", "s4": "SV-9001"}.get(секция)
    if пара:
        item["traces_up"] = [{"ref": пара}]
    return item


def р04(src: dict) -> dict:
    уровень = УРОВЕНЬ.get(src.get("level", ""), "project")
    items = [требование(r, n, уровень, 9000) for n, r in enumerate(src["items"], start=1)]
    return {"kind": "services_to_requirements", "items": items}


def р08(src: dict) -> dict:
    уровень = УРОВЕНЬ.get(src.get("level", ""), "system")
    # системное требование выведено из ПРОЕКТНОГО: пара берётся по коду
    # поставки, а не по тексту — связь названа явно
    родители = {r["code"]: f"RQ-{9000 + i:04d}"
                for i, r in enumerate(читать("Р04-требования.json")["items"], start=1)}
    items = []
    for n, r in enumerate(src["items"], start=1):
        item = требование(r, n, уровень, 9100)
        for rel in r.get("relations", []) or []:
            if rel.get("kind") == "derives_from" and rel.get("target") in родители:
                item["traces_up"] = [{"ref": родители[rel["target"]]}]
                item["derives_from"] = [родители[rel["target"]]]
        # связь с родителем поставки — кодом: перекодировка при акцепте
        # сохранит его в tags, и связь можно восстановить
        for rel in r.get("relations", []) or []:
            if rel.get("kind") == "derives_from" and rel.get("target"):
                item.setdefault("tags", []).append(f"выведено из: {rel['target']}")
        items.append(item)
    return {"kind": "requirement_decomposition", "items": items}


def р10(src: dict) -> dict:
    items = [требование(r, n, "system", 9200) for n, r in enumerate(src["items"], start=1)]
    return {"kind": "services_to_requirements", "items": items}


# ── Р05 · урожай записки ────────────────────────────────────────────────────
ПОЛЯ_УРОЖАЯ = {
    "class", "block", "name", "statement", "role", "establishes", "need_ref",
    "international", "priority", "measure", "fleet", "range", "span", "span_from",
    "horizon", "horizon_note", "canonical", "scores", "scale", "schema_note",
    "note", "anchor", "form_field", "source_mark",
}
РАСШИРЕННЫЕ = {"risk", "open_question", "finding"}


def р05(src: dict) -> dict:
    items = []
    for эл in src["items"]:
        item = {"class": эл["class"]}
        хвост = []
        for k, v in эл.items():
            if k in ("class", "anchors", "source_mark") or v is None:
                continue
            if k == "priority" and not isinstance(v, bool):
                # в схеме priority географии — признак «приоритетная», а не ранг:
                # число поставки уходит строкой, признак ставится по нему
                item["priority"] = True
                хвост.append(f"приоритет покрытия: {v}")
            elif k in ПОЛЯ_УРОЖАЯ:
                # диапазон с одной границей («до 50», «от 500») схемой не
                # принимается: неназванная граница уходит строкой, а не null
                if isinstance(v, dict) and ("min" in v or "max" in v) and (v.get("min") is None or v.get("max") is None):
                    край = "не более" if v.get("min") is None else "не менее"
                    число = v.get("max") if v.get("min") is None else v.get("min")
                    item[k] = {"value": число, "unit": v.get("unit", "1")} if "value" not in v else v
                    хвост.append(f"{k}: {край} {число} {v.get('unit', '')}".strip())
                else:
                    item[k] = v
            else:
                # поле вне схемы не теряется: уходит в note человеческой строкой
                хвост.append(f"{k}: {v}" if not isinstance(v, (dict, list))
                             else f"{k}: {json.dumps(v, ensure_ascii=False)}")
        if якоря(эл):
            item["block"] = якоря(эл)
        if эл.get("source_mark"):
            # метка схемы — одна буква; полный код поставки («И1») уходит в note
            метки = эл["source_mark"]
            item["source_mark"] = метки[0][0]
            if any(len(m) > 1 for m in метки):
                хвост.append("метки поставки: " + ", ".join(метки))
        if эл["class"] in РАСШИРЕННЫЕ and "schema_note" not in item:
            item["schema_note"] = {
                "risk": "риск: условие—событие—последствие из записки; в реестр рисков идёт пакетом Р09",
                "open_question": "открытый вопрос записки: решение владельца, объекта модели пока нет",
                "finding": "наблюдение разбора: факт без объекта модели, основание для решений",
            }[эл["class"]]
        if хвост:
            item["note"] = (item.get("note", "") + " · " if item.get("note") else "") + "; ".join(хвост)
        items.append(item)
    out = {"kind": "document_semantic_parse", "items": items}
    if src.get("source_document"):
        out["source_document"] = src["source_document"]
    return out


# ── Р06 · Р07 — проза раздела ───────────────────────────────────────────────
# поставка называет шаблон идентификатором объекта полки, система — кодом
ШАБЛОН = {"DT-9002": "semp", "SEMP": "semp", "ConOps": "conops", "DT-0002": "conops"}

def проза(src: dict, номер_id: int) -> dict:
    item = {
        "id": f"ST-{номер_id:04d}",
        "template_code": ШАБЛОН.get(src["template"], src["template"]),
        "section": int(src["section"]),
        "text": src["text"],
        "lifecycle": dict(DRAFT),
        "provenance": {"source": "ai_proposed",
                       "ai": {"prompt_package_id": "ПМИ-4", "accepted": False}},
    }
    строки = [x for x in (src.get("section_title"), src.get("note")) if x]
    if src.get("data_refs"):
        строки.append("данные вставок: " + ", ".join(src["data_refs"]))
    if строки:
        item["inserts_lines"] = строки
    return {"kind": "section_prose", "items": [item]}


# ── Р09 · риски ─────────────────────────────────────────────────────────────
def разложить_риск(r: dict) -> str:
    """«если A, то B, последствие — C» → «A — B — C». Связки не найдены —
    фраза остаётся как есть, и правило честно отправит риск в доработку."""
    фраза = (r.get("condition_event_consequence") or "").strip()
    if not фраза:
        return r["statement"]
    условие, _, хвост = фраза.partition(", то ")
    if not хвост:
        return f"{r['statement']} — {фраза}"
    событие, разделитель, последствие = хвост.partition(", последствие — ")
    if not разделитель:
        событие, разделитель, последствие = хвост.partition(", последствие ")
    условие = условие.removeprefix("если ").strip()
    части = [условие, событие.strip(), последствие.strip()] if разделитель else [условие, событие.strip()]
    return " — ".join(x for x in части if x)


def р09(src: dict) -> dict:
    items = []
    for n, r in enumerate(src["items"], start=1):
        prov, заметка = происхождение(r)
        # формулировка риска обязана нести условие, событие и последствие ТРЕМЯ
        # частями через тире — поставка пишет их одной фразой «если …, то …,
        # последствие — …», и раскладка разбирает её по этим же связкам
        формулировка = разложить_риск(r)
        item = {
            "id": f"RSK-{9000 + n:04d}",
            "statement": формулировка,
            "category": КАТЕГОРИЯ_РИСКА.get(r.get("category", ""), "technical"),
            "probability": int(r["probability"]),
            "impact": int(r["impact"]),
            "owner": r.get("owner_role", "вед. СИ"),
            "status": "open",
            "provenance": prov,
        }
        if r.get("strategy"):
            item["strategy"] = СТРАТЕГИЯ.get(r["strategy"], "mitigate")
        # категория поставки богаче нашего перечня: «регуляторный» и
        # «программный» ложатся на technical, а слово поставки уходит в
        # мероприятия — не теряется и читается человеком
        действия = [x for x in (
            r.get("measures"),
            f"категория поставки: {r.get('category')}" if r.get("category") else None,
            заметка or None,
        ) if x]
        if действия:
            item["actions"] = действия
        items.append(item)
    return {"kind": "risk_register", "items": items}


ИСТОЧНИКИ = источники_трассировки()

СБОРКА = [
    ("Р01-замысел.json", "Р01-замысел.json", р01),
    ("Р02-цели-нужды.json", "Р02-цели.json", р02_цели),
    ("Р02-цели-нужды.json", "Р02-нужды.json", р02_нужды),
    ("Р03-сервисы.json", "Р03-сервисы.json", р03),
    ("Р04-требования.json", "Р04-требования.json", р04),
    ("Р05-урожай-записки.json", "Р05-урожай-записки.json", р05),
    ("Р06-проза-SEMP-3.json", "Р06-проза-SEMP-3.json", lambda s: проза(s, 9006)),
    ("Р07-проза-ConOps-1.json", "Р07-проза-ConOps-1.json", lambda s: проза(s, 9007)),
    ("Р08-системные.json", "Р08-системные.json", р08),
    ("Р09-риски.json", "Р09-риски.json", р09),
    ("Р10-сценарные-цепочки.json", "Р10-сценарные-цепочки.json", р10),
]


def main() -> int:
    проверка = "--check" in sys.argv
    расхождений = 0
    for исходник, цель, разложить in СБОРКА:
        текст = json.dumps(разложить(читать(исходник)), ensure_ascii=False, indent=2) + "\n"
        файл = OUT / цель
        if проверка:
            было = файл.read_text(encoding="utf-8") if файл.exists() else ""
            if было != текст:
                print(f"РАЗОШЛОСЬ: {цель} не совпадает с раскладкой из {исходник}")
                расхождений += 1
            else:
                print(f"ok: {цель}")
        else:
            файл.write_text(текст, encoding="utf-8")
            собрано = json.loads(текст)
            n = len(собрано.get("items", [])) if "items" in собрано else 1
            print(f"{цель}: {собрано['kind']} · элементов {n}")
    return 1 if расхождений else 0


if __name__ == "__main__":
    raise SystemExit(main())
