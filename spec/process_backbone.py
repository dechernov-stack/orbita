"""Спина процесса (блок B задания «прогон до KDP B», ADR-029).

Исполняемый эталон: состояние операции по объектам проекта, состав проверки
прохождения точки, порядок вех, блокировка возвратом. Перенос — один в один:
core/req/Operations.kt, core/com/api/GatePassing.kt (ProcessBackboneTest).

Реестры (операции, точки, возвраты §5.1) читаются ИЗ ФАЙЛОВ ресурсов —
эталон и код смотрят в одни данные, второй копии реестров нет.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "core/req/src/main/resources/orbita/req"

ORDER = ["Draft", "Preliminary", "Approved", "Baseline"]
BASELINE_GATES = {"SRR", "SDR", "KDP-B"}  # TBD/TBR и трассировка блокируют с базирования


def load_operations():
    return json.loads((RES / "operations.json").read_text())


def operation_state(op, objects):
    """Состояние операции (ADR-029 п. 6): не «строки есть», а «выход достиг статуса».

    objects — [{'type': ..., 'status': ...}] проекта, без Cancelled.
    """
    kinds = op["output"]["kinds"]
    if not kinds:
        return "NotMeasurable"          # материал вне системы (до блока C) — видимый пробел
    alive = [o for o in objects if o.get("status") != "Cancelled"]
    relevant = [o for o in alive if o["type"] in kinds]
    if not relevant:
        return "NotStarted"
    required = op.get("required_status")
    if required is None:
        return "Done"      # статус к точке не требуется — наличие выхода и есть выполнение
    for kind in kinds:
        of_kind = [o for o in alive if o["type"] == kind]
        if not of_kind or any(ORDER.index(o.get("status", "Draft")) < ORDER.index(required)
                              for o in of_kind):
            return "InProgress"
    return "Done"


def gate_issues(gate, phase, objects, maturity):
    """Перечень незакрытого до точки (ADR-029 п. 1–2).

    maturity — {'gaps': [(id, type, actual, required)], 'open_tbd': [...],
    'trace_breaks': [...]}; полная верификация точку Формулирования не блокирует.
    """
    reg = load_operations()
    issues = []
    for op in reg["operations"]:
        if op["phase"] != phase or op.get("gate") != gate:
            continue
        state = operation_state(op, objects)
        if state == "NotStarted":
            issues.append(f"{op['code']}: выход операции не создан")
        elif state == "InProgress":
            issues.append(f"{op['code']}: выход не достиг статуса {op['required_status']}")
    for (oid, otype, actual, required) in maturity.get("gaps", []):
        issues.append(f"{oid}: {otype} {actual} ниже требуемого {required}")
    if gate in BASELINE_GATES:
        issues += [f"{i}: незакрытый TBD/TBR" for i in maturity.get("open_tbd", [])]
        issues += [f"{i}: требование без входящей нити трассировки"
                   for i in maturity.get("trace_breaks", [])]
    return issues


def next_gate(milestones):
    """Ближайшая непройденная веха; None — все пройдены (ADR-029 п. 4)."""
    for m in milestones:
        if not m.get("held"):
            return m["gate"]
    return None


def can_pass(gate, milestones, active_return, issues):
    """Условия прохождения: порядок вех, нет возврата, перечень пуст (п. 1, 4, 5)."""
    reasons = []
    if active_return:
        reasons.append(f"действует возврат от точки {active_return['gate']}")
    nxt = next_gate(milestones)
    if nxt is None:
        reasons.append("все вехи уже пройдены")
    elif gate != nxt:
        reasons.append(f"ближайшая непройденная точка — {nxt}")
    reasons += issues
    return (not reasons), reasons


KITS = json.loads(
    (ROOT / "core/out/src/main/resources/orbita/out/document-kits.json").read_text()
)


def kit(phase):
    """Комплект документов фазы: Д-код → шаблон (блок C, document-kits.json)."""
    return KITS.get(phase, KITS["pre_phase_a"])


def blocking_reviews(gate, review_items):
    """Незакрытые критические замечания точки блокируют её (блок C, ADR-029)."""
    return [r["id"] for r in review_items
            if r.get("review_gate") == gate
            and r.get("classification") == "critical"
            and r.get("status") != "closed"]


def missing_issues(gate, phase, issued_templates):
    """Д-коды операций точки без выпуска документа своего шаблона."""
    reg = load_operations()
    k = kit(phase)
    dcodes = sorted({d for op in reg["operations"]
                     if op["phase"] == phase and op.get("gate") == gate
                     for d in ((op["output"]["doc"] if isinstance(op["output"]["doc"], list)
                                else [op["output"]["doc"]]) if op["output"]["doc"] else [])})
    return [d for d in dcodes if d in k and k[d] not in set(issued_templates)]


def return_targets(phase, gate):
    """Допустимые цели возврата — §5.1 регламентов, из реестра."""
    for r in load_operations().get("returns", []):
        if r["phase"] == phase and r["gate"] == gate:
            return r["to"]
    return []


# ================= проверки =================
if __name__ == "__main__":
    ok = fail = 0

    def check(name, cond, detail=""):
        global ok, fail
        if cond:
            ok += 1
            print(f"  + {name}")
        else:
            fail += 1
            print(f"  - {name} {detail}")

    reg = load_operations()
    o2 = next(o for o in reg["operations"] if o["phase"] == "pre_phase_a" and o["code"] == "О2")
    o9 = next(o for o in reg["operations"] if o["phase"] == "pre_phase_a" and o["code"] == "О9")

    check("пустой проект: операция с видами — не начата",
          operation_state(o2, []) == "NotStarted")
    check("операция без видов в системе — нечем измерить (не прочерк)",
          operation_state(o9, []) == "NotMeasurable")
    check("черновик достаточен, когда планка Draft",
          operation_state(o2, [{"type": "need", "status": "Draft"},
                               {"type": "mission_goal", "status": "Draft"}]) == "Done")
    check("один из объектов ниже планки — в работе",
          operation_state(dict(o2, required_status="Approved"),
                          [{"type": "need", "status": "Draft"}]) == "InProgress")
    check("отменённый объект выхода не считается",
          operation_state(o2, [{"type": "need", "status": "Cancelled"}]) == "NotStarted")

    empty_issues = gate_issues("internal_review", "pre_phase_a", [], {"gaps": []})
    check("пустой проект не проходит точку молча: незакрытое названо операциями",
          any("О2" in i for i in empty_issues) and any("О4" in i for i in empty_issues))

    check("TBD блокирует только с базирования: MCR — нет",
          gate_issues("MCR", "pre_phase_a",
                      [{"type": t, "status": "Approved"} for t in
                       ["need", "component", "requirement", "conops", "technology", "risk"]],
                      {"gaps": [], "open_tbd": ["RQ-0001"]}) == [])
    check("TBD блокирует SRR",
          any("TBD" in i for i in
              gate_issues("SRR", "phase_a", [], {"gaps": [], "open_tbd": ["RQ-0001"]})))

    ms = [{"gate": "internal_review", "held": True}, {"gate": "MCR"}, {"gate": "KDP-A"}]
    check("ближайшая непройденная — MCR", next_gate(ms) == "MCR")
    check("перескочить нельзя",
          can_pass("KDP-A", ms, None, [])[0] is False)
    check("возврат блокирует прохождение",
          can_pass("MCR", ms, {"gate": "MCR", "to": ["О3"]}, [])[0] is False)
    check("пустой перечень и порядок — точка проходится",
          can_pass("MCR", ms, None, [])[0] is True)
    check("все вехи пройдены — проходить нечего",
          can_pass("KDP-A", [{"gate": "KDP-A", "held": True}], None, [])[0] is False)

    check("комплекты полны: Д1–Д9 и Д1–Д10",
          list(kit("pre_phase_a")) == [f"Д{i}" for i in range(1, 10)] and
          list(kit("phase_a")) == [f"Д{i}" for i in range(1, 11)])
    check("КТ-1 без выпусков требует все девять документов",
          len(missing_issues("internal_review", "pre_phase_a", [])) == 9)
    check("выпуск шаблона закрывает свой Д-код",
          "Д1" not in missing_issues("internal_review", "pre_phase_a", ["fad"]))
    check("критическое незакрытое замечание своей точки блокирует",
          blocking_reviews("MCR", [{"id": "RF-1", "review_gate": "MCR",
                                    "classification": "critical", "status": "open"}]) == ["RF-1"])
    check("замечание-вопрос и чужая точка не блокируют",
          blocking_reviews("MCR", [
              {"id": "RF-2", "review_gate": "MCR", "classification": "question", "status": "open"},
              {"id": "RF-3", "review_gate": "SRR", "classification": "critical", "status": "open"},
          ]) == [])
    check("закрытое критическое не блокирует",
          blocking_reviews("MCR", [{"id": "RF-4", "review_gate": "MCR",
                                    "classification": "critical", "status": "closed"}]) == [])

    check("возвраты MCR — только О3 (§5.1 БП-PPA)",
          return_targets("pre_phase_a", "MCR") == ["О3"])
    check("возвраты SDR — О4 и О5 (§5.1 БП-PA)",
          set(return_targets("phase_a", "SDR")) == {"О4", "О5"})
    check("у точки без правила возвратов целей нет",
          return_targets("pre_phase_a", "SRR") == [])

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)
