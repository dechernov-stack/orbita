#!/usr/bin/env python3
"""Справочники и шаблоны системы на полку LIB: единицы (UR), глоссарий (GL),
анкеты характеристик (PF, Ф-06), шаблон записки миссии (DT, пачка-2).

Запуск: python3 tools/seed_registries.py [http://localhost:8080/api]

Идемпотентно: полка, совпадающая с сидом, не трогается; полка старее сида
обновляется НОВОЙ ВЕРСИЕЙ объекта, а не пересозданием — ссылки на неё
(границы пачек, разбор документов) обязаны правку пережить.

Учётки: когда они включены, сервер требует сессию, и сид без входа получает
401. Логин и пароль берутся из ORBITA_LOGIN / ORBITA_PASSWORD, а если их нет
и запуск идёт из терминала — спрашиваются здесь же. Пароль в командную
строку не передаётся: он попал бы в историю оболочки и в список процессов.
"""
import getpass
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api"
ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKETS = ROOT / "docs/tz/manual-run/packets"

SEEDS = [
    ("unit_registry", "/library/unit-registry", "07-справочник-единиц.json"),
    ("glossary", "/library/glossary", "08-глоссарий.json"),
    # Ф-06: анкеты характеристик — ими библиотека запрашивает данные
    ("property_form", "/library/property-forms", "09-анкеты-характеристик.json"),
    # Пачка-2: шаблон записки миссии — полка Б, нитка «образец → шаблон».
    # Списка шаблонов своей ручкой нет: наличие проверяем прямым чтением
    # объекта — портфель из нескольких проектов ломает общий /objects.
    ("document_template", None, "10-шаблон-записки.json"),
    # «Работа фазы»: задачи регламента — контент полки, а не код экрана.
    ("phase_task", None, "11-задачи-фазы-pre-a.json"),
    # Контент Phase A (БП-PA, О1–О17) — отдельным пакетом: полка наполняется
    # по фазам, и фаза без контента честно говорит об этом, а не пустует
    ("phase_task", None, "12-задачи-фазы-phase-a.json"),
    # Шаблон SEMP (Phase A, задача 1): 8 разделов прил. 1 БП-PA
    ("document_template", None, "13-шаблон-semp.json"),
    # Глоссарий системной инженерии (NASA SEH App. B): отдельный объект полки —
    # наш глоссарий главнее, заимствованный лежит рядом с пометкой источника
    ("glossary", None, "14-глоссарий-se.json"),
    # Чек внутреннего обзора (NASA SEH App. C): инспекция людей, задача 11
    ("review_checklist", None, "15-чек-обзора.json"),
    # Словарь линта формулировок: правится с экрана справочников
    ("quality_dictionary", None, "16-словарь-линта.json"),
]

TOKEN: str | None = None


def request(method: str, path: str, body=None):
    """Один запрос с текущей сессией; 401 отдаётся наверх для входа."""
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers=headers,
        method=method,
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())


