#!/usr/bin/env python3
"""Сторож: критерий готовности только читает.

Правило владельца 08.09 (ПРИЁМКА-KNOWLEDGE-REMARKS, правило 1) после
находки сторожа отказов: критерий `maturation_planned` звал генератор
созревания — и тем самым сам закрывал разрыв, который проверял. Отказать он
не мог никогда. Проверка не имеет права ничего создавать, менять или
запускать: она наблюдает состояние и называет причину.

Что проверяется (код критериев = `*Checks.kt` доменных модулей и оценщик
`DomainGateEvaluator.kt`; модуль `readiness` целиком — по импортам):
  · импорты: `readiness` видит соседей только через их `api`; `*Checks.kt`
    не лезет во `internal` чужого модуля;
  · вызовы на портах: у каждого порта из конструктора (`private val x: Тип`)
    метод из списка ЗАПИСИ И ГЕНЕРАЦИИ — отказ. Список — по глаголам
    портов v2 (create/update/link/unlink/…/maturation/takeWbs/estimate…);
    метод чтения, которого нет ни в одном порте, — тоже отказ: сторож
    не должен пропускать неизвестное молча;
  · безадресно: `.create(` / `.update(` / `.link(` / `.unlink(` на любом
    получателе — отказ (хранилище могли передать под другим именем).

    python3 tools/validate_checks_readonly.py            # проверка
    python3 tools/validate_checks_readonly.py --selftest # сторож ловит подделку
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V2 = ROOT / "core" / "v2"

# Глаголы записи и генерации на портах v2. Новый порт с новым глаголом —
# сюда же, иначе сторож отказывает как «неизвестный метод».
WRITE_VERBS = {
    # kernel
    "create", "update", "link", "unlink", "confirm", "publish", "subscribe",
    # process
    "openPhase", "passGate",
    # requirements / architecture
    "baseline", "instantiate", "deploy",
    # programmatics — генераторы
    "estimate", "maturation", "planPackage", "takeWbs",
    # documents
    "addStatement", "write", "render", "ensure", "print",
    # knowledge
    "accept", "addFact", "addTopic", "dispose", "plan", "putFacts",
    "putMaterial", "canon", "task",
}
# Безадресный запрет: эти вызовы в коде критерия недопустимы на любом получателе.
BARE_FORBIDDEN = ("create", "update", "link", "unlink", "maturation", "takeWbs", "estimate", "planPackage")


def port_methods() -> dict[str, set[str]]:
    """Интерфейсы портов `api` всех модулей v2: имя → методы."""
    out: dict[str, set[str]] = {}
    for f in V2.glob("*/src/main/kotlin/orbita/*/api/*.kt"):
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(r"\binterface\s+(\w+)[^{]*\{", text):
            body = block(text, m.end() - 1)
            out.setdefault(m.group(1), set()).update(re.findall(r"\bfun\s+(?:<[^>]+>\s*)?(\w+)\s*\(", body))
    return out


def block(text: str, open_at: int) -> str:
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_at + 1:i]
    return text[open_at + 1:]


def module_of(path: Path) -> str:
    parts = path.relative_to(V2).parts
    return parts[0]


def analyse(path: Path, text: str, ports: dict[str, set[str]]) -> list[str]:
    errors: list[str] = []
    rel = path.relative_to(ROOT) if path.is_absolute() and ROOT in path.parents else path
    mod = module_of(path) if path.is_absolute() and V2 in path.parents else "readiness"

    # 1. Импорты.
    for m in re.finditer(r"^import orbita\.(\w+)\.(\w+)", text, re.M):
        other, layer = m.group(1), m.group(2)
        if layer == "internal" and other != mod:
            errors.append(f"{rel}: импорт internal чужого модуля «{other}» — критерий читает соседей только через api")
        if mod == "readiness" and other != "readiness" and layer != "api":
            errors.append(f"{rel}: readiness импортирует orbita.{other}.{layer} — допустим только api")

    # 2. Порты из конструктора и вызовы на них.
    head = text.split("{", 1)[0] if "class " in text else ""
    ctor = re.search(r"class\s+\w+\s*\((.*?)\)\s*(?::|\{)", text, re.S)
    port_vars: dict[str, str] = {}
    if ctor:
        for v in re.finditer(r"va[lr]\s+(\w+)\s*:\s*([\w.]+)", ctor.group(1)):
            port_vars[v.group(1)] = v.group(2).split(".")[-1]
    all_methods = set().union(*ports.values()) if ports else set()
    for var, typ in port_vars.items():
        for m in re.finditer(rf"\b{re.escape(var)}\.(\w+)\s*\(", text):
            method = m.group(1)
            if method in WRITE_VERBS:
                errors.append(f"{rel}:{line_no(text, m.start())}: {var}.{method}() — запись/генерация в критерии готовности")
            elif typ in ports and method not in ports[typ] and method not in all_methods:
                errors.append(f"{rel}:{line_no(text, m.start())}: {var}.{method}() — метода нет в портах v2; сторож не пропускает неизвестное")

    # 3. Безадресный запрет.
    for verb in BARE_FORBIDDEN:
        for m in re.finditer(rf"\.{verb}\s*\(", text):
            # уже названо по порту — не дублировать
            var = text[:m.start()].split()[-1].split(".")[-1] if text[:m.start()].split() else ""
            if var in port_vars:
                continue
            errors.append(f"{rel}:{line_no(text, m.start())}: .{verb}( — запись/генерация в критерии готовности")
    return errors


def line_no(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def criteria_files() -> list[Path]:
    files = sorted(V2.glob("*/src/main/kotlin/orbita/*/internal/*Checks.kt"))
    files += sorted((V2 / "readiness" / "src" / "main" / "kotlin").rglob("*.kt"))
    seen: set[Path] = set()
    return [f for f in files if not (f in seen or seen.add(f))]


def selftest(ports: dict[str, set[str]]) -> int:
    fake = '''package orbita.readiness.internal
import orbita.kernel.api.EntityStore
import orbita.programmatics.api.Programmatics
import orbita.programmatics.internal.EntityProgrammatics

class FakeChecks(
    private val store: EntityStore,
    private val programmatics: Programmatics,
) : ExtraChecks {
    fun x() {
        store.list(Area.Project, "p")
        store.create(x)
        programmatics.maturation("p", "a")
        programmatics.maturationState("p")
        registry.link(a, b)
    }
}
'''
    errs = analyse(V2 / "readiness" / "fake" / "FakeChecks.kt", fake, ports)
    ожидаемые = ["internal чужого", "store.create()", "programmatics.maturation()", ".link("]
    пропущено = [o for o in ожидаемые if not any(o in e for e in errs)]
    if пропущено:
        print("!!! самопроверка: сторож НЕ поймал: " + ", ".join(пропущено))
        for e in errs:
            print("    " + e)
        return 1
    лишнее = [e for e in errs if "maturationState" in e or "store.list" in e]
    if лишнее:
        print("!!! самопроверка: сторож ругает чтение: " + "; ".join(лишнее))
        return 1
    print(f"   самопроверка: подделка поймана ({len(errs)} отказа), чтение пропущено")
    return 0


def main() -> int:
    ports = port_methods()
    if "--selftest" in sys.argv:
        return selftest(ports)
    files = criteria_files()
    errors: list[str] = []
    reads = 0
    for f in files:
        text = f.read_text(encoding="utf-8")
        errors += analyse(f, text, ports)
        reads += len(re.findall(r"\b\w+\.(?:list|byId|byCode|from|to|packages|maturationState|components|card|gaps|suspects|baselines)\(", text))
    if errors:
        print("!!! критерий готовности пишет или запускает:")
        for e in errors:
            print("    " + e)
        return 1
    print(f"   файлов критериев: {len(files)}, портов: {len(ports)}, вызовов чтения: {reads}, записи: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
