#!/usr/bin/env python3
"""Сравнение постановки проекта с эталоном по разделам (ПМИ-8, мера КТ1 · 2).

    python3 tools/compare_formulation.py PJ-ПМИ7 docs/tz/v2/фикстуры/постановка-из-записки-nm.json
    python3 tools/compare_formulation.py PJ-ПМИ8 … --base http://localhost:8031   # стенд 216 через туннель
    python3 tools/compare_formulation.py --nm docs/tz/v2/фикстуры/постановка-из-записки.json
    python3 tools/compare_formulation.py --selftest

Разделы: стороны · нужды · цели · сервисы · ограничения · вехи · риски ·
требования-кандидаты. Сопоставление в два прохода:

1. **Ключ идентичности истины** — то же ядро, каким сверка узнаёт дубль
   (`IdentityKeys.statementCore` · `nameNormalized`): регистр и «ё» сняты,
   служебные слова убраны, числа с единицей ушли к величине, слова сведены к
   основам и отсортированы; написания словаря проекта (синонимы) сведены к
   канону термина — «Минтранс» и «Минтранс России» одна сторона.
   Формулировка эталона «заголовок: пояснение» узнаётся и по заголовку — в
   постановку ложится короткая формулировка («единое оперативное управление
   транспортом»), пояснение живёт у нужды основанием.
2. **Лексика с порогом** — для оставшихся: сходство основ — наибольшее из
   доли общих (Жаккар) по всей формулировке и по заголовку и вхождения
   короткой в длинную (общих основ не меньше трёх). Пара выше порога
   засчитывается, пара между нижним порогом и порогом — СПОРНАЯ: она идёт
   отдельным списком владельцу на глаз и сама не засчитывается.

Вывод: таблица `раздел · эталон · найдено · охват % · лишнее · пары · не
найдено`, затем пары «эталон ↔ проект», не найденное, лишнее и спорные пары
поимённо. Данные проекта — маршрутами v2 (`/api/v2/entities`, словарь —
`/api/v2/glossary`); вход — `tools/v2/stand_session.py` (учётка стенда либо
сессия Telegram через туннель).

`--nm` (эталон к n:m): нужда эталона, записанная копией на каждого носителя
(эталон писался до шипа 4: «у нужды ровно один носитель»), сводится в одну
нужду со списком носителей — тем же ключом ядра формулировки, каким
миграция `NeedOwnersMigration` свела копии ПМИ-7 (33 → 29). Файл кладётся
рядом с эталоном: `…-nm.json`.
"""
from __future__ import annotations

import argparse
import http.cookiejar
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent

# --- ядро формулировки: зеркало IdentityKeys (core/v2/knowledge) -------------

СЛУЖЕБНЫЕ = {
    "необходимо", "необходима", "необходим", "необходимы",
    "требуется", "требуются", "требуемый",
    "должен", "должна", "должно", "должны", "быть",
    "обеспечить", "обеспечен", "обеспечена", "обеспечено", "обеспечены", "обеспечивается",
    "нужен", "нужна", "нужно", "нужны", "следует", "предусмотреть", "иметь",
    "в", "во", "на", "для", "с", "со", "по", "от", "из", "за", "к", "ко", "о", "об", "при",
    "и", "а", "или", "что", "как", "у", "же", "это", "том", "чтобы",
}
ОРГФОРМЫ = {"ао", "оао", "зао", "пао", "нао", "ооо", "фгуп", "гуп", "муп", "фгбу", "фгау", "фку", "фгбну", "ано", "нко"}
ОКОНЧАНИЯ = sorted([
    "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ия", "ии", "ию",
    "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий", "ой", "ей", "ем", "ом",
    "ах", "ях", "ов", "ев", "ью",
    "у", "ю", "а", "я", "ы", "и", "о", "е", "ь",
], key=len, reverse=True)


