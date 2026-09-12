#!/usr/bin/env python3
"""Миграция стенда под «Знания v2» (ADR-070): материалам без ранга доверия —
ранг по прежнему типу входного, фактам — ранг от их материала.

Правило миграции из поставки: записка · ТЗ · директива · норматив ·
регуляторное решение → mandatory; аналитика · наследие · даташит · КП ·
чужой ICD · чужая модель · справка → reference; неизвестное → doubtful
С ПОМЕТОЙ (правдоподобный ранг не выдумывается). Ручной факт инженера —
expert. Факты, у которых материал уже несёт ранг, наследуют его.

  python3 tools/v2/migrate_authority.py [--base URL] [--apply]

Без --apply — только план: сколько чего и по какому правилу. Вход на стенд —
stand_session (учётка стенда либо Telegram с ролью «от имени»).
"""
from __future__ import annotations

import argparse
import http.cookiejar
import json
import pathlib
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stand_session  # noqa: E402

ПО_ТИПУ = {
    "mission_memo": "mandatory", "tor": "mandatory", "directive": "mandatory",
    "normative": "mandatory", "regulatory_decision": "mandatory",
    "analysis": "reference", "heritage": "reference", "datasheet": "reference",
    "quote": "reference", "external_icd": "reference", "external_model": "reference",
    "reference": "reference",
}
_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def вызов(base: str, метод: str, путь: str, тело=None):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    з = urllib.request.Request(base + путь, data=данные, headers={"Content-Type": "application/json; charset=utf-8"}, method=метод)
    try:
        with _opener.open(з, timeout=120) as о:
            сырое = о.read().decode()
            return json.loads(сырое) if сырое else {}
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{метод} {путь} → {e.code}: {e.read().decode()[:300]}")


def ранг_материала(док: dict) -> tuple[str, str]:
    """Ранг и правило, по которому он выведен."""
    тип = (док.get("type") or док.get("kind") or "").strip()
    if тип in ПО_ТИПУ:
        return ПО_ТИПУ[тип], f"тип входного «{тип}»"
    return "doubtful", f"тип «{тип or 'не указан'}» неизвестен — ранг сомнительный с пометой"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--apply", action="store_true", help="писать; без флага — только план")
    a = ap.parse_args()
    stand_session.войти(a.base, _opener, "ivanov")
    проекты = [p["code"] for p in вызов(a.base, "GET", "/v2/projects").get("items", [])]
    if not проекты:
        print("проектов v2 на стенде нет — мигрировать нечего")
        return 0
    всего_м = всего_ф = 0
    for проект in проекты:
        материалы = вызов(a.base, "GET", f"/v2/entities?project={проект}&kind=material").get("items", [])
        факты = вызов(a.base, "GET", f"/v2/entities?project={проект}&kind=fact").get("items", [])
        ранги: dict[str, str] = {}
        без_ранга_м = [м for м in материалы if not (м.get("doc") or {}).get("authority")]
        for м in материалы:
            док = м.get("doc") or {}
            ранг = док.get("authority") or ранг_материала(док)[0]
            ранги[м["id"]] = ранг
            ранги[м["code"]] = ранг
        for м in без_ранга_м:
            ранг, правило = ранг_материала(м.get("doc") or {})
            print(f"  {проект} {м['code']}: ранг {ранг} — {правило}")
            if a.apply:
                вызов(a.base, "PATCH", f"/v2/entities/{м['code']}?project={проект}",
                      {"fields": {"authority": ранг}, "author": "миграция знаний v2", "reason": правило})
                всего_м += 1
        без_ранга_ф = [ф for ф in факты if not (ф.get("doc") or {}).get("authority")]
        for ф in без_ранга_ф:
            док = ф.get("doc") or {}
            источник = док.get("source") or {}
            материал = источник.get("material") or док.get("material") or ""
            ручной = bool(источник.get("account")) or not материал
            ранг = "expert" if ручной else ранги.get(материал, "doubtful")
            if a.apply:
                вызов(a.base, "PATCH", f"/v2/entities/{ф['code']}?project={проект}",
                      {"fields": {"authority": ранг}, "author": "миграция знаний v2",
                       "reason": "ранг эксперта — ручной факт" if ручной else f"ранг от материала {материал}"})
                всего_ф += 1
        print(f"{проект}: материалов без ранга {len(без_ранга_м)} из {len(материалы)}, фактов без ранга {len(без_ранга_ф)} из {len(факты)}")
    print(f"\nитог: {'проставлено' if a.apply else 'к правке'} материалов {всего_м or sum(1 for _ in [])}, фактов {всего_ф}"
          + ("" if a.apply else " (план; добавьте --apply)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
