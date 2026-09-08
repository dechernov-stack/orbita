#!/usr/bin/env python3
"""Прогон проекта по сценам 1–12 — только каналами продукта.

Зачем: мера шипа B — «§5–§8 отчёта полны ПОСЛЕ сцен 10–12». Проверить её
можно лишь на проекте, который эти сцены прожил. Ходить по сценам руками
каждый раз — потерять полдня и не суметь повторить, поэтому прогон
собирается скриптом. Скрипт НЕ пишет в базу и не знает внутренностей: он
дёргает те же маршруты, что нажимает человек, — иначе прогон доказывал бы
работоспособность скрипта, а не продукта.

Содержание живёт в сиде `docs/tz/v2/сиды/ПРОГОН-СЦЕН-1-12.json`, а не в
коде: числа прогона — это данные, и править их должно быть можно, не
трогая программу.

Идемпотентно: то, что уже заведено, повторно не заводится, поэтому прогон
можно догонять после правки сида.

    python3 tools/v2/walk_scenes.py                    # прогон по умолчанию
    python3 tools/v2/walk_scenes.py --project PJ-V2-W4
    python3 tools/v2/walk_scenes.py --report           # только состояние
"""
from __future__ import annotations

import argparse
import http.cookiejar
import json
import pathlib
import sys
import urllib.error
import urllib.request

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
СИД = КОРЕНЬ / "docs/tz/v2/сиды/ПРОГОН-СЦЕН-1-12.json"

_opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
)


class Отказ(Exception):
    """Отказ стенда: он несёт причину словами — её и показываем."""


def вызов(base: str, метод: str, путь: str, тело=None):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    запрос = urllib.request.Request(
        base + путь, data=данные,
        headers={"Content-Type": "application/json; charset=utf-8"}, method=метод,
    )
    try:
        with _opener.open(запрос, timeout=180) as ответ:
            сырое = ответ.read().decode()
            return json.loads(сырое) if сырое else {}
    except urllib.error.HTTPError as e:
        текст = e.read().decode()
        try:
            причина = json.loads(текст).get("error", текст)
        except json.JSONDecodeError:
            причина = текст
        raise Отказ(f"{метод} {путь} → {e.code}: {причина[:300]}") from None


