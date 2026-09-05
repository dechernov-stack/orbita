#!/usr/bin/env python3
"""Загрузчик полок v2: содержимое поставки → библиотечная область стенда.

Правило ТЗ §6.3: «контент и полки — из поставок внешнего контура
генератором; правка пакетов руками — отказ сторожа». Поэтому полки не
редактируются на стенде и не пишутся руками в сиды: этот загрузчик —
единственная дорога от файла поставки к полке.

  python3 tools/v2/load_shelves.py [http://localhost:8080/api]
  python3 tools/v2/load_shelves.py --check   # сторож: файлы поставки не тронуты

Идемпотентно: полка, совпадающая с поставкой, не трогается; иная —
обновляется НОВОЙ ВЕРСИЕЙ объекта, ссылки на неё правку переживают.
"""
import argparse
import getpass
import hashlib
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОСТАВКА = КОРЕНЬ / "docs/tz/v2"
ОТПЕЧАТКИ = КОРЕНЬ / "tools/v2/shelves.sha256"

# файл поставки → вид полки; порядок = порядок загрузки (ссылки идут вниз)
ПОЛКИ = [
    ("ПОЛКА-PBS.json", "pbs_template"),
    ("ПОЛКА-ИНТЕРФЕЙСЫ.json", "interface_template"),
    ("ПОЛКА-АРХИТЕКТУРА-ARCADIA.json", "architecture_template"),
    ("ПОЛКА-WBS.json", "wbs_template"),
    ("ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json", "component_template_shelf"),
    ("ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json", "lifecycle_process_reference"),
]

TOKEN: str | None = None


def отпечаток(путь: pathlib.Path) -> str:
    return hashlib.sha256(путь.read_bytes()).hexdigest()


def сторож() -> int:
    """Файлы поставки правятся только поставкой: расхождение — отказ."""
    ожидаемые = {}
    if ОТПЕЧАТКИ.exists():
        for строка in ОТПЕЧАТКИ.read_text(encoding="utf-8").splitlines():
            if строка.strip() and not строка.startswith("#"):
                хеш, имя = строка.split(None, 1)
                ожидаемые[имя.strip()] = хеш
    расхождения, новые = [], []
    for имя, _ in ПОЛКИ:
        путь = ПОСТАВКА / имя
        if not путь.exists():
            расхождения.append(f"нет файла поставки: {имя}")
            continue
        текущий = отпечаток(путь)
        if имя not in ожидаемые:
            новые.append(f"{текущий}  {имя}")
        elif ожидаемые[имя] != текущий:
            расхождения.append(f"файл поставки правлен руками: {имя}")
    if расхождения:
        print("полки v2: поставка нарушена —")
        for x in расхождения:
            print("  ", x)
        print("   поставку правит внешний контур, не стенд и не руки")
        return 1
    if новые:
        ОТПЕЧАТКИ.write_text(
            "# отпечатки файлов поставки полок: правка руками — отказ сторожа\n"
            + "\n".join(sorted(новые)) + "\n",
            encoding="utf-8",
        )
        print(f"полки v2: записаны отпечатки {len(новые)} файлов поставки")
        return 0
    print(f"полки v2: {len(ПОЛКИ)} файлов поставки совпадают с отпечатками")
    return 0


def запрос(метод: str, путь: str, тело=None):
    заголовки = {"Content-Type": "application/json; charset=utf-8"}
    if TOKEN:
        заголовки["Authorization"] = f"Bearer {TOKEN}"
    req = urllib.request.Request(
        БАЗА + путь,
        data=json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None,
        headers=заголовки,
        method=метод,
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())


