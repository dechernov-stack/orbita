#!/usr/bin/env python3
"""Полка «каталог процессов СИ» (process_catalog) — из конфигурации SEMP
первой версии, не из головы: 17 процессов NPR 7123.1 (NASA SEH App. J §5)
→ механизм «Орбиты» → место; процессы реализации несут tailoring —
отклонение названо, не умолчано. Третий столбец — мероприятия Романова
стадии 2 (ROM-…, РОМАНОВ-СТАДИИ-0-2 §3 п. 5), где соответствие очевидно.

Полка хранит каталог ЗАПИСЯМИ — одна на процесс, полями истины (СХЕМЫ-ДИФФ
21-09: «одна запись = один процесс»); до 22.09 весь каталог лежал одной
записью со списками, и печать SEMP разворачивала их мимо истины. Два
расхождения названы владельцу: у шести процессов мероприятий Романова два и
больше при `romanov_activity: str` (пишутся через « · »), а шесть инструментов
среды инженерии поля у вида не имеют — идут подсказкой тезиса SEMP §10.

SEMP Phase A §6 читает эту полку запросом (17 строк — мера шипа G).

  python3 tools/v2/gen_process_catalog.py            # записать полку
  python3 tools/v2/gen_process_catalog.py --check    # сторож: не разошлась
"""
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ИСТОЧНИК = КОРЕНЬ / "core/out/src/main/resources/orbita/out/semp-configuration.json"
ПОЛКА = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ПОЛКА-ПРОЦЕССЫ-СИ.json"

# Группа — значением перечисления истины (`process_catalog.group`), не словом.
ГРУППЫ = {range(1, 5): "system_design", range(5, 10): "product_realization", range(10, 18): "technical_management"}
# Мероприятия схемы Романова (стадия 2), которыми процесс NPR исполняется у нас.
РОМАНОВ = {
    1: ["ROM-2.1"], 2: ["ROM-2.1", "ROM-2.3"], 3: ["ROM-2.E2"], 4: ["ROM-2.5", "ROM-2.7"],
    7: ["ROM-2.E6"], 8: ["ROM-2.M7"], 10: ["ROM-2.P", "ROM-2.PE"], 11: ["ROM-2.3", "ROM-2.4"],
    12: ["ROM-2.E4"], 13: ["ROM-2.P"], 14: ["ROM-2.P"], 15: ["ROM-2.P"], 16: ["ROM-2.M3", "ROM-2.M6"], 17: ["ROM-2.6", "ROM-2.7"],
}
ISO = {
    1: "6.4.2 Stakeholder needs and requirements definition", 2: "6.4.3 System requirements definition",
    3: "6.4.4 Architecture definition", 4: "6.4.5 Design definition", 5: "6.4.7 Implementation",
    6: "6.4.8 Integration", 7: "6.4.9 Verification", 8: "6.4.11 Validation", 9: "6.4.10 Transition",
    10: "6.3.1 Project planning", 11: "6.4.3 (management aspects)", 12: "6.4.4 (interface management)",
    13: "6.3.4 Risk management", 14: "6.3.5 Configuration management", 15: "6.3.6 Information management",
    16: "6.3.2 Project assessment and control", 17: "6.3.3 Decision management",
}


def собрать() -> dict:
    конфиг = json.loads(ИСТОЧНИК.read_text(encoding="utf-8"))
    записи = []
    for p in конфиг["processes"]:
        n = int(p["number"])
        группа = next(g for r, g in ГРУППЫ.items() if n in r)
        tailoring = p.get("tailoring")
        запись = {
            "code": f"SE-{n:02d}", "name": p["process"],
            "npr_7123": f"NPR 7123.1 п. {n}", "iso_15288": ISO.get(n, ""),
            "group": группа,
            "orbita_mechanism": p.get("mechanism", "—"), "place": p.get("place", "—"),
            "tailoring_allowed": bool(tailoring),
        }
        # Истина: romanov_activity — ОДИН код; здесь их бывает два и больше.
        if РОМАНОВ.get(n):
            запись["romanov_activity"] = " · ".join(РОМАНОВ[n])
        # Текст отклонения у вида поля не имеет — идёт в notes ядра.
        if tailoring:
            запись["notes"] = tailoring
        записи.append(запись)
    return {
        "kind": "process_catalog", "version": 2,
        "title": "Каталог процессов СИ — 17 процессов NPR 7123.1, ISO 15288 и мероприятия Романова",
        "source": "core/out/src/main/resources/orbita/out/semp-configuration.json (NASA SEH App. J §5); РОМАНОВ-СТАДИИ-0-2 §3 п. 5",
        "rule": "процесс → механизм «Орбиты» → место; процесс без механизма назван отклонением (tailoring), а не умолчан",
        "note": "одна запись = один процесс (истина 21.09); инструменты среды инженерии — в SEMP §10 тезисом",
        "tools_for_semp_10": конфиг.get("tools", []),
        "items": записи,
    }


def main() -> int:
    новый = json.dumps(собрать(), ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if not ПОЛКА.exists() or ПОЛКА.read_text(encoding="utf-8") != новый:
            print("полка процессов СИ разошлась с источником — python3 tools/v2/gen_process_catalog.py")
            return 1
        print(f"полка процессов СИ: {len(json.loads(новый)['items'])} записей-процессов совпадают с конфигурацией SEMP")
        return 0
    ПОЛКА.write_text(новый, encoding="utf-8")
    print(f"записана {ПОЛКА.name}: {len(json.loads(новый)['items'])} записей-процессов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