def плоско(текст: str) -> str:
    """Регистр, «ё» и пунктуация ключа не меняют (IdentityKeys.плоско)."""
    т = (текст or "").lower().replace("ё", "е")
    т = "".join(с if (с.isalnum() or с == "_") else " " for с in т)
    return re.sub(r"\s+", " ", т).strip()


def основа(слово: str) -> str:
    if len(слово) < 4 or "_" in слово or any(с.isdigit() for с in слово):
        return слово
    for о in ОКОНЧАНИЯ:
        if слово.endswith(о) and len(слово) - len(о) >= 3:
            return слово[: -len(о)]
    return слово


def без_величин(слова: list[str]) -> list[str]:
    """Число и короткая единица за ним — величина, а не ядро («2–4 КА»)."""
    итог, после_числа = [], False
    for с in слова:
        if с.isdigit():
            после_числа = True
            continue
        единица = после_числа and len(с) <= 3
        после_числа = False
        if not единица:
            итог.append(с)
    return итог


class Словарь:
    """Написание термина проекта → канон (синонимы словаря); многословные — фразами."""

    def __init__(self, термины: list[dict] | None = None) -> None:
        self.канон: dict[str, str] = {}
        for т in термины or []:
            if т.get("status") not in (None, "accepted"):
                continue
            канон = плоско(т.get("term_ru", ""))
            if not канон:
                continue
            for написание in [т.get("term_ru", "")] + list(т.get("synonyms") or []):
                к = плоско(написание)
                if к and к not in self.канон:
                    self.канон[к] = канон
        self.фразы = sorted(((к, в.replace(" ", "_")) for к, в in self.канон.items() if " " in к), key=lambda п: -len(п[0]))

    def основы(self, текст: str) -> list[str]:
        строка = " " + плоско(текст) + " "
        for написание, канон in self.фразы:
            строка = строка.replace(f" {написание} ", f" {канон} ")
        слова = [self.канон.get(с, с) for с in без_величин(строка.split())]
        return sorted({основа(с) for с in слова if с not in СЛУЖЕБНЫЕ})

    def ядро(self, текст: str) -> str:
        return " ".join(self.основы(текст))

    def имя(self, имя: str) -> str:
        """Имя стороны: оргформа снята, синоним сведён к канону (nameNormalized → ядро)."""
        строка = " ".join(с for с in плоско(имя).split() if с not in ОРГФОРМЫ)
        return self.ядро(self.канон.get(строка, строка))


# --- разделы: что сравнивается в эталоне и в проекте -------------------------

РАЗДЕЛЫ = [
    # (раздел, ключ эталона, поле эталона, вид проекта, поле проекта, имя ли)
    ("стороны", "stakeholders", "name", "stakeholder", "name", True),
    ("нужды", "needs", "statement", "need", "statement", False),
    ("цели", "goals", "statement", "goal", "statement", False),
    ("сервисы", "services", "name", "service", "name", False),
    ("ограничения", "constraints", "statement", "constraint", "statement", False),
    ("вехи", "stages", "name", "milestone", "name", False),
    ("риски", "risks", "statement", "risk", "statement", False),
    ("требования-кандидаты", "requirements", "statement", "requirement", "statement", False),
]


def текст_записи(doc: dict, поле: str) -> str:
    """Текст записи проекта: поле раздела, для требования — формулировка либо заголовок."""
    значение = doc.get(поле) or (doc.get("title") if поле == "statement" else None) or doc.get("name") or ""
    return значение if isinstance(значение, str) else json.dumps(значение, ensure_ascii=False)


def жаккар(а: set[str], б: set[str]) -> float:
    return len(а & б) / len(а | б) if а and б else 0.0


def заголовок(текст: str) -> str:
    """«Заголовок: пояснение» — заголовок; без двоеточия (или заголовок в одно слово) — весь текст."""
    голова = (текст or "").split(":", 1)[0].strip()
    return голова if ":" in (текст or "") and len(голова.split()) >= 2 else (текст or "")


