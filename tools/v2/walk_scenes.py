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
import urllib.parse
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stand_session  # noqa: E402  — вход учёткой стенда либо через Telegram с ролью «от имени»

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
    def __init__(self, base: str, проект: str, сид: dict, точки: bool = False, знания: bool = False, фазаA: bool = False):
        self.точки = точки
        self.знания = знания
        self.фазаA = фазаA
        self.base = base
        self.проект = проект
        self.сид = сид
        self.сделано: list[str] = []
        self.пропущено: list[str] = []

    # --- служебное --------------------------------------------------------

    def войти(self, учётка: str) -> None:
        """Режим stand — учётка; режим telegram — одна учётка владельца, роль «от имени» (ADR-066)."""
        stand_session.войти(self.base, _opener, учётка)

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
                          # Имя с кодом, если проект не из сида: два прогона с одним
                          # именем не различить в портфеле (поймано на E1).
                          {"name": self.сид["name"] if self.проект == self.сид["code"] else f"{self.сид['name']} · {self.проект}",
                           "code": self.проект}),
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
        """
        План пакетов созревания. Дата вехи идёт ОТ НЕГО, а не ставится
        рукой: иначе две даты разъезжаются, и никто не замечает, пока срок
        не наступит (решение владельца 08.09).
        """
        for п in self.сид.get("maturation_plan", []):
            ответ = вызов(self.base, "POST", f"/v2/wbs/plan?project={self.проект}",
                          {**п, "author": "Иванов И."})
            self.сделано.append(
                f"сцена 10: план {п['package']} до {п['end']}"
                + (f" → веха {ответ['milestone']}" if ответ.get("milestone") else ""))

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

    # --- знания 2: три типа входных (шип E) -----------------------------

    def знания_2(self) -> None:
        """ТЗ · даташит · норматив — каждый своим режимом живой моделью; план
        даташита принимается (параметры в анкету, риск по рамке); допущение
        держит MCR до подтверждения."""
        настройки = self.сид.get("knowledge2", {})
        материалы = {м.get("name"): м for м in вызов(self.base, "GET", f"/v2/materials?project={self.проект}").get("items", [])}
        итоги = {}
        for тип in ("tor", "datasheet", "normative"):
            вход = настройки.get(тип)
            if not вход:
                continue
            имя = вход["name"]
            if имя in материалы:
                код = материалы[имя]["code"]
                self.пропущено.append(f"знания 2: материал «{имя}» уже есть ({код})")
            else:
                код = вызов(self.base, "POST", f"/v2/materials?project={self.проект}",
                            {"name": имя, "kind": тип, "text": вход["text"], "author": "Иванов И."})["code"]
                self.сделано.append(f"знания 2: материал «{имя}» ({тип}) положен — {код}")
            разбор = вызов(self.base, "POST", f"/v2/intake/atomize?project={self.проект}",
                           {"material": код, "intent": вход["intent"], "author": "Иванов И."})
            итоги[тип] = разбор
            self.сделано.append(f"знания 2: {тип} разобран — {разбор.get('note', '')[:110]}")
            задание = разбор.get("task")
            if not задание:
                continue
            план = вызов(self.base, "GET", f"/v2/intake/{задание}?project={self.проект}")
            if тип == "tor":
                оценка = план.get("assessment") or {}
                if not оценка:
                    raise Отказ("ТЗ разобрано без оценки против нужд — режим tor не сработал")
                self.сделано.append(f"знания 2: ТЗ оценено — строк {len(оценка.get('lines', []))}, непокрытых нужд "
                                    f"{len(оценка.get('uncovered_needs', []))}, требований без нужды {len(оценка.get('orphan_requirements', []))}")
            if тип == "datasheet":
                виды = [(д["kind"], д["target_kind"]) for д in план.get("actions", [])]
                if ("update_params", "parameter") not in виды:
                    raise Отказ(f"даташит не дал параметров анкеты: план {виды}")
                if "рассмотрим как базовую" in вход["intent"] and ("create_entity", "component") not in виды:
                    raise Отказ(f"задание «рассмотрим как базовую» не дало узла-кандидата: план {виды}")
                параметры = вызов(self.base, "GET", f"/v2/parameters?project={self.проект}").get("items", [])
                if any(п.get("origin") == "datasheet" for п in параметры) or any((у.get("doc") or {}).get("candidate") for у in self.сущности("component")):
                    self.пропущено.append("знания 2: параметры даташита уже приняты")
                else:
                    выбраны = [д["index"] for д in план["actions"] if д["target_kind"] in ("component", "parameter", "risk", "data_request")]
                    принято = вызов(self.base, "POST", f"/v2/intake/{задание}/accept?project={self.проект}",
                                    {"chosen": выбраны, "author": "Иванов И."})
                    self.сделано.append(f"знания 2: даташит принят — создано {принято.get('created')}: {', '.join(принято.get('codes', [])[:6])}")
            if тип == "normative":
                выбраны = [д["index"] for д in план.get("actions", []) if д["target_kind"] == "normative_document"]
                if выбраны:
                    принято = вызов(self.base, "POST", f"/v2/intake/{задание}/accept?project={self.проект}",
                                    {"chosen": выбраны, "author": "Иванов И."})
                    self.сделано.append(f"знания 2: норматив на полке — {', '.join(принято.get('codes', []))}")
        # риски, рождённые сверкой даташита с рамками, закрываются РЕШЕНИЕМ
        # словами (репетиция: настоящее решение — за РП); темы с принятыми
        # фактами разрешаются в сущность — иначе MCR держится ими честно.
        решения = настройки.get("risk_resolutions_by_constraint", {})
        for р in self.сущности("risk"):
            док = р.get("doc") or {}
            рамка = док.get("constraint")
            if р.get("status") != "closed" and рамка in решения:
                вызов(self.base, "POST", f"/v2/risks/{р['code']}/close?project={self.проект}",
                      {"resolution": решения[рамка], "author": "Чернов Д."})
                self.сделано.append(f"знания 2: риск {р['code']} ({рамка}) закрыт решением")
        адреса = настройки.get("topic_resolutions", [])
        норматив = next((к for к in (итоги.get("normative") or {}).get("codes", [])), None)
        полка = [н for н in вызов(self.base, "GET", "/v2/shelves?kind=normative_document").get("items", [])]
        for т in вызов(self.base, "GET", f"/v2/topics?project={self.проект}").get("items", []):
            if т.get("resolved_to") or not т.get("facts"):
                continue
            for а in адреса:
                if а["contains"] in т["label"]:
                    адрес = а.get("entity")
                    if а.get("entity_from") == "candidate":
                        адрес = next((у["code"] for у in self.сущности("component") if (у.get("doc") or {}).get("candidate")), None)
                    if а.get("entity_from") == "normative":
                        адрес = next((н["code"] for н in полка
                                      if а["contains"] in ((н.get("doc") or {}).get("designation") or (н.get("doc") or {}).get("title") or "")), None)
                    if адрес:
                        вызов(self.base, "POST", f"/v2/topics/{т['id']}/resolve?project={self.проект}",
                              {"entity": адрес, "author": "Иванов И."})
                        self.сделано.append(f"знания 2: тема {т['id']} «{т['label'][:40]}» → {адрес}")
                    break
        # допущение к MCR: держит точку, подтверждение отпускает
        д = настройки.get("assumption")
        if д:
            факты = вызов(self.base, "GET", f"/v2/facts?project={self.проект}").get("items", [])
            факт = next((ф for ф in факты if ф.get("predicate") == д["predicate"] and ф.get("manual")), None)
            if факт is None:
                факт = вызов(self.base, "POST", f"/v2/facts?project={self.проект}",
                             {"subject": д["subject"], "predicate": д["predicate"], "value": д["value"], "unit": д["unit"],
                              "kind": "quantity", "author": д["owner"]})
                self.сделано.append(f"знания 2: факт {факт['id']} заведён руками")
            if факт.get("disposition") not in ("assumed", "adopted"):
                вызов(self.base, "POST", f"/v2/facts/{факт['id']}/disposition?project={self.проект}",
                      {"disposition": "assumed", "reason": "пока не измерено", "author": д["owner"],
                       "assumption": {"owner": д["owner"], "confirm_by": д["confirm_by"],
                                      "validation": д["validation"], "impact_if_wrong": д["impact_if_wrong"]}})
                self.сделано.append(f"знания 2: {факт['id']} — допущение к {д['confirm_by']} (владелец {д['owner']})")
                точка = self.точка(д["confirm_by"])
                if not any("допущен" in б for б in точка["blocking"]):
                    raise Отказ(f"допущение не держит {д['confirm_by']}: {точка['blocking']}")
                self.сделано.append(f"знания 2: {д['confirm_by']} держится допущением — как и должно")
                вызов(self.base, "POST", f"/v2/facts/{факт['id']}/disposition?project={self.проект}",
                      {"disposition": "adopted", "reason": "замерено на стенде: 11,6 с", "author": д["owner"]})
                self.сделано.append(f"знания 2: допущение {факт['id']} подтверждено — точка отпущена")

    # --- точки: сцены 15–18 (шип D) --------------------------------------

    def точка(self, ключ: str) -> dict:
        точки = вызов(self.base, "GET", f"/v2/points?project={self.проект}")["items"]
        return next(т for т in точки if т["key"] == ключ)

    def подготовка_к_точкам(self) -> None:
        """Чего сценам 1–12 не хватало для точек: план фазы, решение по
        риску со сроком к MCR, допущение и открытый вопрос для §10–§11."""
        # Документы фазы заводит открытие раздела «Документы» (как у человека):
        # без этого матрица KDP-A честно говорит «документа нет».
        документы = вызов(self.base, "GET", f"/v2/documents?project={self.проект}").get("items", [])
        self.сделано.append(f"документы фазы: {', '.join(д['code'] for д in документы)}")
        план = вызов(self.base, "GET", f"/v2/plan?project={self.проект}")
        if not план.get("planned"):
            точки = {т["key"]: т for т in вызов(self.base, "GET", f"/v2/points?project={self.проект}")["items"]}
            import datetime
            сегодня = datetime.date.today()
            окна = []
            for i in range(1, 13):
                начало = сегодня + datetime.timedelta(days=(i - 1) * 5)
                окна.append({"scene": str(i), "start": начало.isoformat(),
                             "end": (начало + datetime.timedelta(days=4)).isoformat()})
            вызов(self.base, "POST", f"/v2/plan?project={self.проект}", {
                "gate_dates": [{"gate": к, "date": т["planned_date"]} for к, т in точки.items() if т.get("planned_date")],
                "scene_windows": окна, "author": "Чернов Д.",
            })
            self.сделано.append("сцена 1: план работ фазы задан (окна сцен 1–12)")
        настройки = self.сид.get("points", {})
        риски = {(р.get("doc") or {}).get("statement"): р for р in self.сущности("risk")}
        for р in настройки.get("risk_resolutions", []):
            риск = риски.get(р["statement"])
            if риск and риск.get("status") != "closed":
                вызов(self.base, "POST", f"/v2/risks/{риск['code']}/close?project={self.проект}",
                      {"resolution": р["resolution"], "author": "Чернов Д."})
                self.сделано.append(f"сцена 17: риск {риск['code']} закрыт решением")
        # §10 наполняется допущениями по истине схем (source_mark = П):
        # смотрим на сам раздел, а не на список фактов.
        отчёт = вызов(self.base, "GET", f"/v2/documents/mcreport?project={self.проект}&gate=KDP-A")
        строк10 = sum(len(э.get("rows", [])) for р in отчёт.get("sections", []) if р.get("no") == "§10"
                      for э in р.get("elements", []))
        допущение = настройки.get("assumption")
        if допущение and строк10 == 0:
            вызов(self.base, "POST", f"/v2/facts?project={self.проект}", {**допущение, "author": "Иванов И."})
            self.сделано.append("§10: допущение заведено руками (помета П)")
        тема = настройки.get("open_topic")
        if тема:
            темы = вызов(self.base, "GET", f"/v2/topics?project={self.проект}").get("items", [])
            if not any(т.get("label") == тема for т in темы):
                вызов(self.base, "POST", f"/v2/topics?project={self.проект}", {"label": тема, "author": "Иванов И."})
                self.сделано.append("§11: открытый вопрос заведён темой")

    def сцена_15_внутренний_обзор(self) -> None:
        """Чек с галками РП: одно «нет» — замечание с возвратом; сцена
        возврата снова в работе; закрыли — решение РП."""
        точка = self.точка("internal_review")
        if точка["passed"]:
            self.пропущено.append("сцена 15: внутренний обзор пройден")
            return
        вопросы = self.точка("MCR").get("expertise", {}).get("questions", [])
        вопрос = вопросы[0] if вопросы else "Названы ли все стороны?"
        if not точка["findings"]:
            замечание = вызов(self.base, "POST", f"/v2/points/internal_review/findings?project={self.проект}",
                              {"text": f"Нет: {вопрос} — проверить реестр сторон", "returns_to_scene": "3",
                               "question": вопрос})
            фаза = вызов(self.base, "GET", f"/v2/phase?project={self.проект}")
            сцена3 = next(с for с in фаза["scenes"] if с["key"] == "3")
            if сцена3["state"] != "open":
                raise Отказ(f"замечание не вернуло сцену 3 в работу: состояние {сцена3['state']}")
            self.сделано.append(f"сцена 15: замечание {замечание['code']} вернуло сцену 3 в работу")
            вызов(self.base, "POST", f"/v2/findings/{замечание['code']}/close?project={self.проект}",
                  {"note": "стороны проверены — реестр полон"})
            self.сделано.append(f"сцена 15: замечание {замечание['code']} закрыто, сцена 3 прожита вновь")
        # фиксация инженером — отказ ролью: это мера шипа, а не помеха
        self.войти("petrova")
        try:
            вызов(self.base, "POST", f"/v2/points/internal_review/decide?project={self.проект}", {"outcome": "approve"})
            raise Отказ("инженер зафиксировал внутренний обзор — роль не проверена")
        except Отказ as о:
            if "роль" not in str(о) and "принимает" not in str(о):
                raise
            self.сделано.append(f"сцена 15: инженеру отказано ролью — {str(о)[:80]}")
        self.войти("chernov")
        вызов(self.base, "POST", f"/v2/points/internal_review/decide?project={self.проект}",
              {"outcome": "approve", "note": "к MCR готовы"})
        self.сделано.append("сцена 15: внутренний обзор зафиксирован РП")

    def сцена_16_mcr(self) -> None:
        точка = self.точка("MCR")
        if точка["passed"]:
            self.пропущено.append("сцена 16: MCR пройден")
            return
        if точка["blocking"]:
            raise Отказ("MCR держится: " + "; ".join(точка["blocking"]))
        вызов(self.base, "POST", f"/v2/points/MCR/decide?project={self.проект}",
              {"outcome": "approve", "note": "экспертиза: замысел понят"})
        self.сделано.append("сцена 16: MCR зафиксирован DA")

    def сцена_17_замечания(self) -> None:
        открытые = вызов(self.base, "GET", f"/v2/findings?project={self.проект}&status=open")["items"]
        for з in открытые:
            вызов(self.base, "POST", f"/v2/findings/{з['code']}/close?project={self.проект}", {"note": "устранено"})
            self.сделано.append(f"сцена 17: замечание {з['code']} закрыто")
        if not открытые:
            self.пропущено.append("сцена 17: открытых замечаний нет")

    def сцена_14_fad_fa(self) -> None:
        """FAD и FA (Прил. 1–2): разделы-запросы наполняются реестром, разделы
        с тезисом ждут слова руководителя; к KDP-A оба базируются."""
        тезисы = self.сид.get("points", {}).get("statements", {})
        for код in ("fad", "fa"):
            вызов(self.base, "POST", f"/v2/documents?project={self.проект}", {"template": код, "author": "Чернов Д."})
            документ = вызов(self.base, "GET", f"/v2/documents/{код}?project={self.проект}&gate=KDP-A")
            for раздел in документ["sections"]:
                ждёт = " ".join(раздел.get("waiting", []))
                текст = тезисы.get(код, {}).get(раздел["no"])
                if "тезис:" in ждёт and текст:
                    вызов(self.base, "POST", f"/v2/documents/{код}/statement?project={self.проект}",
                          {"section": раздел["no"], "text": текст, "author": "Чернов Д."})
                    self.сделано.append(f"сцена 14: {код.upper()} {раздел['no']} — тезис")
            документ = вызов(self.base, "GET", f"/v2/documents/{код}?project={self.проект}&gate=KDP-A")
            if документ["complete"] < документ["total"]:
                неполные = [f"{р['no']} ({'; '.join(р.get('waiting', []))[:80]})" for р in документ["sections"] if not р.get("complete")]
                raise Отказ(f"{код.upper()} к KDP-A неполон: " + ", ".join(неполные))
            линии = вызов(self.base, "GET", f"/v2/documents/{код}/baselines?project={self.проект}").get("items", [])
            if not линии:
                вызов(self.base, "POST", f"/v2/documents/{код}/baseline?project={self.проект}",
                      {"name": "KDP-A", "author": "Чернов Д."})
                self.сделано.append(f"сцена 14: {код.upper()} базирован линией «KDP-A»")

    def сцена_18_kdp_a(self) -> None:
        точка = self.точка("KDP-A")
        if точка["passed"]:
            self.пропущено.append("сцена 18: KDP-A пройдена")
            return
        if точка["blocking"]:
            raise Отказ("KDP-A держится: " + "; ".join(точка["blocking"]))
        ответ = вызов(self.base, "POST", f"/v2/points/KDP-A/decide?project={self.проект}",
                      {"outcome": "approve", "note": "переход в Phase A"})
        if ответ.get("phase") != "Phase A":
            raise Отказ(f"после KDP-A фаза {ответ.get('phase')!r}, а не Phase A")
        self.сделано.append("сцена 18: решение DA — проект в Phase A")

    # ── Phase A (шип G): переход, экземпляры аванпроекта, деривация, SEMP/OpsCon/ICD ──
    def phase_a(self) -> None:
        self.войти("chernov")
        фаза = вызов(self.base, "GET", f"/v2/phase?project={self.проект}")
        if фаза.get("phase") != "Phase A":
            raise Отказ(f"проект в фазе {фаза.get('phase')!r}: сначала --points до KDP-A")
        сцены = {с["key"]: с for с in фаза["scenes"]}
        if "A1" not in сцены:
            raise Отказ("после KDP-A лента осталась Pre-A: шаблон PHT-9002 не записан проекту (полки не загружены?)")
        self.сделано.append(f"Phase A: лента из {len(фаза['scenes'])} сцен, точки {[т['key'] for т in фаза['gates']]}")
        self.войти("ivanov")
        # элементы состава: аванпроект — экземпляр на каждый
        состав = {у["code"]: у for у in вызов(self.base, "GET", f"/v2/components?project={self.проект}").get("items", [])}
        for код, имя, kind, level, parent in [
            ("SEG-SP", "Космический сегмент", "segment", 1, None),
            ("SEG-GS", "Наземный сегмент", "segment", 1, None),
            ("SEG-US", "Пользовательский сегмент", "segment", 1, None),
            ("EL-SC", "Космический аппарат (элемент)", "element", 2, "SEG-SP"),
            ("EL-GS", "Наземный комплекс управления (элемент)", "element", 2, "SEG-GS"),
            ("EL-UT", "Абонентский терминал (элемент)", "element", 2, "SEG-US"),
        ]:
            if код in состав:
                continue
            тело = {"code": код, "name": имя, "kind": kind, "level": level, "nature": "node", "author": "Иванов И."}
            if parent:
                тело["parent"] = parent
            вызов(self.base, "POST", f"/v2/components?project={self.проект}", тело)
            self.сделано.append(f"Phase A: узел {код} ({kind})")
        # системные требования из проектных — деривация с основанием
        требования = вызов(self.base, "GET", f"/v2/requirements?project={self.проект}").get("items", [])
        проектные = [т for т in требования if т.get("level") == "project"]
        выведенные = {т.get("code") for т in требования if str(т.get("code", "")).startswith("RQ-S-000")}
        if проектные and "RQ-S-0001" not in выведенные:
            родитель = проектные[0]["code"]
            for код, формулировка, основание, носитель in [
                ("RQ-S-0001", "КА должен передавать кадр телеметрии в НКУ не реже одного раза за виток.", "суточная норма проектного уровня делится на 16 витков", "EL-SC"),
                ("RQ-S-0002", "НКУ должен принимать кадры телеметрии на каждом сеансе связи с КА.", "приём — зеркало передачи", "EL-GS"),
                ("RQ-S-0003", "Абонентский терминал должен получать подтверждение доставки в течение двух суток.", "класс B′: подтверждённая доставка — нужда ND-0002", "EL-UT"),
            ]:
                вызов(self.base, "POST", f"/v2/requirements/derive?project={self.проект}",
                      {"parent": родитель, "code": код, "statement": формулировка, "rationale": основание, "carrier": носитель, "subtype": "decomposition",
                       "verification_method": "test", "acceptance_criteria": "в журнале приёмного тракта есть кадр за каждый виток сеанса", "author": "Иванов И."})
                self.сделано.append(f"Phase A: {код} выведено из {родитель} на {носитель}")
        # стыки элементов: IF-S-USER — КА ↔ терминал; IF-S-G — КА ↔ НКУ
        стыки = {с["code"] for с in вызов(self.base, "GET", f"/v2/interfaces?project={self.проект}").get("items", [])}
        for код, имя, тип, a, b in [
            ("IF-S-USER", "КА — абонентский терминал (P-диапазон, S-диапазон)", "rf", "EL-SC", "EL-UT"),
            ("IF-S-G", "КА — НКУ (S-диапазон)", "rf", "EL-SC", "EL-GS"),
        ]:
            if код in стыки:
                continue
            вызов(self.base, "POST", f"/v2/interfaces?project={self.проект}",
                  {"code": код, "name": имя, "type": тип, "a": a, "b": b, "direction": "both", "requirement_classes": ["interface"], "author": "Иванов И."})
            self.сделано.append(f"Phase A: стык {код}")
        # требование на стык — ICD собирается из него
        if not any(т.get("code") == "RQ-S-0004" for т in требования) and проектные:
            вызов(self.base, "POST", f"/v2/requirements/derive?project={self.проект}",
                  {"parent": проектные[0]["code"], "code": "RQ-S-0004", "statement": "Стык КА — терминал должен обеспечивать передачу пакета 32 байта за один сеанс видимости.",
                   "rationale": "короткое сообщение класса A′ — 32 байта", "carrier": "IF-S-USER", "subtype": "refinement", "category": "interface", "level": "interface",
                   "verification_method": "test", "acceptance_criteria": "пакет 32 байта принят за один сеанс на стенде стыка", "author": "Иванов И."})
            self.сделано.append("Phase A: RQ-S-0004 на стык IF-S-USER")
        # документы фазы: SEMP, OpsCon, ICD
        self.войти("chernov")
        документы = {д["code"] for д in вызов(self.base, "GET", f"/v2/documents?project={self.проект}").get("items", [])}
        for код in ["semp", "opscon", "icd"]:
            if код not in документы:
                вызов(self.base, "POST", f"/v2/documents?project={self.проект}", {"template": код, "author": "Чернов Д."})
                self.сделано.append(f"Phase A: документ {код} заведён")
        semp = вызов(self.base, "GET", f"/v2/documents/semp?project={self.проект}&gate=SRR")
        р6 = next((р for р in semp.get("sections", []) if р.get("no") == "§6"), {})
        строк = sum(len(э.get("rows", [])) for э in р6.get("elements", []))
        self.сделано.append(f"Phase A: SEMP §6 — {строк} строк процессов" + ("" if строк == 17 else " (ожидалось 17: полка процессов СИ не загружена?)"))
        icd = вызов(self.base, "GET", f"/v2/documents/icd?project={self.проект}&gate=SDR")
        строкиICD = [row for р in icd.get("sections", []) for э in р.get("elements", []) for row in э.get("rows", [])]
        ifuser = [r for r in строкиICD if any("IF-S-USER" in str(x) for x in r)]
        self.сделано.append(f"Phase A: ICD — строк {len(строкиICD)}, с IF-S-USER {len(ifuser)}")
        фаза = вызов(self.base, "GET", f"/v2/phase?project={self.проект}")
        экземпляры = [с for с in фаза["scenes"] if с.get("instance_of") == "A4"]
        self.сделано.append("Phase A: экземпляры аванпроекта — " + ", ".join(f"{с['key']} [{с['state']}]" for с in экземпляры))
        self.phase_a_условия(фаза)

    # ── Условия сцен Phase A (поставка 10.09): кормим то, для чего есть дорога ──
    def phase_a_условия(self, фаза: dict) -> None:
        """A1 план фазы · A2/A3 SEMP и ConOps до базирования · A6 модели и
        протокол · A9 SMA · A10 оценка параметрикой · A11 Project Plan и FA ·
        A12 базовые линии и позиции экспертиз. Чего кормить нечем (функции,
        цепочки, бюджеты, элементы обмена, ступень SDR) — остаётся разрывом
        и печатается честно."""
        self.войти("chernov")
        # A1: даты точек фазы, ответственные сцен, окно первой сцены
        точки = {т["key"]: т for т in фаза["gates"]}
        даты = {"internal_review": "2026-10-01", "MCR": "2026-11-01", "KDP-A": "2026-12-01",
                "internal_review_a": "2027-03-01", "SRR": "2027-05-01", "SDR": "2027-09-01", "KDP-B": "2027-11-01"}
        план = вызов(self.base, "GET", f"/v2/plan?project={self.проект}")
        if (план or {}).get("phase") != "Phase A":
            вызов(self.base, "POST", f"/v2/plan?project={self.проект}", {
                "phase": "Phase A", "set_by": "Чернов Д.",
                "gate_dates": [{"gate": к, "date": д} for к, д in даты.items()],
                "scene_windows": [
                    {"scene": "A1", "start": "2027-01-10", "end": "2027-01-31", "responsible": "chernov"},
                    {"scene": "A2", "start": "2027-01-15", "end": "2027-03-01", "responsible": "chernov"},
                    {"scene": "A3", "start": "2027-01-20", "end": "2027-04-01", "responsible": "ivanov"},
                ],
            })
            self.сделано.append("A1: план фазы — даты точек, ответственные сцен, окна A1–A3")
        # A2/A3/A9/A11: документы фазы с тезисами там, где раздел ждёт слова
        for код in ["sma", "projectplan"]:
            if код not in {д["code"] for д in вызов(self.base, "GET", f"/v2/documents?project={self.проект}").get("items", [])}:
                вызов(self.base, "POST", f"/v2/documents?project={self.проект}", {"template": код, "author": "Чернов Д."})
                self.сделано.append(f"Phase A: документ {код} заведён")
        тезис = {
            "semp": "по SEMP: {title} — по шаблону БП-PA, отклонения названы в §9",
            "conops": "по ConOps: {title} — режимы и сценарии из сцен 9 и A3",
            "icd": "по ICD: {title} — стыки и протоколы решений сцен A4 и A6",
            "sma": "план обеспечения: {title} — из ОСЗ, рисков и радиостыков",
            "projectplan": "Project Plan: {title} — ссылкой на документ-источник, не копией",
            "fa": "FA, план Phase B: {title} — вехи SRR · SDR · KDP-B и отклонения",
        }
        for код, ступень in [("semp", "SRR"), ("conops", "SRR"), ("icd", "SDR"), ("sma", "SDR"), ("projectplan", "KDP-B"), ("fa", "KDP-B")]:
            документ = вызов(self.base, "GET", f"/v2/documents/{код}?project={self.проект}&gate={ступень}")
            for раздел in документ.get("sections", []):
                ждёт = " ".join(раздел.get("waiting", []))
                пусто = not any(э.get("rows") or э.get("text") for э in раздел.get("elements", []))
                if "тезис:" in ждёт or (код == "fa" and раздел["no"] == "§6" and пусто):
                    вызов(self.base, "POST", f"/v2/documents/{код}/statement?project={self.проект}",
                          {"section": раздел["no"], "text": тезис[код].format(title=раздел["title"]), "author": "Чернов Д."})
                    self.сделано.append(f"Phase A: {код} {раздел['no']} — тезис")
            if ступень == "SRR":
                линии = вызов(self.base, "GET", f"/v2/documents/{код}/baselines?project={self.проект}").get("items", [])
                if not линии:
                    try:
                        вызов(self.base, "POST", f"/v2/documents/{код}/baseline?project={self.проект}", {"name": "SRR", "author": "Чернов Д."})
                        self.сделано.append(f"Phase A: {код} базирован линией «SRR»")
                    except Отказ as о:
                        self.пропущено.append(f"Phase A: {код} не базирован — {str(о)[:120]}")
        # A3: третий сценарий и завершение миссии
        self.войти("ivanov")
        сценарии = {с["name"] for с in вызов(self.base, "GET", f"/v2/scenarios?project={self.проект}").get("items", [])}
        for имя, шаги in [
            ("Нештатный: потеря ориентации КА", [{"actor": "КА", "action": "переход в безопасный режим"}, {"actor": "НКУ", "action": "восстановление ориентации по телеметрии"}]),
            ("Завершение миссии: увод КА", [{"actor": "НКУ", "action": "команда на увод"}, {"actor": "КА", "action": "манёвр увода и пассивация"}]),
        ]:
            if имя in сценарии:
                continue
            try:
                вызов(self.base, "POST", f"/v2/scenarios?project={self.проект}", {"name": имя, "layer": "SA", "steps": шаги, "author": "Иванов И."})
                self.сделано.append(f"A3: сценарий «{имя[:40]}»")
            except Отказ as о:
                self.пропущено.append(f"A3: сценарий «{имя[:40]}» — {str(о)[:100]}")
        # A6: четыре модели — взять, прогнать, верифицировать
        модели = {м["code"]: м for м in вызов(self.base, "GET", f"/v2/models?project={self.проект}").get("items", [])}
        if not модели:
            try:
                вызов(self.base, "POST", f"/v2/models/take?project={self.проект}", {"author": "Иванов И."})
                модели = {м["code"]: м for м in вызов(self.base, "GET", f"/v2/models?project={self.проект}").get("items", [])}
                self.сделано.append(f"A6: модели взяты с полки ({len(модели)})")
            except Отказ as о:
                self.пропущено.append(f"A6: модели не взяты — {str(о)[:100]}")
        for код in ["М1", "М2а", "М3а", "М6"]:
            if код not in модели:
                self.пропущено.append(f"A6: модели {код} в проекте нет")
                continue
            try:
                if not модели[код].get("last_run"):
                    вызов(self.base, "POST", f"/v2/models/{urllib.parse.quote(код)}/run?project={self.проект}", {"author": "Иванов И.", "outputs": {"result": "прогон волны Phase A"}})
                if модели[код].get("verification") not in ("verified", "validated"):
                    вызов(self.base, "POST", f"/v2/models/{urllib.parse.quote(код)}/verify?project={self.проект}", {"status": "verified", "note": "сверено с эталоном spec/reference", "author": "Иванов И."})
                self.сделано.append(f"A6: модель {код} — прогон и верификация")
            except Отказ as о:
                self.пропущено.append(f"A6: модель {код} — {str(о)[:110]}")
        # A8: риски со сроком к SRR — решением словами (как сцена 17 к KDP-A)
        self.войти("chernov")
        for р in вызов(self.base, "GET", f"/v2/risks?project={self.проект}").get("items", []):
            if р.get("status") != "closed" and р.get("due_point") in ("SRR", "internal_review_a"):
                try:
                    вызов(self.base, "POST", f"/v2/risks/{р['code']}/close?project={self.проект}",
                          {"resolution": "к SRR: мера исполнена, риск снят решением РП", "author": "Чернов Д."})
                    self.сделано.append(f"A8: риск {р['code']} закрыт решением к SRR")
                except Отказ as о:
                    self.пропущено.append(f"A8: риск {р['code']} — {str(о)[:100]}")
        # A10: оценка параметрикой к KDP-B
        пакеты = вызов(self.base, "GET", f"/v2/wbs?project={self.проект}").get("items", [])
        if пакеты:
            первый = пакеты[0]["code"]
            try:
                вызов(self.base, "POST", f"/v2/wbs/estimate?project={self.проект}",
                      {"package": первый, "min": 1900, "max": 2500, "unit": "млн ₽", "method": "parametric",
                       "assumptions": "параметрика по массе ПН класса 34 кг, курс 2026; к KDP-B", "author": "Чернов Д."})
                self.сделано.append(f"A10: оценка {первый} параметрикой")
            except Отказ as о:
                self.пропущено.append(f"A10: оценка параметрикой — {str(о)[:100]}")
        # A12: базовые линии и позиции экспертиз
        снимки = {с.get("kind"): с for с in вызов(self.base, "GET", f"/v2/baselines?project={self.проект}").get("items", [])}
        for вид, точка in [("functional", "SRR"), ("allocated", "SDR")]:
            if вид in снимки:
                continue
            try:
                вызов(self.base, "POST", f"/v2/baselines?project={self.проект}", {"name": f"{вид}-{точка}", "kind": вид, "gate": точка, "author": "Чернов Д."})
                self.сделано.append(f"A12: базовая линия {вид} к {точка}")
            except Отказ as о:
                self.пропущено.append(f"A12: снимок {вид} отвергнут — {str(о)[:220]}")
        for ключ in ["SRR", "SDR"]:
            экспертиза = точки.get(ключ, {}).get("expertise") or {}
            позиции = [п.get("artifact") for п in (экспертиза.get("positions") or экспертиза.get("control_items") or []) if п.get("artifact")]
            if not позиции:
                self.пропущено.append(f"A12: у точки {ключ} нет позиций экспертизы на полке")
                continue
            вызов(self.base, "POST", f"/v2/points/{ключ}/positions?project={self.проект}",
                  {"positions": [{"artifact": а, "verdict": "принято", "note": "по данным проекта"} for а in позиции], "author": "Чернов Д."})
            self.сделано.append(f"A12: {ключ} — вердикты по {len(позиции)} позициям")
        # итог: что держит сцены Phase A
        фаза = вызов(self.base, "GET", f"/v2/phase?project={self.проект}")
        for с in фаза["scenes"]:
            if с["state"] != "done" and с.get("blockers"):
                self.пропущено.append(f"{с['key']} держится: " + "; ".join(с["blockers"])[:200])

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
        if self.знания:
            self.знания_2()
        if self.точки:
            # после KDP-A лента — Phase A, точек Pre-A в ней нет: повторный прогон их не ищет
            if вызов(self.base, "GET", f"/v2/phase?project={self.проект}").get("phase") == "Phase A":
                self.пропущено.append("сцены 15–18: точки Pre-A пройдены — проект уже в Phase A")
            else:
                self.подготовка_к_точкам()
                self.сцена_15_внутренний_обзор()
                self.сцена_16_mcr()
                self.сцена_17_замечания()
                self.сцена_14_fad_fa()
                self.сцена_18_kdp_a()
        if self.фазаA:
            self.phase_a()


