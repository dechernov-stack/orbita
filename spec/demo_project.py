#!/usr/bin/env python3
"""Эталонный демонстрационный проект «Орбита-IoT» (шаг 7).

Двойное назначение:
  1) наполнение для интерфейса — экраны показывают связную картину, а не пустые таблицы;
  2) сквозная проверка — если проект проходит собственные отчёты целостности системы,
     значит модель работает от нужды до пакета передачи.

Данные строятся программно и проверяются функциями остальных эталонов:
отдельной «облегчённой» логики для демо нет.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from constraint_semantics import rollup_check, validate_mop, statement_matches_operator, render
from requirements_semantics import check_quality, trace_gaps, allocation_coverage, readiness
from traceability_semantics import (allocation_consistent, interface_allocation_valid,
                                    component_specification, rollup_children,
                                    verification_state, event_issues, evidence_state,
                                    validation_issues)
from risk_semantics import risk_issues, register_summary, criticality
from demand_semantics import build_demand_map, demand_weighted_quality, latitude_profile
from presentation_semantics import (radar_series, pareto_front, budget_segments,
                                    build_tree, verification_matrix, transfer_package)

Q = lambda v, u: {'value': v, 'unit': u, 'provenance': {'source': 'manual'}}
MOP = lambda n, op, v, u, roll='none': {'name': n, 'operator': op, 'value': Q(v, u), 'rollup': roll}

# ---------- состав системы ----------
COMPONENTS = {
    'CM-0001': {'kind': 'system',    'name': 'КС «Орбита-IoT»'},
    'CM-0010': {'kind': 'segment',   'name': 'Космический сегмент', 'parent': 'CM-0001'},
    'CM-0011': {'kind': 'subsystem', 'name': 'Платформа',           'parent': 'CM-0010'},
    'CM-0012': {'kind': 'subsystem', 'name': 'Полезная нагрузка',   'parent': 'CM-0010'},
    'CM-0020': {'kind': 'segment',   'name': 'Наземный сегмент',    'parent': 'CM-0001'},
    'CM-0021': {'kind': 'subsystem', 'name': 'Станция приёма',      'parent': 'CM-0020'},
    'CM-0030': {'kind': 'segment',   'name': 'Пользовательский сегмент', 'parent': 'CM-0001'},
    'CM-0031': {'kind': 'subsystem', 'name': 'Терминал',            'parent': 'CM-0030'},
    'IF-0001': {'kind': 'interface', 'name': 'Платформа ↔ ПН',      'owners': ['CM-0011', 'CM-0012']},
    'IF-0002': {'kind': 'interface', 'name': 'КА ↔ Станция приёма', 'owners': ['CM-0012', 'CM-0021']},
}

# ---------- нужды и сервисы ----------
NEEDS = [
    {'id': 'ND-0001', 'type': 'need', 'statement': 'Сбор телеметрии датчиков в районах без наземной связи',
     'stakeholder': {'name': 'Оператор сети', 'role': 'operator', 'priority': 5}},
    {'id': 'ND-0002', 'type': 'need', 'statement': 'Оперативное управление подвижными объектами на маршрутах',
     'stakeholder': {'name': 'Логистическая компания', 'role': 'customer', 'priority': 4}},
    {'id': 'ND-0003', 'type': 'need', 'statement': 'Соблюдение требований по уводу с орбиты',
     'stakeholder': {'name': 'Регулятор', 'role': 'regulator', 'priority': 5}},
]

SERVICES = [
    {'id': 'SV-0001', 'type': 'service', 'name': 'Сбор телеметрии', 'traces_up': ['ND-0001'],
     'qos_profiles': [
        {'consumer_class': 'A_prime', 'moe': [{'id': 'MOE-0001', 'name': 'delivery_probability_daily',
                                               'target': Q(0.90, '1')}]},
        {'consumer_class': 'B_prime', 'moe': [{'id': 'MOE-0002', 'name': 'delivery_probability_n_attempts',
                                               'target': Q(0.99, '1')}]}]},
    {'id': 'SV-0002', 'type': 'service', 'name': 'Оперативное управление', 'traces_up': ['ND-0002'],
     'qos_profiles': [
        {'consumer_class': 'C_prime', 'moe': [{'id': 'MOE-0003', 'name': 'reaction_time_probability',
                                               'target': Q(0.95, '1')}]}]},
]

# ---------- требования: два бюджетных дерева ----------
def req(rid, level, statement, mop, up, alloc=None, cat='performance', evs=None, status='Draft'):
    r = {'id': rid, 'type': 'requirement', 'level': level, 'statement': statement,
         'category': cat, 'mop': mop, 'traces_up': up, 'owner': 'вед. системный инженер',
         'lifecycle': {'status': status, 'version': '1'}, 'status': status}
    if alloc: r['allocated_to'] = alloc
    if evs:   r['verification_events'] = evs
    return r

EV_MASS_CALC = {'id': 'VE-0001', 'method': 'analysis', 'kind': 'preliminary', 'phase': 'PhaseA',
                'level': 'system', 'closes': False, 'status': 'passed',
                'approach': 'Суммирование масс подсистем по MEL с резервами по зрелости элементов',
                'means': 'Сводный перечень оборудования (MEL)', 'evidence_ref': 'EV-0001'}
EV_MASS_TEST = {'id': 'VE-0002', 'method': 'test', 'kind': 'qualification', 'phase': 'PhaseD',
                'level': 'system', 'closes': True, 'status': 'planned', 'design_version': 'v1',
                'approach': 'Взвешивание собранного аппарата после интеграции с фиксацией в протоколе',
                'means': 'Весовой стенд, поверенное оборудование'}
EV_T_MC = {'id': 'VE-0010', 'method': 'analysis', 'kind': 'preliminary', 'phase': 'PhaseA',
           'level': 'end_to_end', 'closes': False, 'status': 'passed',
           'approach': 'Монте-Карло по расписанию пролётов: распределение времени реакции по участкам',
           'means': 'Ядро моделирования потоков', 'evidence_ref': 'EV-0002'}
EV_T_DEMO = {'id': 'VE-0011', 'method': 'demonstration', 'kind': 'acceptance', 'phase': 'PhaseD',
             'level': 'end_to_end', 'closes': True, 'status': 'planned', 'unit': 'FM-01',
             'approach': 'Передача команды на макет терминала в худшей геометрии пролёта',
             'means': 'Наземный макет контура управления'}

REQUIREMENTS = [
    req('RQ-0100', 'system', 'Сухая масса космического аппарата не должна превышать 100 кг.',
        MOP('Сухая масса', 'le', 100, 'kg', 'sum'), [{'ref': 'ND-0003'}],
        [{'component': 'CM-0010'}], evs=[EV_MASS_CALC, EV_MASS_TEST]),
    req('RQ-0101', 'element', 'Масса платформы не должна превышать 60 кг.',
        MOP('Масса платформы', 'le', 60, 'kg'), [{'ref': 'RQ-0100'}], [{'component': 'CM-0011'}]),
    req('RQ-0102', 'element', 'Масса полезной нагрузки не должна превышать 30 кг.',
        MOP('Масса ПН', 'le', 30, 'kg'), [{'ref': 'RQ-0100'}], [{'component': 'CM-0012'}]),

    req('RQ-0110', 'system', 'Время реакции контура оперативного управления не должно превышать 120 с.',
        MOP('Время реакции', 'le', 120, 's', 'sum'),
        [{'ref': 'SV-0002', 'consumer_class': 'C_prime'}], [{'component': 'CM-0001'}],
        evs=[EV_T_MC, EV_T_DEMO]),
    req('RQ-0111', 'element', 'Ожидание восходящего канала не должно превышать 40 с.',
        MOP('Ожидание вверх', 'le', 40, 's'), [{'ref': 'RQ-0110'}], [{'component': 'CM-0012'}]),
    req('RQ-0112', 'element', 'Транзит до наземной станции не должен превышать 30 с.',
        MOP('Транзит вниз', 'le', 30, 's'), [{'ref': 'RQ-0110'}], [{'component': 'CM-0021'}]),
    req('RQ-0113', 'element', 'Доставка команды на терминал не должна превышать 35 с.',
        MOP('Доставка команды', 'le', 35, 's'), [{'ref': 'RQ-0110'}], [{'component': 'CM-0031'}]),

    req('RQ-0120', 'system', 'Вероятность доставки сообщения за сутки должна быть не менее 0,9.',
        MOP('Вероятность доставки', 'ge', 0.90, '1'),
        [{'ref': 'SV-0001', 'consumer_class': 'A_prime'}], [{'component': 'CM-0001'}],
        evs=[EV_T_MC]),
    req('RQ-0130', 'element', 'Интерфейс «Платформа — ПН» должен обеспечивать передачу не менее 2 Мбит/с.',
        MOP('Пропускная способность стыка', 'ge', 2e6, 'bit/s'), [{'ref': 'RQ-0100'}],
        [{'interface': 'IF-0001'}], cat='interface'),
]

LINKS = ([{'from': u['ref'], 'to': r['id'], 'kind': 'trace'}
          for r in REQUIREMENTS for u in r['traces_up']] +
         [{'from': p, 'to': c, 'kind': 'derive', 'derivation_kind': 'allocated'}
          for p, c in [('RQ-0100', 'RQ-0101'), ('RQ-0100', 'RQ-0102'),
                       ('RQ-0110', 'RQ-0111'), ('RQ-0110', 'RQ-0112'), ('RQ-0110', 'RQ-0113')]] +
         [{'from': 'RQ-0100', 'to': 'RQ-0130', 'kind': 'derive', 'derivation_kind': 'derived'}] +
         [{'from': r['id'], 'to': a['component'], 'kind': 'allocation'}
          for r in REQUIREMENTS for a in r.get('allocated_to', []) if 'component' in a])

EVIDENCE = [
    {'id': 'EV-0001', 'kind': 'analysis_report', 'maturity': 'preliminary',
     'source': {'scenario_ref': 'SC-0001'}, 'configuration': 'C1', 'date': '2026-04-10'},
    {'id': 'EV-0002', 'kind': 'model_run', 'maturity': 'preliminary',
     'source': {'scenario_ref': 'SC-0001'}, 'configuration': 'C1', 'date': '2026-05-02'},
]

VALIDATIONS = [
    {'id': 'VA-0001', 'target': 'ND-0001', 'conops_ref': 'CO-0001', 'product_kind': 'model',
     'method': 'analysis', 'phase': 'PhaseA', 'status': 'passed',
     'approach': 'Прогон сценария сбора телеметрии на модели миссии с картой спроса'},
    {'id': 'VA-0002', 'target': 'SV-0002', 'conops_ref': 'CO-0003', 'product_kind': 'model',
     'method': 'demonstration', 'phase': 'PhaseA', 'status': 'planned',
     'approach': 'Демонстрация контура управления на модели с худшей геометрией пролёта'},
]

RISKS = [
    {'id': 'RSK-0001', 'category': 'schedule', 'probability': 4, 'impact': 4,
     'statement': 'При задержке поставки приёмника — срыв срока интеграции — сдвиг SRR на два месяца',
     'owner': 'руководитель проекта', 'strategy': 'mitigate', 'actions': ['резервный поставщик'],
     'due': '2026-12-01', 'affects': ['CM-0012'], 'status': 'open'},
    {'id': 'RSK-0002', 'category': 'technical', 'probability': 2, 'impact': 5,
     'statement': 'При недостижении TRL 6 по антенне — пересмотр архитектуры ПН — потеря года разработки',
     'owner': 'вед. системный инженер', 'strategy': 'mitigate',
     'actions': ['резервное решение на патч-антенне'], 'due': '2027-03-01',
     'affects': ['RQ-0130'], 'status': 'open'},
    {'id': 'RSK-0003', 'category': 'cost', 'probability': 2, 'impact': 2,
     'statement': 'При росте курса — удорожание закупки — превышение бюджета фазы на 5 процентов',
     'owner': 'руководитель проекта', 'affects': ['CM-0001'], 'status': 'open'},
    {'id': 'RSK-0004', 'category': 'safety', 'probability': 1, 'impact': 5,
     'statement': 'При отказе увода — превышение срока существования — нарушение норм засорения',
     'owner': 'служба SMA', 'strategy': 'avoid', 'actions': ['ДУ с резервом ΔV'],
     'due': '2027-06-01', 'affects': ['RQ-0100'], 'status': 'open'},
    {'id': 'RSK-0005', 'category': 'technical', 'probability': 3, 'impact': 2,
     'statement': 'При росте числа терминалов — перегрузка канала — снижение доставки ниже 0,9',
     'owner': 'вед. системный инженер', 'affects': ['RQ-0120'], 'status': 'closed'},
]

POPULATIONS = [{'id': f'p{lat}', 'lat': lat, 'pop_density_per_km2': d,
                'terminals_per_capita': 0.02, 'msgs_per_terminal_day': 4, 'klass': 'A_prime'}
               for lat, d in [(15, 20), (30, 40), (45, 50), (55, 25), (70, 0.5)]]

OPTIONS = [{'name': 'Walker 40/5 · 550 км', 'quality': 0.82, 'cost': 100, 'reliability': 0.90,
            'energy': 68, 'deployment_days': 120, 'launch_campaigns': 1},
           {'name': 'Walker 24/3 · 700 км', 'quality': 0.61, 'cost': 62, 'reliability': 0.70,
            'energy': 71, 'deployment_days': 90, 'launch_campaigns': 1},
           {'name': 'ССО 30/3 · 600 км', 'quality': 0.55, 'cost': 78, 'reliability': 0.80,
            'energy': 80, 'deployment_days': 200, 'launch_campaigns': 2}]

def by_id(rid):
    return next(r for r in REQUIREMENTS if r['id'] == rid)

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("Состав проекта")
    check("нужды заданы", len(NEEDS) == 3)
    check("сервисы несут профили по классам",
          all(s['qos_profiles'] for s in SERVICES))
    check("требования покрывают три уровня",
          {r['level'] for r in REQUIREMENTS} == {'system', 'element'})
    check("состав системы содержит сегменты и интерфейсы",
          sum(1 for c in COMPONENTS.values() if c['kind'] == 'interface') == 2)
    check("риски охватывают все классы критичности",
          {criticality(r['probability'], r['impact']) for r in RISKS} == {'low', 'medium', 'high'})

    print("\nЦелостность трассировки")
    objs = ([{'id': n['id'], 'type': 'need'} for n in NEEDS] +
            [{'id': s['id'], 'type': 'service'} for s in SERVICES] +
            [{'id': r['id'], 'type': 'requirement', 'level': r['level'], 'status': r['status']}
             for r in REQUIREMENTS] +
            [{'id': cid, 'type': 'component'} for cid, c in COMPONENTS.items() if c['kind'] != 'interface'])
    gaps = trace_gaps(objs, LINKS)
    check("требований без источника нет", gaps == [], gaps)
    un, bare = allocation_coverage(objs, LINKS)
    check("нераспределённых системных требований нет", un == [], un)
    check("элементы без требований выявляются", isinstance(bare, list))
    check("ссылка на сервис несёт класс потребителя",
          all('consumer_class' in u for r in REQUIREMENTS for u in r['traces_up']
              if u['ref'].startswith('SV-')))

    print("\nКачество формулировок и условий")
    bad_q = {r['id']: check_quality(r) for r in REQUIREMENTS if check_quality(r)}
    check("замечаний к формулировкам нет", not bad_q, bad_q)
    bad_m = {r['id']: validate_mop(r['mop']) for r in REQUIREMENTS if validate_mop(r['mop'])}
    check("условия структурно полны", not bad_m, bad_m)
    mism = {r['id']: statement_matches_operator(r['statement'], r['mop'])
            for r in REQUIREMENTS if statement_matches_operator(r['statement'], r['mop'])}
    check("формулировка и оператор согласованы", not mism, mism)
    check("условие читается человеком", render(by_id('RQ-0100')['mop']) == 'не более 100 kg')

    print("\nСвёртка бюджетов")
    for pid, expect_used in (('RQ-0100', 90), ('RQ-0110', 105)):
        kids = rollup_children(pid, LINKS, REQUIREMENTS)
        r = rollup_check(by_id(pid), kids)
        check(f"{pid}: декомпозиция состоятельна", r['consistent'], r)
        check(f"{pid}: свёртка равна {expect_used}", r['aggregate'] == expect_used, r['aggregate'])
    check("производное требование в бюджет не входит",
          'RQ-0130' not in [k['id'] for k in rollup_children('RQ-0100', LINKS, REQUIREMENTS)])

    print("\nСогласованность деревьев")
    for pid, cid in (('RQ-0100', 'RQ-0101'), ('RQ-0110', 'RQ-0112')):
        okk, why = allocation_consistent(by_id(pid), by_id(cid), COMPONENTS)
        check(f"{cid} распределён внутри области {pid}", okk, why)
    check("интерфейсное требование распределено на интерфейс",
          interface_allocation_valid(by_id('RQ-0130'), COMPONENTS)[0])
    spec_pl = component_specification(REQUIREMENTS, 'CM-0011')
    check("спецификация платформы собирается", spec_pl == ['RQ-0101'], spec_pl)

    print("\nВерификация и валидация")
    check("масса: предварительно подтверждена, не верифицирована",
          verification_state(by_id('RQ-0100')) == 'предварительно подтверждено')
    check("требование без закрывающего события выявляется",
          verification_state(by_id('RQ-0120')) == 'план неполон: нет закрывающего события')
    ev_bad = {r['id']: [i for e in r.get('verification_events', []) for i in event_issues(e, r)]
              for r in REQUIREMENTS}
    check("события верификации описаны полностью",
          not any(ev_bad.values()), {k: v for k, v in ev_bad.items() if v})
    check("свидетельство действительно для текущей конфигурации",
          evidence_state(EVIDENCE[0], 'C1') == 'действительно')
    check("свидетельство прежней конфигурации неприменимо",
          evidence_state(EVIDENCE[0], 'C2') == 'неприменимо к текущей конфигурации')
    val_bad = {v['id']: validation_issues(v) for v in VALIDATIONS if validation_issues(v)}
    check("валидация привязана к ожиданиям, не к требованиям", not val_bad, val_bad)

    print("\nРиски")
    risk_bad = {r['id']: risk_issues(r) for r in RISKS if risk_issues(r)}
    check("записи реестра полны", not risk_bad, risk_bad)
    s = register_summary(RISKS)
    check("закрытый риск сохранён и исключён из активных",
          s['closed_retained'] == ['RSK-0005'] and s['active'] == 4)
    check("к эскалации отобраны высокие",
          set(s['escalate']) == {'RSK-0001', 'RSK-0002', 'RSK-0004'}, s['escalate'])
    check("редкое тяжёлое событие эскалируется наравне с частым",
          'RSK-0004' in s['escalate'] and criticality(1, 5) == 'high')

    print("\nСпрос и оценка построений")
    cells = build_demand_map(POPULATIONS)
    check("карта спроса построена", abs(sum(c['weight'] for c in cells.values()) - 1.0) < 1e-9)
    polar = lambda c: 1.0 if abs(c['lat']) >= 60 else 0.35
    mid = lambda c: 0.9 if abs(c['lat']) < 60 else 0.4
    check("полярное преимущество не выигрывает на населённой карте",
          demand_weighted_quality(cells, mid) > demand_weighted_quality(cells, polar))
    prof = latitude_profile(cells, mid)
    check("широтный профиль построен", abs(sum(p['w'] for p in prof) - 1.0) < 1e-9)

    print("\nПредставление")
    radar = radar_series(OPTIONS, ['quality', 'cost', 'reliability'])
    check("роза KPI построена", len(radar['series']) == 3)
    check("роза несёт состав набора нормировки", len(radar['normalized_over']) == 3)
    check("Парето-фронт вычислен",
          pareto_front(OPTIONS) == ['Walker 24/3 · 700 км', 'Walker 40/5 · 550 км'])
    bs = budget_segments(100, [{'label': 'Платформа', 'value': 60}, {'label': 'ПН', 'value': 30}])
    check("полоса бюджета показывает резерв", bs['remaining'] == 10 and not bs['overrun'])
    tree = build_tree([r['id'] for r in REQUIREMENTS], LINKS)
    check("дерево требований имеет два бюджетных корня",
          'RQ-0100' in tree['roots'] and 'RQ-0110' in tree['roots'])
    vm = verification_matrix(REQUIREMENTS)
    check("матрица верификации даёт строку на событие", len(vm['rows']) == 5, len(vm['rows']))
    shared = [r for r in vm['rows'] if r['event'] == 'VE-0010']
    check("одно событие может верифицировать несколько требований",
          len(shared) == 2 and {r['req'] for r in shared} == {'RQ-0110', 'RQ-0120'},
          [r['req'] for r in shared])
    check("требования без событий попадают в разрывы", len(vm['gaps']) >= 5, len(vm['gaps']))

    print("\nПакет передачи и зрелость")
    pkg = transfer_package({'requirements': [{'id': r['id'], 'status': r['status']} for r in REQUIREMENTS],
                            'architecture': list(COMPONENTS), 'parameters': [],
                            'verification_matrix': vm['rows'], 'modeling_reports': []})
    check("пакет собирается", pkg['complete'])
    check("небазированные требования — предупреждение, не отказ",
          len(pkg['warnings']) == len(REQUIREMENTS))
    gapsr = readiness(objs, 'SRR')
    check("отчёт зрелости к SRR показывает, что базировать",
          len(gapsr) > 0 and all('required' in g for g in gapsr))

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


def dump():
    """Выгрузка проекта одним документом.

    Нужна для того, чтобы заполнение базы и эталон брали данные ИЗ ОДНОГО
    ИСТОЧНИКА (STEP-7-9, ловушка 1). Вторая копия демо-данных разошлась бы
    с этой на первом же изменении модели, и разошлась бы молча.
    """
    return {
        'components': COMPONENTS,
        'needs': NEEDS,
        'services': SERVICES,
        'requirements': REQUIREMENTS,
        'links': LINKS,
        'evidence': EVIDENCE,
        'validations': VALIDATIONS,
        'risks': RISKS,
        'populations': POPULATIONS,
        'options': OPTIONS,
    }


if __name__ == '__main__':
    if '--dump' in sys.argv:
        import json as _json
        print(_json.dumps(dump(), ensure_ascii=False, sort_keys=True, indent=1))
    else:
        _run_checks()