def сходство(эталон: str, проект: str, ядро) -> float:
    """Наибольшее из: Жаккар по всей формулировке, Жаккар по заголовку, вхождение короткой в длинную (≥ 3 общих основ)."""
    э, г, п = set(ядро(эталон).split()), set(ядро(заголовок(эталон)).split()), set(ядро(проект).split())
    общих = len(э & п)
    вхождение = общих / min(len(э), len(п)) if общих >= 3 else 0.0
    return max(жаккар(э, п), жаккар(г, п), вхождение)


def сопоставить(эталон: list[str], проект: list[str], словарь: Словарь, имя: bool, порог: float, нижний: float) -> dict:
    """Два прохода: ядро ключа, затем лексика с порогом; спорные — отдельно, не засчитываются."""
    ядро = словарь.имя if имя else словарь.ядро
    ключи_э = [ядро(т) for т in эталон]
    головы_э = [ядро(заголовок(т)) for т in эталон]
    ключи_п = [ядро(т) for т in проект]
    пары: list[tuple[int, int, str]] = []
    занято_п: set[int] = set()
    # Первый проход — ключ: вся формулировка, затем заголовок эталонной.
    for ключи, как in ((ключи_э, "ключ"), (головы_э, "ключ по заголовку")):
        for i, к in enumerate(ключи):
            if any(пi == i for пi, _, _ in пары):
                continue
            for j, кп in enumerate(ключи_п):
                if j not in занято_п and к and к == кп:
                    пары.append((i, j, как))
                    занято_п.add(j)
                    break
    найдено_э = {i for i, _, _ in пары}
    # Второй проход — жадно по убыванию сходства: лучшая пара берётся первой.
    кандидаты = sorted(
        ((сходство(эталон[i], проект[j], ядро), i, j)
         for i in range(len(эталон)) if i not in найдено_э
         for j in range(len(проект)) if j not in занято_п),
        reverse=True,
    )
    спорные: list[tuple[int, int, float]] = []
    for сх, i, j in кандидаты:
        if i in найдено_э or j in занято_п or сх < нижний:
            continue
        if сх >= порог:
            пары.append((i, j, f"лексика {сх:.2f}"))
            найдено_э.add(i)
            занято_п.add(j)
    for сх, i, j in кандидаты:
        if i not in найдено_э and j not in занято_п and нижний <= сх < порог:
            if all(с_i != i for с_i, _, _ in спорные):
                спорные.append((i, j, сх))
    return {
        "пары": пары,
        "не_найдено": [i for i in range(len(эталон)) if i not in найдено_э],
        "лишнее": [j for j in range(len(проект)) if j not in занято_п],
        "спорные": спорные,
    }


# --- эталон к n:m ------------------------------------------------------------

def к_nm(эталон: dict) -> dict:
    """Копии нужды по носителям — одна нужда со списком носителей (ключ — ядро формулировки)."""
    словарь = Словарь()
    сведённые: dict[str, dict] = {}
    for н in эталон.get("needs", []):
        ключ = словарь.ядро(н.get("statement", ""))
        носитель = н.get("stakeholder")
        if ключ in сведённые:
            была = сведённые[ключ]
            if носитель and носитель not in была["stakeholders"]:
                была["stakeholders"].append(носитель)
            была["shared"] = len(была["stakeholders"]) > 1
            continue
        новая = {к: в for к, в in н.items() if к != "stakeholder"}
        новая["stakeholders"] = [носитель] if носитель else []
        сведённые[ключ] = новая
    итог = dict(эталон)
    итог["needs"] = list(сведённые.values())
    итог["summary"] = dict(эталон.get("summary") or {}, needs=len(итог["needs"]))
    итог["migration"] = (
        f"нужды к n:m: {len(эталон.get('needs', []))} → {len(итог['needs'])} — копии одной формулировки по носителям "
        "сведены в одну нужду со списком stakeholders тем же ключом ядра формулировки, что миграция "
        "NeedOwnersMigration на ПМИ-7 (33 → 29); прочие разделы без изменений"
    )
    return итог