def login() -> str:
    """Вход по учётке: сервер принимает тот же токен заголовком Bearer."""
    user = os.environ.get("ORBITA_LOGIN")
    password = os.environ.get("ORBITA_PASSWORD")
    if not user or not password:
        if not sys.stdin.isatty():
            sys.exit(
                "нужен вход: учётки включены. Задайте ORBITA_LOGIN и ORBITA_PASSWORD "
                "либо запустите сид из терминала — логин спросится здесь"
            )
        print("учётки включены — нужен вход")
        user = user or input("логин: ").strip()
        password = password or getpass.getpass("пароль: ")
    req = urllib.request.Request(
        BASE + "/auth/login",
        data=json.dumps({"login": user, "password": password}, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as r:
            # сессия приходит кукой; сервер принимает тот же токен заголовком
            # Authorization: Bearer — им и ходим дальше
            cookies = r.headers.get_all("Set-Cookie") or []
            body = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 401:
            sys.exit("вход отклонён: неверный логин или пароль")
        raise
    token = None
    for c in cookies:
        for part in c.split(";"):
            name, _, value = part.strip().partition("=")
            if name == "orbita_session" and value:
                token = value
    if not token:
        sys.exit(f"вход прошёл, но сессия не пришла: {body}")
    print(f"вошли как {body.get('display_name') or user}")
    return token


def call(method: str, path: str, body=None):
    """Запрос с одной попыткой входа: сид не должен падать об учётки."""
    global TOKEN
    try:
        return request(method, path, body)
    except urllib.error.HTTPError as e:
        if e.code == 403:
            # вход прошёл, но правка запрещена ролью: полки правит инженерный
            # контур (specialist · lead_se · lead), и это решает сервер
            sys.exit(
                f"вход есть, но роли не хватает: {method} {path} → 403. "
                "Полки правят роли specialist, lead_se или lead — "
                "назначьте роль учётке сида в проекте либо запустите сид "
                "под учёткой руководителя"
            )
        if e.code != 401 or TOKEN:
            raise
        TOKEN = login()
        return request(method, path, body)


def present(view, packet):
    """Что уже лежит на полке: списком ручки либо прямым чтением объектов."""
    if view:
        return call("GET", view)
    rows = []
    for obj in packet["objects"]:
        try:
            rows.append(call("GET", f"/objects/{obj['id']}")["doc"])
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
    return rows


def obsolete(type_: str, rows: list, packet: dict | None = None) -> bool:
    """Полка старее сида по СОСТАВУ полей, а не по версии: поля добавляются
    пачками (написания единиц — Д1, умолчание промпта — Ф-08.1, точки
    зрелости — Ф-06 п.5, метки источников в шаблоне — правка пачки-2), и
    объект надо обновить, а не пересоздать."""
    if type_ == "unit_registry":
        return not any("spellings" in d for d in rows)
    if type_ == "glossary":
        return not any(
            e.get("prompt_default") for d in rows for e in ([d] if "term" in d else d.get("entries", []))
        )
    if type_ == "property_form":
        return not any(f.get("required_by") for d in rows for f in d.get("fields", []))
    if type_ == "phase_task":
        # Задачи фазы обновляются, когда меняется их контент. Сверяем по
        # АДРЕСАМ условий: выдуманный идентификатор проверки готовности не
        # гасит шаг никогда, и полка с такими адресами обязана обновиться
        # (три таких были в первом наполнении Pre-A).
        живые = {"tbd", "trace", "reviews", "docs", "needs",
                 "verification", "carriers", "geo_masks", "data_requests", "need_stakeholder"}
        for d in rows:
            условия = [s.get("done_when", {}) for s in d.get("steps", [])] + d.get("input", [])
            for c in условия:
                if c.get("check") == "gate_check" and c.get("gate_check_id") not in живые:
                    return True
        # Адреса шагов писались на слух, и часть вела в несуществующие
        # экраны (находка живого прохода: «Орг-структура» вела в СОЗДАНИЕ
        # проекта). Полка с такими адресами обязана обновиться.
        мёртвые = {"constellations", "datarequests", "debris", "tech", "documents"}
        for d in rows:
            for шаг in d.get("steps", []):
                if шаг.get("screen") in мёртвые:
                    return True
                # задача фазы не ведёт в мастер начала проекта
                if d.get("phase") != "pre_phase_a" and шаг.get("screen") == "startpath":
                    return True
        # Круг 6: тип связи и порядок шагов стали данными полки. Полка без
        # них рисует всё «после окончания» — то есть врёт о регламенте.
        for d in rows:
            for связь in d.get("depends_on", []):
                if isinstance(связь, str) or "type" not in связь:
                    return True
            if len(d.get("steps", [])) > 1 and not any(s.get("after") for s in d.get("steps", [])):
                return True
        # Круг 8: разметка связей пересматривается ПО СМЫСЛУ, и признака-маркера
        # для этого не придумаешь — сверяем состав связей с сидом напрямую.
        # Так полка обновляется всякий раз, когда порядок работ переосмыслен.
        if packet:
            по_id = {o["id"]: o for o in packet["objects"]}
            # патч контента: в сиде появилась задача, которой на полке нет
            # (разделение задачи 1 Phase A на «Развёртывание» и «SEMP»)
            на_полке = {d.get("id") for d in rows}
            if any(i not in на_полке for i in по_id):
                return True
            for d in rows:
                сид = по_id.get(d.get("id"))
                if not сид:
                    continue
                if d.get("depends_on", []) != сид.get("depends_on", []):
                    return True
                шаги_полки = [s.get("after", []) for s in d.get("steps", [])]
                шаги_сида = [s.get("after", []) for s in сид.get("steps", [])]
                if шаги_полки != шаги_сида:
                    return True
        return not any(len(d.get("steps", [])) >= 4 for d in rows)
    if type_ == "document_template":
        # ред. 2 SEMP (ШАБЛОН-SEMP v2): режим раздела — поле шаблона, разделов
        # 11. Полка без режимов либо с восемью разделами — старее сида.
        if packet is not None:
            по_id = {o["id"]: o for o in packet["objects"]}
            for d in rows:
                сид = по_id.get(d.get("id"))
                if сид and len(d.get("sections", [])) != len(сид.get("sections", [])):
                    return True
                if сид and any("mode" in s for s in сид.get("sections", [])) and \
                        not any("mode" in s for s in d.get("sections", [])):
                    return True
        # полка устарела, если раздел «Обозначения источников» не несёт
        # авторской семантики меток: проверяем НАЛИЧИЕ верной формулировки,
        # а не отсутствие прежней — так детектор не хранит старую ошибку
        return not any(
            "внешний источник, проверенный" in s.get("expects", "")
            for d in rows for s in d.get("sections", [])
        )
    return False


def коллизии_глоссария(packet, rows) -> list:
    """Термины, которые уже есть на полке под другим определением.

    Владелец: «наш термин главнее, SEH примечанием». Тихая перезапись
    подменила бы принятое определение чужим — поэтому коллизии
    докладываются списком и остаются решением человека.
    """
    свои = {}
    for d in rows:
        for e in ([d] if "term" in d else d.get("entries", [])):
            if e.get("term"):
                свои[e["term"]] = e.get("brief", "")
    спорные = []
    for obj in packet["objects"]:
        for e in obj.get("entries", []):
            прежнее = свои.get(e.get("term"))
            if прежнее is not None and прежнее.strip() != e.get("brief", "").strip():
                спорные.append((e["term"], прежнее, e.get("brief", "")))
    return спорные


for type_, view, fname in SEEDS:
    packet = json.loads((PACKETS / fname).read_text())
    rows = present(view, packet)
    if type_ == "glossary":
        # наш термин главнее: спорные показываем владельцу, не перезаписываем
        все = present("/library/glossary", packet) or []
        спорные = коллизии_глоссария(packet, [{"entries": все}] if все else [])
        if спорные:
            print(f"glossary: КОЛЛИЗИИ ТЕРМИНОВ — {len(спорные)}; заимствованное определение "
                  f"НЕ подменяет принятое, решение за владельцем:")
            for термин, прежнее, чужое in спорные:
                print(f"  · {термин}")
                print(f"      наше:  {прежнее[:90]}")
                print(f"      SEH:   {чужое[:90]}")
    if rows and not obsolete(type_, rows, packet):
        print(f"{type_}: уже на полке — пропуск")
        continue
    if rows:
        # правка полки — новой версией объекта, не пересозданием
        for obj in packet["objects"]:
            try:
                cur = call("GET", f"/objects/{obj['id']}")
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise
                # объекта на полке ещё нет — это новая задача патча, а не правка
                out = call("POST", "/library/objects", {"type": type_, "doc": obj, "author": packet["author"]})
                print(f"{type_}: залит {out['id']} — новый объект пакета")
                continue
            changes = {k: v for k, v in obj.items() if k not in ("id", "lifecycle")}
            call("PATCH", f"/edit/{obj['id']}", {
                "author": packet["author"], "base_version": cur["version"], "changes": changes,
            })
            print(f"{type_}: обновлён {obj['id']} — полка была старее сида")
        continue
    for obj in packet["objects"]:
        out = call("POST", "/library/objects", {"type": type_, "doc": obj, "author": packet["author"]})
        print(f"{type_}: залит {out['id']}")
