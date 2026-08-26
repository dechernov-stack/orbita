#!/usr/bin/env python3
"""Наполнение библиотеки Pre-Phase A (ЗАДАЧА-CODE-БИБЛИОТЕКА §5, манифест
СТРУКТУРА-БИБЛИОТЕКИ §5) — то, что Code делает сам: Б4 класс миссии, А1
нормативы реквизитами, Б5 каркас PBS, Б6 каркас WBS, Б7 типовые интерфейсы,
Г1 заготовки профилей, Г2 глоссарий.

Полки А2 (стейкхолдеры) и Б3 (риски) — службой на акцепт владельцу; А3/А4 —
ждут материалов владельца: скрипт их НЕ выдумывает (ловушка 6).

Запуск: python3 tools/seed_library.py [http://localhost:8080/api]
Защита от дублей: если класс «НОО · связь и IoT» уже на полке — отказ.
"""
import json
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api"
AUTHOR = "наполнение библиотеки (§5)"


def call(method: str, path: str, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method=method,
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())


def put(type_, doc):
    out = call("POST", "/library/objects", {"type": type_, "doc": doc, "author": AUTHOR})
    print(f"  {out['id']}  {doc.get('name', doc.get('statement', ''))[:60]}")
    return out["id"]


existing = call("GET", "/library/mission-classes")
if any(c["name"] == "НОО · связь и IoT" for c in existing):
    sys.exit("класс «НОО · связь и IoT» уже на полке — повторное наполнение создало бы дубли")

# ── Б4: класс миссии — ключ ко всему; типовые ограничения = реестр решений ──
print("Б4 · класс миссии")
mc = put("mission_class", {
    "name": "НОО · связь и IoT",
    "description": "Низкоорбитальные группировки связи и интернета вещей: "
                   "малые платформы, РЧ-линии, классы потребителей A'/B'/C'",
    "typical_constraints": [
        {"code": "Р1", "text": "Полезная нагрузка — только регенеративная; bent-pipe не рассматривается."},
        {"code": "Р2", "text": "Платформы — диапазон 12U…100 кг; конфигурации вне диапазона недопустимы."},
        {"code": "Р3", "text": "Межспутниковая связь — только радиодиапазон; оптический ISL не рассматривается."},
        {"code": "Р5", "text": "Терминалы знают эфемериды; КА ведёт широковещательный маяк альманаха."},
        {"code": "Р7", "text": "Подвижность — статика и маршруты; роуминг регуляторных зон не рассматривается."},
    ],
})

# ── А1: нормативная база — реквизиты и известные проекту пункты, не полный текст ──
print("А1 · нормативная база")
norms = [
    {"name": "О мониторинге перевозок опасных грузов", "kind": "decree",
     "number": "ПП РФ № 2216", "org": "Правительство РФ",
     "edition_date": "2020-12-18", "in_force": "in_force",
     "clauses": [{"clause": "п. 6", "text": "Геопозиция ТС с опасным грузом передаётся не реже одного раза в 30 с"}],
     "summary": "Мониторинг транспортных средств с опасными грузами"},
    {"name": "О безопасности колёсных транспортных средств", "kind": "tech_reg",
     "number": "ТР ТС 018/2011", "org": "Таможенный союз",
     "edition_date": "2011-12-09", "in_force": "in_force",
     "summary": "Аппаратура спутниковой навигации на транспорте"},
    {"name": "Об утверждении порядка оснащения ТС аппаратурой спутниковой навигации", "kind": "order",
     "number": "Приказ Минтранса № 2", "org": "Минтранс России",
     "edition_date": "2012-01-10", "in_force": "in_force",
     "summary": "Порядок оснащения транспортных средств навигационной аппаратурой"},
    {"name": "О безопасности критической информационной инфраструктуры", "kind": "law",
     "number": "187-ФЗ", "org": "Российская Федерация",
     "edition_date": "2017-07-26", "in_force": "in_force",
     "summary": "Категорирование и защита объектов КИИ"},
    {"name": "Международная конвенция по охране человеческой жизни на море (LRIT, АИС)", "kind": "convention",
     "number": "SOLAS", "org": "ИМО",
     "edition_date": "1974-11-01", "in_force": "in_force",
     "summary": "Дальняя идентификация и сопровождение судов, автоматическая идентификационная система"},
    {"name": "Проект постановления о мониторинге беспилотных авиационных систем", "kind": "decree",
     "number": "проект (БАС)", "org": "Правительство РФ",
     "edition_date": "2026-03-01", "in_force": "draft",
     "summary": "Мониторинг БАС — ожидаемое требование с 03.2026"},
    {"name": "Process for Limiting Orbital Debris", "kind": "standard",
     "number": "NASA-STD-8719.14", "org": "NASA",
     "edition_date": "2021-11-01", "in_force": "in_force",
     "clauses": [{"clause": "4.6", "text": "Увод КА с рабочей орбиты не позднее 25 лет после завершения миссии"}],
     "summary": "Ограничение орбитального засорения"},
    {"name": "NASA Space Flight Program and Project Management Requirements", "kind": "standard",
     "number": "NPR 7120.5", "org": "NASA",
     "edition_date": "2021-08-01", "in_force": "in_force",
     "summary": "Жизненный цикл программ: фазы, контрольные точки, KDP"},
    {"name": "NASA Systems Engineering Processes and Requirements", "kind": "standard",
     "number": "NPR 7123.1", "org": "NASA",
     "edition_date": "2020-02-01", "in_force": "in_force",
     "summary": "Процессы системной инженерии; прил. G — критерии обзоров"},
    {"name": "Бизнес-процесс Pre-Phase A (регламент проекта)", "kind": "regulation",
     "number": "БП-PPA", "org": "проект «Орбита»",
     "edition_date": "2026-07-01", "in_force": "in_force",
     "summary": "Операции и выходы фазы Pre-Phase A"},
    {"name": "Бизнес-процесс Phase A (регламент проекта)", "kind": "regulation",
     "number": "БП-PA", "org": "проект «Орбита»",
     "edition_date": "2026-07-01", "in_force": "in_force",
     "summary": "Операции и выходы фазы Phase A"},
]
nr_ids = {}
for n in norms:
    nr_ids[n["number"]] = put("normative_document", n)