def вход_стенда() -> str | None:
    """Стенд (ORBITA_AUTH_MODE=stand) пускает учётку без пароля — своей ручкой.

    Пароля у витринных учёток нет вовсе, поэтому обычный вход отвечает 401:
    сначала пробуем стендовую дорогу и только потом спрашиваем пароль.
    """
    try:
        кто = запрос("GET", "/auth/whoami")
    except Exception:
        return None
    if кто.get("mode") != "stand":
        return None
    учётки = [u["login"] for u in кто.get("stand_users", [])]
    желаемый = os.environ.get("ORBITA_LOGIN")
    логин = желаемый if желаемый in учётки else (учётки[0] if учётки else None)
    if not логин:
        return None
    req = urllib.request.Request(
        БАЗА + "/auth/stand-login",
        data=json.dumps({"login": логин}, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        куки = r.headers.get_all("Set-Cookie") or []
        тело = json.loads(r.read().decode())
    for c in куки:
        for часть in c.split(";"):
            имя, _, значение = часть.strip().partition("=")
            if имя == "orbita_session" and значение:
                print(f"вошли учёткой стенда: {тело.get('display_name') or логин}")
                return значение
    return None


def вход() -> str:
    стендовая = вход_стенда()
    if стендовая:
        return стендовая
    логин = os.environ.get("ORBITA_LOGIN")
    пароль = os.environ.get("ORBITA_PASSWORD")
    if not логин or not пароль:
        if not sys.stdin.isatty():
            sys.exit("нужен вход: задайте ORBITA_LOGIN и ORBITA_PASSWORD либо запустите из терминала")
        логин = логин or input("логин: ").strip()
        пароль = пароль or getpass.getpass("пароль: ")
    req = urllib.request.Request(
        БАЗА + "/auth/login",
        data=json.dumps({"login": логин, "password": пароль}, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        куки = r.headers.get_all("Set-Cookie") or []
        тело = json.loads(r.read().decode())
    for c in куки:
        for часть in c.split(";"):
            имя, _, значение = часть.strip().partition("=")
            if имя == "orbita_session" and значение:
                print(f"вошли как {тело.get('display_name') or логин}")
                return значение
    sys.exit(f"вход прошёл, но сессия не пришла: {тело}")


def вызов(метод: str, путь: str, тело=None):
    global TOKEN
    try:
        return запрос(метод, путь, тело)
    except urllib.error.HTTPError as e:
        if e.code == 403:
            sys.exit(f"вход есть, но роли не хватает: {метод} {путь} → 403 (нужен lead_se либо lead)")
        if e.code != 401 or TOKEN:
            raise
        TOKEN = вход()
        return запрос(метод, путь, тело)


def загрузить() -> int:
    залито = обновлено = пропущено = 0
    непринято: list[str] = []
    for имя, вид in ПОЛКИ:
        путь = ПОСТАВКА / имя
        if not путь.exists():
            print(f"{вид}: файла поставки нет — пропуск ({имя})")
            continue
        документ = json.loads(путь.read_text(encoding="utf-8"))
        код = документ.get("code")
        if not код:
            print(f"{вид}: в поставке нет поля code — пропуск ({имя})")
            continue
        try:
            текущий = вызов("GET", f"/objects/{код}")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            текущий = None
        if текущий is None:
            try:
                вызов("POST", "/library/objects", {"type": вид, "doc": документ, "author": "поставка v2"})
            except urllib.error.HTTPError as e:
                # Приёмник видов v2 — модуль library волны 1. Пока его нет,
                # стенд честно отвечает «unknown type»: это состояние работ,
                # а не сбой загрузчика, и молчать о нём нельзя.
                if e.code == 400:
                    подробность = e.read().decode(errors="replace")[:200]
                    print(f"{вид}: стенд не принимает вид ({подробность.strip()})")
                    непринято.append(вид)
                    continue
                raise
            print(f"{вид}: залит {код}")
            залито += 1
        elif текущий["doc"] == документ:
            print(f"{вид}: {код} совпадает с поставкой — пропуск")
            пропущено += 1
        else:
            изменения = {k: v for k, v in документ.items() if k not in ("id", "lifecycle")}
            вызов("PATCH", f"/edit/{код}", {
                "author": "поставка v2",
                "base_version": текущий["version"],
                "changes": изменения,
            })
            print(f"{вид}: обновлён {код} — полка была старее поставки")
            обновлено += 1
    print(f"итог: залито {залито}, обновлено {обновлено}, без изменений {пропущено}")
    if непринято:
        print(
            "не приняты стендом (виды v2 появятся с модулем library, волна 1): "
            + ", ".join(непринято)
        )
        return 2
    return 0


if __name__ == "__main__":
    разбор = argparse.ArgumentParser(description="загрузчик полок v2")
    разбор.add_argument("base", nargs="?", default="http://localhost:8080/api")
    разбор.add_argument("--check", action="store_true", help="сторож отпечатков поставки")
    аргументы = разбор.parse_args()
    БАЗА = аргументы.base
    sys.exit(сторож() if аргументы.check else загрузить())
