#!/usr/bin/env python3
"""Сторож демо-значений проекта-примера: таблица значений не должна
разъезжаться с полкой каркаса.

Значения примера (`ЗНАЧЕНИЯ`, `ДИАПАЗОНЫ`, `TBR` в
`tools/build_example_project.py`) заполняют АНКЕТЫ узлов: ключ значения —
это ключ `expects` узла каркаса. Полка живёт своей жизнью: узел
переименовали, ключ анкеты поправили — и значение примера тихо перестало
попадать в анкету, а сборка отчиталась «заполнено N» уже без него.

Здесь это ловится статикой: каждый код узла обязан быть на полке PBS, а
каждый ключ — среди `expects` этого узла.

    python3 tools/validate_example_values.py
"""
from __future__ import annotations

import ast
import json
import sys
from pathlib import Path

КОРЕНЬ = Path(__file__).resolve().parent.parent
СБОРЩИК = КОРЕНЬ / "tools/build_example_project.py"
ПОЛКА = КОРЕНЬ / "docs/tz/manual-run-4/ПОЛКА-PBS.json"


def константа(имя: str):
    """Значение константы модуля — БЕЗ импорта: сборщик ходит в сеть."""
    дерево = ast.parse(СБОРЩИК.read_text(encoding="utf-8"))
    for узел in дерево.body:
        цели = (узел.targets if isinstance(узел, ast.Assign)
                else [узел.target] if isinstance(узел, ast.AnnAssign) else [])
        for ц in цели:
            if isinstance(ц, ast.Name) and ц.id == имя and узел.value is not None:
                return разобрать(узел.value)
    raise SystemExit(f"в сборщике нет константы {имя}")


def разобрать(узел: ast.AST):
    """Литерал или вызов dict(...) — в значение. Таблицы примера написаны
    и скобками, и dict(...), и сторож обязан читать обе записи."""
    if isinstance(узел, ast.Call) and isinstance(узел.func, ast.Name) and узел.func.id == "dict":
        return {k.arg: разобрать(k.value) for k in узел.keywords if k.arg}
    if isinstance(узел, (ast.List, ast.Tuple)):
        return [разобрать(э) for э in узел.elts]
    if isinstance(узел, ast.Dict):
        return {разобрать(k): разобрать(v) for k, v in zip(узел.keys, узел.values)}
    return ast.literal_eval(узел)


def узлы_полки() -> dict[str, set[str]]:
    """Код узла → ключи его анкеты, как они лежат на полке каркаса."""
    данные = json.loads(ПОЛКА.read_text(encoding="utf-8"))
    строки = (данные if isinstance(данные, list)
              else данные.get("nodes") or данные.get("objects") or [])
    out: dict[str, set[str]] = {}
    for о in строки:
        код = о.get("code")
        if not код:
            continue
        # в поставке анкета лежит в `params`, в объекте проекта — в `expects`
        анкета = о.get("params") or о.get("expects") or []
        out[код] = {e.get("key") for e in анкета if e.get("key")}
    return out


def main() -> int:
    полка = узлы_полки()
    if not полка:
        print(f"полка каркаса не разобрана: {ПОЛКА}", file=sys.stderr)
        return 1
    беды: list[str] = []

    значения = константа("ЗНАЧЕНИЯ")
    диапазоны = константа("ДИАПАЗОНЫ")
    tbr = константа("TBR")

    пар = 0
    for таблица, имя_таблицы in ((значения, "ЗНАЧЕНИЯ"), (диапазоны, "ДИАПАЗОНЫ")):
        for код, ключи in таблица.items():
            if код not in полка:
                беды.append(f"{имя_таблицы}: узла «{код}» на полке каркаса нет")
                continue
            for ключ in ключи:
                пар += 1
                if ключ not in полка[код]:
                    беды.append(
                        f"{имя_таблицы}: узел «{код}» не спрашивает «{ключ}» — "
                        f"анкета узла ждёт: {', '.join(sorted(полка[код])) or '—'}")
    for t in tbr:
        пар += 1
        код, ключ = t["node"], t["key"]
        if код not in полка:
            беды.append(f"TBR: узла «{код}» на полке каркаса нет")
        elif ключ not in полка[код]:
            беды.append(f"TBR: узел «{код}» не спрашивает «{ключ}»")
        elif not t.get("owner") or not t.get("due") or not t.get("why"):
            беды.append(f"TBR {код}.{ключ}: помета обязана нести владельца, "
                        f"точку и причину (канон L-C5)")

    if беды:
        print("демо-значения примера: НЕ в порядке", file=sys.stderr)
        for b in беды:
            print("  ·", b, file=sys.stderr)
        return 1
    print(f"демо-значения примера: {пар} пар «узел · ключ анкеты» сходятся "
          f"с полкой каркаса ({len(полка)} узлов)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