def состояние(base: str, проект: str) -> None:
    """Что получилось: сцены, свёртка с рамкой и полнота разделов отчёта."""
    фаза = вызов(base, "GET", f"/v2/phase?project={проект}")
    print("  фаза:", фаза.get("phase"))
    print("  сцены:", " ".join(f"{с['key']}:{с['state'][:4]}" for с in фаза["scenes"]))
    for т in вызов(base, "GET", f"/v2/points?project={проект}")["items"]:
        состояние_точки = "пройдена" if т["passed"] else (f"держится ({len(т['blocking'])})" if т["blocking"] else "условия выполнены")
        открытых = sum(1 for з in т["findings"] if з["status"] == "open")
        print(f"  ◆ {т['title'][:40]:<42} {состояние_точки:<22} замечаний открыто {открытых}")
        for б in т["blocking"][:4]:
            print(f"      ← {б[:110]}")

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
    ap.add_argument("--points", action="store_true", help="после сцен 1–12 пройти точки: сцены 15–18 до KDP-A")
    ap.add_argument("--knowledge", action="store_true", help="знания 2: ТЗ · даташит · норматив живой моделью, допущение к точке")
    ap.add_argument("--phase-a", action="store_true", help="шип G: после KDP-A — элементы состава, деривация, стыки, SEMP/OpsCon/ICD, экземпляры аванпроекта")
    args = ap.parse_args()

    сид = json.loads(СИД.read_text(encoding="utf-8"))
    проект = args.project or сид["code"]

    вызов(args.base, "POST", "/auth/stand-login", {"login": "chernov"})
    if not args.report:
        прогон = Прогон(args.base, проект, сид, точки=args.points, знания=args.knowledge, фазаA=args.phase_a)
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
        # Phase A: что пропущено и что держит сцены — печатается, а не глотается
        for п in прогон.пропущено:
            if not п.startswith("сцена ") and not п.startswith("знания"):
                print(f"  · {п}")

    print(f"состояние {проект}:")
    состояние(args.base, проект)
    return 0


if __name__ == "__main__":
    sys.exit(main())
