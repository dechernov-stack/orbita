#!/usr/bin/env python3
"""Сторож: один сериализатор на вид.

Правило владельца 08.09 (ПРИЁМКА-KNOWLEDGE-REMARKS, правило 2). Список
фактов и ответ на заведение факта писались разным кодом — `manual` был в
одном и не был в другом; экран показывал «пусто» при живом ручном факте.
Тот же класс дефекта, что два построителя `Fact` раньше.

Сторож читает data-классы портов v2 (`core/v2/*/…/api/*.kt`) и ищет в
маршрутах (`core/v2/api`, `core/com`) рукописные цепочки `.put("поле", …)`.
Цепочка из ≥ 4 полей, совпадающая с полями одного класса, — это его
сериализатор. Два сериализатора одного класса в разных местах — отказ, с
разницей полей: именно она и была дефектом.

    python3 tools/validate_one_serializer.py
    python3 tools/validate_one_serializer.py --selftest
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V2 = ROOT / "core" / "v2"
ROUTES = [V2 / "api" / "src" / "main" / "kotlin"]
MIN_FIELDS = 4


def norm(name: str) -> str:
    return name.replace("_", "").lower()


def data_classes() -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for f in V2.glob("*/src/main/kotlin/orbita/*/api/*.kt"):
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(r"\bdata class\s+(\w+)\s*\(", text):
            body = paren_block(text, m.end() - 1)
            fields = {norm(x) for x in re.findall(r"va[lr]\s+(\w+)\s*:", body)}
            if len(fields) >= MIN_FIELDS:
                out[m.group(1)] = fields
    return out


def paren_block(text: str, open_at: int) -> str:
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_at + 1:i]
    return text[open_at + 1:]


def chains(path: Path) -> list[tuple[int, list[str], bool]]:
    """Цепочки подряд идущих строк с `.put("поле", …)`: (строка, поля, по документу реестра)."""
    out: list[tuple[int, list[str], bool]] = []
    cur: list[str] = []
    start = 0
    registry = False
    lines = path.read_text(encoding="utf-8").splitlines()

    def в_реестр(после: int) -> bool:
        """Документ уходит в хранилище: это построитель ЗАПИСИ, не сериализатор вида."""
        хвост = "\n".join(lines[после:после + 12])
        return "store.create(" in хвост or "store.update(" in хвост

    for n, line in enumerate(lines, 1):
        s = line.strip()
        puts = re.findall(r'\.put\("(\w+)"', line)
        if puts:
            if not cur:
                start = n
                registry = False
            cur += puts
            # Значения из `.doc.path(…)` (документ → ответ), из `тело.path(…)`
            # (запрос → документ) или документ, уходящий в `store.create` —
            # это рукописный построитель ВИДА РЕЕСТРА (следующий шаг правила:
            # DTO из YAML генерацией), а не сериализатор порта.
            registry = registry or any(k in line for k in (".doc.path(", ".doc[", "тело.path(", "body.path("))
        elif s.startswith("//") or not s or s.startswith("."):
            continue
        else:
            if cur:
                out.append((start, cur, registry or в_реестр(n - 1)))
            cur = []
    if cur:
        out.append((start, cur, registry))
    return out


def same(json: str, kotlin: str) -> bool:
    """Имя JSON против имени поля Kotlin: равно или усечено (packageCode → package)."""
    return json == kotlin or (len(json) >= 4 and kotlin.startswith(json))


def common_fields(F: set[str], C: set[str]) -> int:
    return sum(1 for f in F if any(same(f, c) for c in C))


def match(fields: list[str], classes: dict[str, set[str]]) -> str | None:
    F = {norm(f) for f in fields}
    if len(F) < MIN_FIELDS:
        return None
    best, score = None, 0.0
    for name, C in classes.items():
        common = common_fields(F, C)
        if common < MIN_FIELDS:
            continue
        s = common / len(F) + common / len(C)
        if common / len(F) >= 0.75 and common / len(C) >= 0.5 and s > score:
            best, score = name, s
    return best


def analyse(files: list[Path], classes: dict[str, set[str]]) -> tuple[dict[str, list[tuple[Path, int, list[str]]]], int, int]:
    sites: dict[str, list[tuple[Path, int, list[str]]]] = {}
    total = 0
    registry = 0
    for f in files:
        for start, fields, by_doc in chains(f):
            if by_doc:
                registry += 1
                continue
            cls = match(fields, classes)
            if cls:
                total += 1
                sites.setdefault(cls, []).append((f, start, fields))
    return sites, total, registry


def report(sites: dict[str, list[tuple[Path, int, list[str]]]]) -> list[str]:
    errors = []
    for cls, where in sorted(sites.items()):
        if len(where) < 2:
            continue
        all_fields = [set(map(norm, w[2])) for w in where]
        union = set().union(*all_fields)
        lines = [f"вид {cls}: сериализуется в {len(where)} местах"]
        for (f, n, fields), F in zip(where, all_fields):
            missing = sorted(union - F)
            rel = f.relative_to(ROOT) if ROOT in f.parents else f
            lines.append(f"    {rel}:{n}  полей {len(F)}" + (f", нет: {', '.join(missing)}" if missing else ""))
        errors.append("\n".join(lines))
    return errors


def selftest(classes: dict[str, set[str]]) -> int:
    import tempfile
    fake = '''
    private fun видА(ф: Fact): ObjectNode = mapper.createObjectNode()
        .put("id", ф.id)
        .put("kind", ф.kind)
        .put("subject", ф.subject)
        .put("predicate", ф.predicate)
        .put("value", ф.value)
        .put("unit", ф.unit)
        .put("anchor", ф.anchor)
        .put("mark", ф.mark.name)
        .put("material", ф.material)
        .put("topic", ф.topic)
        .put("disposition", ф.disposition.name)
        .put("confidence", ф.confidence)
        .put("manual", ф.manual)

    private fun список(): V2Router.Ответ {
        intake.facts(проект).forEach { факт ->
            массив.addObject()
                .put("id", факт.id)
                .put("kind", факт.kind)
                .put("subject", факт.subject)
                .put("predicate", факт.predicate)
                .put("value", факт.value)
                .put("unit", факт.unit)
                .put("anchor", факт.anchor)
                .put("mark", факт.mark.name)
                .put("material", факт.material)
                .put("topic", факт.topic)
                .put("disposition", факт.disposition.name)
                .put("confidence", факт.confidence)
        }
    }
'''
    with tempfile.NamedTemporaryFile("w", suffix=".kt", delete=False, encoding="utf-8") as t:
        t.write(fake)
        p = Path(t.name)
    sites, _, _ = analyse([p], classes)
    errs = report(sites)
    p.unlink()
    if not any("Fact" in e and "manual" in e for e in errs):
        print("!!! самопроверка: два сериализатора Fact с разницей «manual» не пойманы")
        for e in errs:
            print(e)
        return 1
    print("   самопроверка: два сериализатора Fact пойманы, разница «manual» названа")
    return 0


def main() -> int:
    classes = data_classes()
    if "--selftest" in sys.argv:
        return selftest(classes)
    files = sorted(f for r in ROUTES for f in r.rglob("*.kt"))
    sites, total, registry = analyse(files, classes)
    errors = report(sites)
    if errors:
        print("!!! два сериализатора одного вида (правило 2 владельца, 08.09):")
        for e in errors:
            print("  " + e)
        return 1
    print(f"   видов портов с полями: {len(classes)}, их сериализаторов: {total}, по одному на вид; "
          f"рукописных построителей видов реестра (.doc.path): {registry} — сводить к DTO из YAML")
    return 0


if __name__ == "__main__":
    sys.exit(main())