# --- данные проекта маршрутами v2 -----------------------------------------------

def открыть(base: str, login: str):
    sys.path.insert(0, str(КОРЕНЬ / "tools/v2"))
    import stand_session  # noqa: PLC0415

    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    stand_session.войти(base + "/api", opener, login)
    return opener


def прочитать(opener, base: str, путь: str) -> dict:
    with opener.open(base + "/api/v2" + путь, timeout=120) as о:
        return json.loads(о.read().decode())


def данные_проекта(opener, base: str, проект: str) -> tuple[dict[str, list[str]], Словарь]:
    к = urllib.parse.quote(проект)
    записи = {}
    for _раздел, _ключ, _поле, вид, поле, _имя in РАЗДЕЛЫ:
        items = прочитать(opener, base, f"/entities?project={к}&kind={вид}").get("items", [])
        записи[вид] = [текст_записи(з.get("doc") or {}, поле) for з in items if з.get("status") != "cancelled"]
    словарь = Словарь(прочитать(opener, base, f"/glossary?project={к}").get("items", []))
    return записи, словарь


# --- отчёт -------------------------------------------------------------------

def сравнить(эталон: dict, записи: dict[str, list[str]], словарь: Словарь, порог: float, нижний: float) -> list[dict]:
    итоги = []
    for раздел, ключ, поле, вид, _поле_п, имя in РАЗДЕЛЫ:
        э = [(з.get(поле) or з.get("title") or "") for з in эталон.get(ключ, [])]
        п = записи.get(вид, [])
        р = сопоставить(э, п, словарь, имя, порог, нижний)
        итоги.append({"раздел": раздел, "эталон": э, "проект": п, **р})
    return итоги