# ── Б5: каркас PBS «типовой НОО-IoT» + внутренние интерфейсы (Б7 живёт и здесь) ──
# Пачка использует локальные метки id; применение перевыпускает идентификаторы.
print("Б5 · каркас PBS (с внутренними интерфейсами)")


def cm(i, name, kind, parent=None):
    d = {"id": f"CM-{9000+i:04d}", "name": name, "kind": kind}
    if parent is not None:
        d["parent"] = f"CM-{9000+parent:04d}"
    return d


pbs_nodes = [
    cm(1, "Космический сегмент", "segment"),
    cm(2, "КА-ретранслятор IoT", "system", 1),
    cm(3, "Полезная нагрузка (регенеративная)", "subsystem", 2),
    cm(4, "Бортовой цифровой вычислительный модуль", "subsystem", 2),
    cm(5, "Система электропитания", "subsystem", 2),
    cm(6, "Антенно-фидерная система", "subsystem", 2),
    cm(7, "Наземный сегмент", "segment"),
    cm(8, "Наземная станция", "system", 7),
    cm(9, "Центр управления полётом", "system", 7),
    cm(10, "Программный комплекс ЦУП", "subsystem", 9),
    cm(11, "Пользовательский сегмент", "segment"),
    cm(12, "Абонентский терминал", "system", 11),
]
pbs_interfaces = [
    {"id": "IF-9001", "name": "Радиолиния КА ↔ наземная станция",
     "owners": ["CM-9002", "CM-9008"],
     "description": "Фидерная РЧ-линия; протокол и бюджет — по типовому ICD радиолинии"},
    {"id": "IF-9002", "name": "Канал КА ↔ абонентский терминал",
     "owners": ["CM-9002", "CM-9012"],
     "description": "Абонентская РЧ-линия классов A'/B'/C'; бюджет — по типовому ICD канала"},
    {"id": "IF-9004", "name": "Наземный канал ЦУП ↔ станция",
     "owners": ["CM-9009", "CM-9008"],
     "description": "Наземная сеть управления; протокол — по типовому ICD наземного канала"},
]
put("library_fragment", {
    "name": "Типовой PBS НОО-IoT", "shelf": "B5", "mission_class_ref": mc,
    "summary": "КА с подсистемами, наземный и пользовательский сегменты, ЦУП, софт-узлы; внутренние интерфейсы",
    "counters": {"component": len(pbs_nodes), "interface": len(pbs_interfaces)},
    "origin": {"project": "LIB", "author": AUTHOR, "date": "2026-08-26"},
    "anonymized": True,
    "payload": {"objects": pbs_nodes + pbs_interfaces},
})

# ── Б7: четыре типовых интерфейса отдельной полкой (замкнутый фрагмент со сторонами) ──
print("Б7 · типовые интерфейсы")
put("library_fragment", {
    "name": "Типовые интерфейсы НОО-IoT", "shelf": "B7", "mission_class_ref": mc,
    "summary": "Радиолиния КА↔станция · канал КА↔терминал · межспутниковая РЧ · наземный канал ЦУП↔станция",
    "counters": {"component": 5, "interface": 4},
    "origin": {"project": "LIB", "author": AUTHOR, "date": "2026-08-26"},
    "anonymized": True,
    "payload": {"objects": [
        {"id": "CM-9101", "name": "КА (сторона интерфейса)", "kind": "system"},
        {"id": "CM-9102", "name": "Наземная станция (сторона интерфейса)", "kind": "system"},
        {"id": "CM-9103", "name": "Абонентский терминал (сторона интерфейса)", "kind": "system"},
        {"id": "CM-9104", "name": "ЦУП (сторона интерфейса)", "kind": "system"},
        {"id": "CM-9105", "name": "Соседний КА (сторона ISL)", "kind": "system"},
        {"id": "IF-9101", "name": "Радиолиния КА ↔ наземная станция",
         "owners": ["CM-9101", "CM-9102"],
         "description": "Фидерная РЧ-линия; бюджет линии — по типовому ICD"},
        {"id": "IF-9102", "name": "Канал КА ↔ абонентский терминал",
         "owners": ["CM-9101", "CM-9103"],
         "description": "Абонентская линия A'/B'/C'; эфемеридный доступ (Р5)"},
        {"id": "IF-9103", "name": "Межспутниковая РЧ-линия",
         "owners": ["CM-9101", "CM-9105"],
         "description": "РЧ ISL (Р3); бюджет — по типовому ICD ISL"},
        {"id": "IF-9104", "name": "Наземный канал ЦУП ↔ станция",
         "owners": ["CM-9104", "CM-9102"],
         "description": "Наземная сеть управления"},
    ]},
})

