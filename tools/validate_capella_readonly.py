#!/usr/bin/env python3
"""Сторож ADR-048: в модель Capella система не пишет никогда.

Проверяется:
  1. адаптер (ops/capella) не открывает файлы на запись и не зовёт save/write
     у модели — только чтение через capellambse;
  2. в docker-compose модель монтируется только на чтение (:ro);
  3. в ядре и клиенте нет записи в .capella/.aird — ни путей, ни вызовов;
  4. библиотека закреплена версией в Dockerfile адаптера, форка нет.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "ops/capella/capella_service.py"
DOCKERFILE = ROOT / "ops/capella.Dockerfile"
COMPOSE = ROOT / "docker-compose.yml"

WRITES = [
    (re.compile(r"open\([^)]*['\"][wax]"), "открытие файла на запись в адаптере"),
    (re.compile(r"\.save\(|\.write\(|\.mkdir\(|shutil\.|os\.remove|os\.rename"), "запись/удаление из адаптера — модель только читается"),
]


def main() -> int:
    problems: list[str] = []
    src = ADAPTER.read_text(encoding="utf-8")
    for pattern, why in WRITES:
        for m in pattern.finditer(src):
            # HTTP-ответ и лог в stderr — не файлы модели
            line_text = src.splitlines()[src.count("\n", 0, m.start())]
            if "wfile.write" in line_text or "stderr.write" in line_text:
                continue
            line = src.count("\n", 0, m.start()) + 1
            problems.append(f"{ADAPTER.relative_to(ROOT)}:{line}: {why} — «{m.group(0)}»")
    if "capellambse" not in src or "MelodyModel(" not in src:
        problems.append(f"{ADAPTER.relative_to(ROOT)}: модель обязана читаться capellambse (MelodyModel)")
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")
    if not re.search(r"capellambse==\d+\.\d+\.\d+", dockerfile):
        problems.append(f"{DOCKERFILE.relative_to(ROOT)}: версия capellambse не закреплена")
    compose = COMPOSE.read_text(encoding="utf-8")
    block = compose.split("  capella:", 1)
    if len(block) < 2:
        problems.append("docker-compose.yml: службы capella нет")
    else:
        body = block[1].split("\n  ", 1)[0] if "\n  " in block[1] else block[1]
        svc = block[1][: block[1].find("\n\n") if "\n\n" in block[1] else None]
        if "/model" in svc and ":ro" not in svc:
            problems.append("docker-compose.yml: модель Capella смонтирована не только на чтение (нет :ro)")
    for base in (ROOT / "core", ROOT / "web/src", ROOT / "tools"):
        for p in base.rglob("*"):
            if not p.is_file() or p.suffix not in {".kt", ".ts", ".tsx", ".py"} or "/build/" in str(p) or "node_modules" in str(p):
                continue
            if p.name == "validate_capella_readonly.py":
                continue
            text = p.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(r"\.(?:capella|aird)\b", text):
                line_text = text.splitlines()[text.count("\n", 0, m.start())]
                if re.search(r"write|save|open\(", line_text):
                    line = text.count("\n", 0, m.start()) + 1
                    problems.append(f"{p.relative_to(ROOT)}:{line}: запись в модель Capella запрещена — «{line_text.strip()[:80]}»")
    if problems:
        print("ЗАПИСЬ В МОДЕЛЬ CAPELLA / НАРУШЕНИЯ ADR-048:")
        for pr in problems:
            print("  " + pr)
        return 1
    print("модель Capella: только чтение (адаптер, монтирование :ro, версия закреплена, записей в .capella/.aird нет)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