def таблица(итоги: list[dict], проект: str, файл: str) -> str:
    строки = [
        f"# Сравнение постановки `{проект}` с эталоном `{файл}`", "",
        "| раздел | эталон | найдено | охват % | лишнее | пары | не найдено |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for и in итоги:
        всего = len(и["эталон"])
        найдено = всего - len(и["не_найдено"])
        охват = f"{100 * найдено / всего:.0f}" if всего else "—"
        строки.append(f"| {и['раздел']} | {всего} | {найдено} | {охват} | {len(и['лишнее'])} | {len(и['пары'])} | {len(и['не_найдено'])} |")
    for и in итоги:
        строки += ["", f"## {и['раздел']}", ""]
        for i, j, как in и["пары"]:
            строки.append(f"- ✓ «{и['эталон'][i][:90]}» ↔ «{и['проект'][j][:90]}» ({как})")
        for i in и["не_найдено"]:
            строки.append(f"- не найдено: «{и['эталон'][i][:110]}»")
        for j in и["лишнее"]:
            строки.append(f"- лишнее: «{и['проект'][j][:110]}»")
        for i, j, сх in и["спорные"]:
            строки.append(f"- **спорная пара** (не засчитана, {сх:.2f}): «{и['эталон'][i][:80]}» ↔ «{и['проект'][j][:80]}»")
    return "\n".join(строки) + "\n"


def самопроверка() -> None:
    словарь = Словарь([{"term_ru": "Минтранс России", "synonyms": ["Минтранс", "Министерство транспорта РФ"], "status": "accepted"}])
    # Ключ: оргформа, кавычки и синоним словаря не различают сторону.
    assert словарь.имя("АО «ГЛОНАСС»") == словарь.имя("ГЛОНАСС"), "оргформа не снята"
    assert словарь.имя("Минтранс") == словарь.имя("Минтранс России"), "синоним словаря не сведён к канону"
    # Порядок слов и служебные слова ядро не меняют.
    assert словарь.ядро("нужна связь в Арктике") == словарь.ядро("в Арктике нужна связь"), "порядок слов меняет ядро"
    р = сопоставить(
        ["Единое оперативное управление транспортом", "Малое энергопотребление терминала", "Суверенитет данных: российское хранение"],
        ["единое оперативное управление транспортом", "низкое энергопотребление массового терминала", "шум постороннего документа"],
        словарь, False, 0.5, 0.25,
    )
    assert (0, 0, "ключ") in р["пары"], f"пара по ключу не найдена: {р}"
    assert any(i == 1 and j == 1 for i, j, _ in р["пары"]) or any(i == 1 for i, _, _ in р["спорные"]), f"лексика не сработала: {р}"
    assert 2 in р["не_найдено"] and 2 in р["лишнее"], f"не найденное и лишнее не названы: {р}"
    assert all(i != j or как for i, j, как in р["пары"])
    # Заголовок эталонной формулировки узнаётся ключом: в постановку ложится короткая.
    з = сопоставить(["Единое оперативное управление транспортом: непрерывное поступление данных, а не витрина"],
                    ["единое оперативное управление транспортом"], словарь, False, 0.5, 0.3)
    assert з["пары"] == [(0, 0, "ключ по заголовку")], f"заголовок не узнан: {з}"
    # Эталон к n:m: три копии одной нужды — одна нужда с тремя носителями.
    nm = к_nm({"needs": [
        {"statement": "Единое управление", "stakeholder": "А", "mark": "И"},
        {"statement": "единое  управление", "stakeholder": "Б", "mark": "И"},
        {"statement": "Единое управление.", "stakeholder": "В", "mark": "И"},
        {"statement": "Своя нужда", "stakeholder": "А", "mark": "П"},
    ], "summary": {"needs": 4}})
    assert len(nm["needs"]) == 2 and nm["needs"][0]["stakeholders"] == ["А", "Б", "В"], nm
    assert nm["needs"][0]["shared"] is True and "stakeholder" not in nm["needs"][0] and nm["summary"]["needs"] == 2, nm


def main() -> int:
    п = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    п.add_argument("project", nargs="?")
    п.add_argument("etalon", nargs="?")
    п.add_argument("--base", default="http://localhost:8080", help="стенд: :8080 — локальная копия, туннель к 216 — свой порт")
    п.add_argument("--login", default="chernov")
    п.add_argument("--threshold", type=float, default=0.5, help="порог лексики: пара засчитывается")
    п.add_argument("--disputed", type=float, default=0.3, help="нижний порог: пара спорная — владельцу на глаз")
    п.add_argument("--out", default=None, help="куда записать таблицу (markdown); иначе — в вывод")
    п.add_argument("--nm", default=None, metavar="ЭТАЛОН", help="эталон к n:m: записать …-nm.json рядом")
    п.add_argument("--selftest", action="store_true")
    args = п.parse_args()
    if args.selftest:
        самопроверка()
        print("сравнение постановки: самопроверка пройдена (ключ, словарь, лексика, спорные, n:m)")
        return 0
    if args.nm:
        путь = pathlib.Path(args.nm)
        эталон = json.loads(путь.read_text(encoding="utf-8"))
        итог = к_nm(эталон)
        куда = путь.with_name(путь.stem + "-nm.json")
        куда.write_text(json.dumps(итог, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{куда}: {итог['migration']}")
        return 0
    if not args.project or not args.etalon:
        п.error("нужны проект и эталон (или --nm, --selftest)")
    самопроверка()
    эталон = json.loads(pathlib.Path(args.etalon).read_text(encoding="utf-8"))
    opener = открыть(args.base, args.login)
    записи, словарь = данные_проекта(opener, args.base, args.project)
    отчёт = таблица(сравнить(эталон, записи, словарь, args.threshold, args.disputed), args.project, args.etalon)
    if args.out:
        pathlib.Path(args.out).write_text(отчёт, encoding="utf-8")
        print(f"таблица: {args.out}")
    print(отчёт.split("\n\n## ")[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
