#!/usr/bin/env python3
"""Нормативы карточками на полку А1 (ЗАДАНИЕ-CODE-ЧАСЫ §4).

Норматив — общий для всех проектов: один и тот же ПП РФ не заводится в
каждом проекте заново, проект ссылается на него основанием ограничения.
Поэтому карточки кладутся в БИБЛИОТЕЧНУЮ область, а не в проект.

Текста актов здесь нет и не требуется сейчас: карточка несёт обозначение,
редакцию, дату, срок и обязанность — этого хватает, чтобы ограничение
сцены 5 получило `normative_basis`, а требование — основание.

    python3 tools/v2/load_normatives.py [http://localhost:8080/api]
    python3 tools/v2/load_normatives.py --check   # сверка: полка совпадает с поставкой

Идемпотентно: карточка, совпадающая с поставкой, не трогается.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОЛКА = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ПОЛКА-НОРМАТИВЫ.json"

_opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(__import__("http.cookiejar", fromlist=["CookieJar"]).CookieJar()),
)


def вызов(base: str, method: str, path: str, тело=None):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    запрос = urllib.request.Request(
        base + path, data=данные,
        headers={"Content-Type": "application/json; charset=utf-8"}, method=method,
    )
    with _opener.open(запрос, timeout=120) as ответ:
        тело = ответ.read().decode()
        return json.loads(тело) if тело else {}


def код(обозначение: str) -> str:
    """Код карточки — из обозначения акта: он и есть его имя у людей."""
    замены = {" ": "-", "«": "", "»": "", "(": "", ")": "", ",": "", ".": "", "/": "-", "№": "N"}
    строка = обозначение
    for что, чем in замены.items():
        строка = строка.replace(что, чем)
    return строка[:60]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("base", nargs="?", default="http://localhost:8080/api")
    ap.add_argument("--check", action="store_true", help="сверить поставку, на стенд не ходить")
    ap.add_argument("--login", default="ivanov", help="учётка стенда: полки ведёт ведущий СИ")
    args = ap.parse_args()

    полка = json.loads(ПОЛКА.read_text(encoding="utf-8"))
    акты = полка["items"]

    обязательные = ("designation", "title", "kind", "edition", "edition_date")
    беды = [
        f"{а.get('designation', '?')}: нет поля «{п}»"
        for а in акты for п in обязательные if not а.get(п)
    ]
    без_обязанности = [а["designation"] for а in акты if not а.get("clauses")]
    беды += [f"{д}: нет ни одной обязанности" for д in без_обязанности]
    if беды:
        print("поставка нормативов неполна:", file=sys.stderr)
        for б in беды:
            print("  ·", б, file=sys.stderr)
        return 1
    if args.check:
        print(f"полка нормативов: {len(акты)} карточек, у каждой реквизиты и обязанность")
        return 0

    вызов(args.base, "POST", "/auth/stand-login", {"login": args.login})
    залито = обновлено = совпало = 0
    for акт in акты:
        документ = {к: в for к, в in акт.items() if к != "code"}
        try:
            ответ = вызов(args.base, "POST", "/v2/shelves", {
                "kind": "normative_document", "code": код(акт["designation"]),
                "doc": документ, "author": "поставка v2",
            })
        except urllib.error.HTTPError as e:
            print(f"  {акт['designation']}: отказ {e.code} — {e.read().decode()[:200]}", file=sys.stderr)
            return 1
        состояние = ответ.get("state", "выложен")
        if состояние == "unchanged":
            совпало += 1
        elif состояние == "updated":
            обновлено += 1
        else:
            залито += 1
        print(f"  {акт['designation']}")
    print(f"нормативы на полке: {len(акты)} (залито {залито}, обновлено {обновлено}, "
          f"без изменений {совпало})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