# ── Б6: каркас WBS программы — работы, не зеркало состава ──
print("Б6 · каркас WBS")


def wb(i, name, parent=None):
    d = {"id": f"WB-{9000+i:04d}", "name": name}
    if parent is not None:
        d["parent"] = f"WB-{9000+parent:04d}"
    return d


put("library_fragment", {
    "name": "Типовой WBS программы НОО-IoT", "shelf": "B6", "mission_class_ref": mc,
    "summary": "Изготовление · интеграция · испытания · наземный комплекс · управление · эксплуатация",
    "counters": {"wbs_element": 7},
    "origin": {"project": "LIB", "author": AUTHOR, "date": "2026-08-26"},
    "anonymized": True,
    "payload": {"objects": [
        wb(1, "Программа НОО-IoT"),
        wb(2, "Изготовление КА и полезной нагрузки", 1),
        wb(3, "Интеграция космического комплекса", 1),
        wb(4, "Испытания (наземные и лётные)", 1),
        wb(5, "Наземный комплекс управления и приёма", 1),
        wb(6, "Управление программой", 1),
        wb(7, "Эксплуатация и сопровождение сервиса", 1),
    ]},
})

# ── Г1: заготовки профилей службы ──
print("Г1 · заготовки профилей")
put("library_fragment", {
    "name": "Заготовки профилей службы (О2–О4, рецензия)", "shelf": "G1", "mission_class_ref": mc,
    "summary": "Профили операций как заготовки: проект берёт и дополняет своими ограничениями",
    "counters": {"ai_profile": 3},
    "origin": {"project": "LIB", "author": AUTHOR, "date": "2026-08-26"},
    "anonymized": True,
    "payload": {"objects": [
        {"id": "AP-9001", "name": "Генерация О2 — цели и нужды",
         "purpose": "Из постановки и профилей стейкхолдеров — цели миссии и нужды",
         "kinds": ["mission_to_goals", "mission_to_needs"], "transport": "any",
         "statement_rules": [
             "формулировка нужды — от лица стейкхолдера, с источником",
             "цель миссии несёт измеримый показатель MOE"],
         "require_source": True},
        {"id": "AP-9002", "name": "Генерация О3–О4 — сервисы и требования",
         "purpose": "Из нужд — сервисы с QoS по классам потребителей; из сервисов — требования",
         "kinds": ["needs_to_services", "services_to_requirements"], "transport": "any",
         "statement_rules": [
             "сервис несёт QoS-профиль по видам потребителей A'/B'/C' (Р9)",
             "требование — одно предложение с оператором сравнения и величиной"],
         "require_source": True},
        {"id": "AP-9003", "name": "Рецензия формулировок требований",
         "purpose": "Проверка качества формулировок без генерации новых требований",
         "kinds": ["requirement_quality"], "transport": "any", "review_only": True},
    ]},
})

# ── Г2: глоссарий отрасли — термины проекта (CLAUDE.md, единообразно в текстах) ──
print("Г2 · глоссарий")
call("POST", "/library/objects", {"type": "source_document", "author": AUTHOR, "doc": {
    "name": "Глоссарий отрасли НОО-IoT", "kind": "other", "shelf": "G2",
    "org": "проект «Орбита»", "rights": "внутренний",
    "summary": "Отраслевая терминология и классы потребителей — питает правило «термины из глоссария»",
    "text": (
        "Зона видимости — геометрический footprint (угол места не ниже минимального); не путать с зоной обслуживания.\n"
        "Зона обслуживания — подмножество зоны видимости: бюджет линии замыкается и есть ёмкость.\n"
        "Карта спроса — сетка ячеек с интенсивностями по классам потребителей; не путать с картой покрытия.\n"
        "MOE — показатель качества сервиса; MOP — показатель требования; TPM — отслеживаемый технический параметр с резервом и трендом.\n"
        "Популяция — группа терминалов с общим профилем.\n"
        "A' — односторонний терминал; B' — с подтверждением (эфемеридный backoff); C' — оперативного управления. "
        "Не путать с классами устройств LoRaWAN A/B/C.\n"
        "ISL — межспутниковая линия (в классе только РЧ).\n"
        "LTAN — местное время восходящего узла опорной плоскости; при P плоскостях разнос 24/P часа."
    ),
}})
print("готово: полки Б4, А1, Б5, Б6, Б7, Г1, Г2 наполнены")
