#!/usr/bin/env python3
"""Реестр маршрутов клиента — файлом в docs, ИЗ КОДА, а не на слух.

Контент-поставки (задачи фазы, чек-листы, подсказки) пишутся по адресам
экранов, и адрес «на слух» уже однажды увёл шаг в создание проекта. Реестр
собирается из двух источников истины: `web/src/nav.ts` (рейка: раздел →
экраны) и `web/src/App.tsx` (все ключи `case '…'` с заголовками). Экран,
которого нет в рейке, попадает в реестр с пометкой «только переходом».

    python3 tools/routes_registry.py          перегенерировать docs/ui/routes.md
    python3 tools/routes_registry.py --check  сверить файл с кодом (код 1 при расхождении)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAV = ROOT / "web/src/nav.ts"
APP = ROOT / "web/src/App.tsx"
OUT = ROOT / "docs/ui/routes.md"


def sections():
    text = NAV.read_text(encoding="utf-8")
    out = []
    for block in re.finditer(r"key:\s*'([a-z]+)',\s*label:\s*'([^']+)',\s*screens:\s*\[(.*?)\]", text, re.S):
        key, label, body = block.groups()
        screens = re.findall(r"key:\s*'([a-z0-9]+)',\s*title:\s*'([^']+)'", body)
        out.append((key, label, screens))
    return out


def app_screens():
    text = APP.read_text(encoding="utf-8")
    found = {}
    for m in re.finditer(r"case '([a-z0-9]+)':(.*?)(?=case '[a-z0-9]+':|default:)", text, re.S):
        key, body = m.groups()
        title = re.search(r'title="([^"]+)"', body)
        found[key] = title.group(1) if title else ""
    return found


def render() -> str:
    nav = sections()
    app = app_screens()
    in_nav = {s for _, _, screens in nav for s, _ in screens}
    lines = [
        "# Реестр маршрутов клиента",
        "",
        "Собран из кода (`tools/routes_registry.py`): рейка — `web/src/nav.ts`, экраны —",
        "`web/src/App.tsx`. Адреса контент-поставок (поле `screen` задач фазы, чек-листов,",
        "операций) берутся отсюда, не на слух; сторож сида сверяет их с этим же кодом.",
        "",
        "| Экран (`screen`) | Заголовок | Как открывается |",
        "|---|---|---|",
    ]
    for _, label, screens in nav:
        for key, title in screens:
            заголовок = app.get(key) or title
            lines.append(f"| `{key}` | {заголовок} | рейка «{label}» → «{title}» |")
    rest = sorted(k for k in app if k not in in_nav)
    for key in rest:
        lines.append(f"| `{key}` | {app.get(key) or '—'} | только переходом (кнопка, ссылка, шаг задачи) |")
    lines += [
        "",
        f"Экранов в клиенте: {len(app)}; в рейке: {len(in_nav)}; только переходом: {len(rest)}.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    text = render()
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print("реестр маршрутов устарел: python3 tools/routes_registry.py", file=sys.stderr)
            return 1
        print("реестр маршрутов: совпадает с кодом")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"реестр маршрутов: {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