class Прогон:
    def __init__(self, base: str, проект: str, сид: dict):
        self.base = base
        self.проект = проект
        self.сид = сид
        self.сделано: list[str] = []
        self.пропущено: list[str] = []

    # --- служебное --------------------------------------------------------

    def войти(self, учётка: str) -> None:
        вызов(self.base, "POST", "/auth/stand-login", {"login": учётка})

    def сущности(self, вид: str) -> list[dict]:
        ответ = вызов(self.base, "GET", f"/v2/entities?project={self.проект}&kind={вид}")
        return ответ.get("items", [])

    def код_по_имени(self, вид: str, имя: str) -> str | None:
        for э in self.сущности(вид):
            док = э.get("doc") or {}
            if имя in (док.get("name"), док.get("statement"), док.get("text"), э.get("code")):
                return э["code"]
        return None

    def шаг(self, что: str, есть: bool, действие) -> None:
        """Шаг прогона: уже сделанное не переделывается, отказ не глотается."""
        if есть:
            self.пропущено.append(что)
            return
        действие()
        self.сделано.append(что)

    # --- сцены ------------------------------------------------------------

    def сцена_1_проект(self) -> None:
        портфель = вызов(self.base, "GET", "/v2/projects").get("items", [])
        есть = any(п.get("code") == self.проект for п in портфель)
        self.шаг(
            f"сцена 1: проект {self.проект}", есть,
            lambda: вызов(self.base, "POST", "/v2/projects",
                          {"name": self.сид["name"], "code": self.проект}),
        )

    def сцена_2_замысел(self) -> None:
        self.шаг(
            "сцена 2: замысел", bool(self.сущности("intent")),
            lambda: вызов(self.base, "POST", f"/v2/intent?project={self.проект}",
                          {**self.сид["intent"], "author": "Чернов Д."}),
        )

    def сцена_3_стороны_и_нужды(self) -> None:
        for сторона in self.сид["stakeholders"]:
            self.шаг(
                f"сцена 3: сторона «{сторона['name']}»",
                self.код_по_имени("stakeholder", сторона["name"]) is not None,
                lambda с=сторона: вызов(self.base, "POST", f"/v2/stakeholders?project={self.проект}",
                                        {**с, "author": "Иванов И."}),
            )
        for нужда in self.сид["needs"]:
            носитель = self.код_по_имени("stakeholder", нужда["owner"])
            if носитель is None:
                raise Отказ(f"носителя «{нужда['owner']}» нет: сначала стороны")
            self.шаг(
                f"сцена 3: нужда «{нужда['statement'][:40]}…»",
                self.код_по_имени("need", нужда["statement"]) is not None,
                lambda н=нужда, к=носитель: вызов(
                    self.base, "POST", f"/v2/needs?project={self.проект}",
                    {"statement": н["statement"], "owner": к, "author": "Иванов И."}),
            )

    def сцена_4_цели(self) -> None:
        нужды = [н["code"] for н in self.сущности("need")]
        for цель in self.сид["goals"]:
            self.шаг(
                f"сцена 4: цель «{цель['statement'][:40]}…»",
                self.код_по_имени("goal", цель["statement"]) is not None,
                lambda ц=цель: вызов(self.base, "POST", f"/v2/goals?project={self.проект}",
                                     {**ц, "covers": нужды, "author": "Иванов И."}),
            )

    def сцена_5_ограничения(self) -> None:
        for о in self.сид["constraints"]:
            self.шаг(
                f"сцена 5: ограничение «{о['text'][:40]}…»",
                self.код_по_имени("constraint", о["text"]) is not None,
                lambda г=о: вызов(self.base, "POST", f"/v2/constraints?project={self.проект}",
                                  {**г, "author": "Иванов И."}),
            )

    def сцена_6_сервисы(self) -> None:
        нужды = [н["code"] for н in self.сущности("need")]
        for с in self.сид["services"]:
            self.шаг(
                f"сцена 6: сервис «{с['name']}»",
                self.код_по_имени("service", с["name"]) is not None,
                lambda в=с: вызов(self.base, "POST", f"/v2/services?project={self.проект}",
                                  {**в, "covers": нужды, "author": "Иванов И."}),
            )

    def сцена_7_состав_и_вариант(self) -> None:
        имеются = {у["code"] for у in self.сущности("component")}
        for узел in self.сид["components"]:
            self.шаг(
                f"сцена 7: узел {узел['code']}", узел["code"] in имеются,
                lambda у=узел: вызов(self.base, "POST", f"/v2/components?project={self.проект}",
                                     {**у, "author": "Иванов И."}),
            )
        for р in self.сид.get("deploy", []):
            self.шаг(
                f"сцена 7: {р['behaviour']} на {р['node']}", False,
                lambda д=р: вызов(self.base, "POST", f"/v2/components/deploy?project={self.проект}",
                                  {**д, "author": "Иванов И."}),
            )
        # Пороги — до вариантов: без порога вариант не с чем сравнивать.
        for к in self.сид.get("criteria", []):
            self.шаг(
                f"сцена 7: порог «{к['title']}»",
                self.код_по_имени("criterion", к["title"]) is not None,
                lambda п=к: вызов(self.base, "POST", f"/v2/criteria?project={self.проект}",
                                  {**п, "author": "Иванов И."}),
            )
        имеются = {в["code"] for в in self.сущности("constellation_variant")}
        for в in self.сид.get("variants", []):
            self.шаг(
                f"сцена 7: вариант {в['code']}", в["code"] in имеются,
                lambda х=в: вызов(self.base, "POST", f"/v2/variants?project={self.проект}",
                                  {**х, "author": "Иванов И."}),
            )
        концепции = вызов(self.base, "GET", f"/v2/concept?project={self.проект}").get("items", [])
        self.шаг(
            "сцена 7: базовый вариант", bool(концепции),
            lambda: вызов(self.base, "POST", f"/v2/concept?project={self.проект}",
                          {**self.сид["concept"], "author": "Чернов Д."}),
        )

    def сцена_8_требования_и_анкеты(self) -> None:
        цели = self.сущности("goal")
        источник = [{"kind": "goal", "ref": цели[0]["id"]}] if цели else []
        имеются = {т["code"] for т in self.сущности("requirement")}
        for т in self.сид["requirements"]:
            self.шаг(
                f"сцена 8: требование {т['code']}", т["code"] in имеются,
                lambda р=т: вызов(self.base, "POST", f"/v2/requirements?project={self.проект}",
                                  {**р, "source": источник, "author": "Иванов И."}),
            )
        имеются = {п["code"] for п in self.сущности("parameter")}
        for п in self.сид.get("parameters", []):
            код = f"{п['component']}.{п['key']}"
            self.шаг(
                f"сцена 8: анкета {код}", код in имеются,
                lambda а=п: вызов(
                    self.base, "POST", f"/v2/parameters?project={self.проект}",
                    {
                        "target": а["component"], "key": а["key"],
                        "measure": {"value": а["value"], "unit": а["unit"]},
                        "maturity_class": а["maturity"], "origin": "прогон волны 4",
                        "required_to": "MCR", "author": "Иванов И.",
                    }),
            )

    def сцена_9_режимы_и_сценарии(self) -> None:
        режимы = self.сид.get("modes")
        if режимы:
            есть = вызов(self.base, "GET", f"/v2/modes?project={self.проект}").get("items", [])
            self.шаг(
                "сцена 9: режимы аппарата", bool(есть),
                lambda: вызов(self.base, "POST", f"/v2/modes?project={self.проект}",
                              {**режимы, "author": "Иванов И."}),
            )
        имеются = {с["code"] for с in вызов(
            self.base, "GET", f"/v2/scenarios?project={self.проект}").get("items", [])}
        for с in self.сид.get("scenarios", []):
            self.шаг(
                f"сцена 9: сценарий «{с['name'][:35]}…»", с["code"] in имеются,
                lambda х=с: вызов(self.base, "POST", f"/v2/scenarios?project={self.проект}",
                                  {**х, "author": "Иванов И."}),
            )

    def сцена_10_технологии(self) -> None:
        имеются = {т["name"] for т in вызов(
            self.base, "GET", f"/v2/technologies?project={self.проект}").get("items", [])}
        for т in self.сид["technologies"]:
            self.шаг(
                f"сцена 10: технология «{т['name'][:35]}…»", т["name"] in имеются,
                lambda х=т: вызов(self.base, "POST", f"/v2/technologies?project={self.проект}",
                                  {**х, "author": "Иванов И."}),
            )

    def сцена_11_риски_и_засорение(self) -> None:
        имеются = {р["statement"] for р in вызов(
            self.base, "GET", f"/v2/risks?project={self.проект}").get("items", [])}
        for р in self.сид["risks"]:
            self.шаг(
                f"сцена 11: риск «{р['statement'][:35]}…»", р["statement"] in имеются,
                lambda х=р: вызов(self.base, "POST", f"/v2/risks?project={self.проект}",
                                  {**х, "author": "Иванов И."}),
            )
        осз = вызов(self.base, "GET", f"/v2/oda?project={self.проект}").get("items", [])
        for о in self.сид["oda"]:
            self.шаг(
                f"сцена 11: ОСЗ варианта «{о['variant'][:30]}…»", bool(осз),
                lambda х=о: вызов(self.base, "POST", f"/v2/oda?project={self.проект}",
                                  {**х, "author": "Иванов И."}),
            )

    def вехи_созревания(self) -> None:
        """Даты вех технологий: без них точка не учтёт сроки рисков."""
        for в in self.сид.get("milestones", []):
            self.шаг(
                f"сцена 10: дата вехи {в['gate']}", False,
                lambda х=в: вызов(self.base, "POST", f"/v2/gates/{х['gate']}/date?project={self.проект}",
                                  {"date": х["date"], "author": "Иванов И."}),
            )

    def сцена_12_стоимость(self) -> None:
        взято = вызов(self.base, "POST", f"/v2/wbs/take?project={self.проект}",
                      {"author": "Чернов Д."}).get("taken", 0)
        (self.сделано if взято else self.пропущено).append(
            f"сцена 12: пакеты работ с полки (взято {взято})")
        пакеты = вызов(self.base, "GET", f"/v2/wbs?project={self.проект}").get("items", [])
        коды = [п["code"] for п in пакеты]
        оценены = {п["code"] for п in пакеты if п.get("estimate")}
        for о in self.сид["estimates"]:
            пакет = о["package"]
            if пакет not in коды:
                self.пропущено.append(
                    f"сцена 12: оценка {пакет} — такого пакета в проекте нет")
                continue
            if пакет in оценены:
                self.пропущено.append(f"сцена 12: оценка {пакет}")
                continue
            вызов(self.base, "POST", f"/v2/wbs/estimate?project={self.проект}",
                  {**о, "package": пакет, "author": "Чернов Д."})
            оценены.add(пакет)
            self.сделано.append(f"сцена 12: оценка {пакет}")

        # Пакеты созревания родила сцена 10, их коды сиду заранее неизвестны.
        # Без их оценки выход сцены 12 честно держит проект: стоимость без
        # созревания считает не тот объём работ.
        правило = self.сид.get("maturation_estimate")
        if правило:
            for п in пакеты:
                код = п["code"]
                if "TECH-" not in код or код in оценены:
                    continue
                вызов(self.base, "POST", f"/v2/wbs/estimate?project={self.проект}",
                      {**{к: в for к, в in правило.items() if к != "note"},
                       "package": код, "author": "Чернов Д."})
                оценены.add(код)
                self.сделано.append(f"сцена 12: оценка созревания {код}")

    def пройти(self) -> None:
        self.войти("chernov")
        self.сцена_1_проект()
        self.сцена_2_замысел()
        self.войти("ivanov")
        self.сцена_3_стороны_и_нужды()
        self.сцена_4_цели()
        self.сцена_5_ограничения()
        self.сцена_6_сервисы()
        self.сцена_7_состав_и_вариант()
        self.сцена_8_требования_и_анкеты()
        self.сцена_9_режимы_и_сценарии()
        self.сцена_10_технологии()
        self.сцена_11_риски_и_засорение()
        self.вехи_созревания()
        self.войти("chernov")
        self.сцена_12_стоимость()


def состояние(base: str, проект: str) -> None:
    """Что получилось: сцены, свёртка с рамкой и полнота разделов отчёта."""
    фаза = вызов(base, "GET", f"/v2/phase?project={проект}")
    print("  сцены:", " ".join(f"{с['key']}:{с['state'][:4]}" for с in фаза["scenes"]))

    свёртка = вызов(base, "GET", f"/v2/budget?project={проект}&kind=mass&gate=MCR")
    print(f"  свёртка массы: сумма {свёртка['sum']} {свёртка['unit']}, "
          f"с резервом класса {свёртка['with_class_reserve']} {свёртка['unit']}")
    for р in свёртка.get("frame", []):
        print(f"    рамка: {р['words']}{'' if р['within'] else '  ← перебор'}")

    документы = вызов(base, "GET", f"/v2/documents?project={проект}").get("items", [])
    for д in документы:
        вид = вызов(base, "GET", f"/v2/documents/{д['code']}?project={проект}")
        print(f"  {вид['title']}: полнота к {вид.get('gate','MCR')} — "
              f"{вид['complete']} из {вид['total']}"
              + (f", не ждут ещё {вид['not_due_yet']}" if вид.get("not_due_yet") else ""))
        for раздел in вид["sections"]:
            строк = sum(len(э.get("rows", [])) for э in раздел.get("elements", []))
            полон = "полон" if раздел.get("complete") else "неполон"
            ждут = "" if раздел.get("due_now", True) else f"  (ждут к {раздел.get('expected_by')})"
            print(f"    §{раздел['no']:<4} {раздел['title'][:38]:<40} {полон:<8} строк {строк}{ждут}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--project", default=None, help="код проекта; по умолчанию — из сида")
    ap.add_argument("--report", action="store_true", help="ничего не делать, показать состояние")
    args = ap.parse_args()

    сид = json.loads(СИД.read_text(encoding="utf-8"))
    проект = args.project or сид["code"]

    вызов(args.base, "POST", "/auth/stand-login", {"login": "chernov"})
    if not args.report:
        прогон = Прогон(args.base, проект, сид)
        try:
            прогон.пройти()
        except Отказ as о:
            print(f"прогон остановлен: {о}", file=sys.stderr)
            for ш in прогон.сделано:
                print("  сделано:", ш, file=sys.stderr)
            return 1
        print(f"прогон {проект}: сделано {len(прогон.сделано)}, "
              f"уже было {len(прогон.пропущено)}")
        for ш in прогон.сделано:
            print("  +", ш)

    print(f"состояние {проект}:")
    состояние(args.base, проект)
    return 0


if __name__ == "__main__":
    sys.exit(main())
