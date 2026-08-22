#!/usr/bin/env python3
"""Исполняемый эталон выходного контура (шаг 6): представление и экспорт.

Проверяется то, что легко сделать незаметно неверно: нормировка показателей
для лепестковой диаграммы, состав полос бюджета, сборка дерева из связей,
горизонты усреднения тепловой карты, детерминированность генерации документов
и сохранность данных при обмене.

  TZ-BAL-007  данные визуализации: роза KPI, широтный профиль, тепловые карты
  TZ-OUT-001  генерация документов из модели
  TZ-OUT-002  аналитические отчёты
  TZ-OUT-004  матрицы без ручного заполнения
  TZ-OUT-005  экспорт в форматы обмена
  TZ-OUT-006  пакет передачи
"""
import json, math, sys, hashlib

# ---------- роза KPI: нормировка с учётом направления ----------
HIGHER_IS_BETTER = {'quality', 'reliability', 'energy'}
LOWER_IS_BETTER  = {'cost', 'deployment_days', 'launch_campaigns'}

def normalize_axis(values, axis):
    """Нормировка по СРАВНИВАЕМОМУ НАБОРУ с учётом направления показателя.
    Без учёта направления дорогой вариант выглядит на диаграмме хорошим."""
    if axis not in HIGHER_IS_BETTER | LOWER_IS_BETTER:
        raise ValueError(f'направление показателя {axis} не задано')
    lo, hi = min(values), max(values)
    if math.isclose(lo, hi):
        return [1.0] * len(values)          # все равны — вырожденный случай, не деление на ноль
    span = hi - lo
    if axis in HIGHER_IS_BETTER:
        return [(v - lo) / span for v in values]
    return [(hi - v) / span for v in values]

def radar_series(options, axes):
    """Ряды для лепестковой диаграммы. Значения ОТНОСИТЕЛЬНЫ сравниваемому набору."""
    cols = {a: normalize_axis([o[a] for o in options], a) for a in axes}
    return {'axes': list(axes),
            'series': [{'name': o['name'], 'values': [cols[a][i] for a in axes]}
                       for i, o in enumerate(options)],
            'normalized_over': [o['name'] for o in options]}

def comparable(r1, r2):
    """Две диаграммы сопоставимы, только если нормированы по одному набору."""
    return r1['normalized_over'] == r2['normalized_over'] and r1['axes'] == r2['axes']

# ---------- Парето для отображения ----------
def pareto_front(options, x='cost', y='quality'):
    """x — меньше лучше, y — больше лучше."""
    front = []
    for a in options:
        dominated = any(b is not a and b[x] <= a[x] and b[y] >= a[y]
                        and (b[x] < a[x] or b[y] > a[y]) for b in options)
        if not dominated:
            front.append(a['name'])
    return sorted(front)

# ---------- полосы бюджета ----------
def budget_segments(limit, children):
    """Сегменты полосы: вклады потомков плюс остаток либо превышение."""
    used = sum(c['value'] for c in children)
    seg = [{'label': c['label'], 'value': c['value']} for c in children]
    if used <= limit:
        seg.append({'label': 'Резерв', 'value': limit - used, 'kind': 'reserve'})
        return {'segments': seg, 'used': used, 'limit': limit,
                'remaining': limit - used, 'overrun': False}
    return {'segments': seg, 'used': used, 'limit': limit,
            'remaining': limit - used, 'overrun': True, 'overrun_value': used - limit}

# ---------- дерево из связей ----------
def build_tree(nodes, links, kind='derive'):
    """Дерево требований строится из связей, а не хранится отдельно."""
    children = {}
    for l in links:
        if l.get('kind') == kind:
            children.setdefault(l['from'], []).append(l['to'])
    has_parent = {l['to'] for l in links if l.get('kind') == kind}
    roots = sorted(n for n in nodes if n not in has_parent)
    return {'roots': roots, 'children': {k: sorted(v) for k, v in children.items()}}

def tree_cycle(links, kind='derive'):
    adj = {}
    for l in links:
        if l.get('kind') == kind:
            adj.setdefault(l['from'], []).append(l['to'])
    seen, stack = set(), set()
    def walk(n):
        if n in stack: return True
        if n in seen: return False
        seen.add(n); stack.add(n)
        for m in adj.get(n, []):
            if walk(m): return True
        stack.discard(n); return False
    return any(walk(n) for n in list(adj))

def depth_of(tree, node, _d=0):
    for p, ch in tree['children'].items():
        if node in ch:
            return depth_of(tree, p, _d + 1)
    return _d

# ---------- тепловая карта: три горизонта ----------
def availability(cells, horizon, diurnal=None):
    """instant — срез; daily — среднее по суткам, взвешенное активностью;
    period — среднее по периоду. Простое среднее по часам без учёта активности
    завышает доступность, если пик спроса приходится на провал покрытия."""
    if horizon == 'instant':
        return {c['id']: c['series'][0] for c in cells}
    out = {}
    for c in cells:
        s = c['series']
        if horizon == 'daily':
            w = diurnal or [1.0] * len(s)
            W = sum(w[:len(s)])
            out[c['id']] = sum(v * w[i] for i, v in enumerate(s)) / W if W else 0.0
        elif horizon == 'period':
            out[c['id']] = sum(s) / len(s)
        else:
            raise ValueError(f'неизвестный горизонт: {horizon}')
    return out

# ---------- генерация документов ----------
def render_document(model, template):
    """Чистая функция модели: генерация не изменяет модель и воспроизводима."""
    body = {'template': template,
            'items': [{'id': o['id'], 'title': o.get('title', '')} for o in model['objects']]}
    return {'body': body,
            'digest': hashlib.blake2b(json.dumps(body, sort_keys=True,
                                                 ensure_ascii=False).encode(), digest_size=8).hexdigest()}

# ---------- матрицы ----------
def verification_matrix(reqs):
    """Строка на СОБЫТИЕ, не на требование. Пустые ячейки — разрывы, отдельным списком."""
    rows, gaps = [], []
    for r in reqs:
        evs = r.get('verification_events', [])
        if not evs:
            gaps.append({'req': r['id'], 'reason': 'нет событий верификации'}); continue
        for e in evs:
            rows.append({'req': r['id'], 'event': e['id'], 'method': e['method'],
                         'level': e['level'], 'closes': e['closes'],
                         'approach': e.get('approach', ''), 'status': e['status'],
                         'evidence': e.get('evidence_ref'),
                         'evidence_stale': bool(e.get('evidence_stale'))})
            if not e.get('approach'):
                gaps.append({'req': r['id'], 'event': e['id'], 'reason': 'не описан подход'})
    return {'rows': rows, 'gaps': gaps}

# ---------- экспорт ----------
# Только направление наружу. Обратное (`from_exchange`) убрано Шагом 16 §2.1:
# ввод идёт настоящим ReqIF через службу обмена (ADR-023), и круговой обмен
# проверяется на нём — tools/check_reqif_roundtrip.py, со сверкой по XSD OMG.
# Второй формат ввода означал бы вторую семантику приёма.
def to_exchange(reqs, links):
    return {'requirements': [{'id': r['id'], 'attributes': dict(r.get('attributes', {}))} for r in reqs],
            'links': [dict(l) for l in links]}

# ---------- пакет передачи ----------
PACKAGE_PARTS = ['requirements', 'architecture', 'parameters', 'verification_matrix', 'modeling_reports']

def transfer_package(model):
    missing = [p for p in PACKAGE_PARTS if p not in model]
    not_baselined = [o['id'] for o in model.get('requirements', [])
                     if o.get('status') != 'Baseline']
    return {'complete': not missing, 'missing': missing, 'warnings': not_baselined}

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    OPTS = [{'name': 'Walker 40/5', 'quality': 0.82, 'cost': 100, 'reliability': 0.9,
             'energy': 68, 'deployment_days': 120, 'launch_campaigns': 1},
            {'name': 'Walker 24/3', 'quality': 0.61, 'cost': 62, 'reliability': 0.7,
             'energy': 71, 'deployment_days': 90, 'launch_campaigns': 1},
            {'name': 'ССО 30/3', 'quality': 0.55, 'cost': 78, 'reliability': 0.8,
             'energy': 80, 'deployment_days': 200, 'launch_campaigns': 2}]

    print("Роза KPI: нормировка с учётом направления")
    q = normalize_axis([o['quality'] for o in OPTS], 'quality')
    c = normalize_axis([o['cost'] for o in OPTS], 'cost')
    check("лучшее качество даёт 1.0", math.isclose(max(q), 1.0) and q.index(max(q)) == 0)
    check("наименьшая стоимость даёт 1.0", math.isclose(max(c), 1.0) and c.index(max(c)) == 1)
    check("дорогой вариант не выглядит хорошим", c[0] < c[1], f"{c[0]:.2f} vs {c[1]:.2f}")
    check("вырожденный случай не делит на ноль",
          normalize_axis([5, 5, 5], 'cost') == [1.0, 1.0, 1.0])
    try:
        normalize_axis([1, 2], 'unknown_axis'); check("показатель без направления отклонён", False)
    except ValueError:
        check("показатель без направления отклонён", True)

    print("\nРоза KPI: сопоставимость диаграмм")
    r3 = radar_series(OPTS, ['quality', 'cost', 'reliability'])
    r2 = radar_series(OPTS[:2], ['quality', 'cost', 'reliability'])
    check("значения относительны сравниваемому набору", r3['normalized_over'] != r2['normalized_over'])
    check("диаграммы разных наборов несопоставимы", not comparable(r3, r2))
    check("та же диаграмма сопоставима сама с собой",
          comparable(r3, radar_series(OPTS, ['quality', 'cost', 'reliability'])))
    # Нормировка зависит от границ набора: удаление крайнего варианта смещает
    # значения промежуточных, а значения самих крайних не меняет.
    check("удаление крайнего варианта меняет значение промежуточного",
          not math.isclose(r3['series'][1]['values'][0], r2['series'][1]['values'][0]),
          f"{r3['series'][1]['values'][0]:.3f} → {r2['series'][1]['values'][0]:.3f}")
    check("значение крайнего варианта при этом не меняется",
          math.isclose(r3['series'][0]['values'][0], r2['series'][0]['values'][0]))
    check("диаграмма несёт состав набора, по которому нормирована",
          'normalized_over' in r3 and len(r3['normalized_over']) == 3)
    check("число осей совпадает с числом значений",
          all(len(s['values']) == len(r3['axes']) for s in r3['series']))

    print("\nПарето для отображения")
    front = pareto_front(OPTS)
    check("недоминируемые определены", front == ['Walker 24/3', 'Walker 40/5'], front)
    check("доминируемый вариант исключён", 'ССО 30/3' not in front)
    check("единственный вариант доминируем быть не может",
          pareto_front([OPTS[0]]) == ['Walker 40/5'])

    print("\nПолосы бюджета")
    b = budget_segments(100, [{'label': 'Платформа', 'value': 60}, {'label': 'ПН', 'value': 30}])
    check("сегменты дополнены резервом", b['segments'][-1]['kind'] == 'reserve')
    check("сумма сегментов равна пределу",
          math.isclose(sum(s['value'] for s in b['segments']), 100))
    check("остаток вычислен", b['remaining'] == 10 and not b['overrun'])
    o = budget_segments(100, [{'label': 'Платформа', 'value': 60}, {'label': 'ПН', 'value': 52}])
    check("превышение помечено, а не обрезано", o['overrun'] and o['overrun_value'] == 12)
    check("при превышении резерв не добавляется",
          all(s.get('kind') != 'reserve' for s in o['segments']))
    check("остаток при превышении отрицателен", o['remaining'] == -12)

    print("\nДерево из связей")
    NODES = ['RQ-0100', 'RQ-0101', 'RQ-0102', 'RQ-0110']
    LINKS = [{'from': 'RQ-0100', 'to': 'RQ-0101', 'kind': 'derive'},
             {'from': 'RQ-0100', 'to': 'RQ-0102', 'kind': 'derive'},
             {'from': 'RQ-0100', 'to': 'RQ-0110', 'kind': 'trace'}]
    t = build_tree(NODES, LINKS)
    check("корни определены", t['roots'] == ['RQ-0100', 'RQ-0110'], t['roots'])
    check("потомки только по связи derive", t['children']['RQ-0100'] == ['RQ-0101', 'RQ-0102'])
    check("связь trace деревом не является", 'RQ-0110' in t['roots'])
    check("глубина потомка равна 1", depth_of(t, 'RQ-0101') == 1)
    check("цикла нет", not tree_cycle(LINKS))
    check("цикл выявляется",
          tree_cycle(LINKS + [{'from': 'RQ-0101', 'to': 'RQ-0100', 'kind': 'derive'}]))

    print("\nТепловая карта: три горизонта")
    CELLS = [{'id': 'c1', 'series': [1.0, 1.0, 0.0, 0.0]}]
    peak_at_gap = [0.2, 0.2, 3.0, 3.0]      # пик активности приходится на провал покрытия
    inst = availability(CELLS, 'instant')
    per = availability(CELLS, 'period')
    day = availability(CELLS, 'daily', diurnal=peak_at_gap)
    check("мгновенный срез берёт первое значение", inst['c1'] == 1.0)
    check("среднее за период не взвешено", math.isclose(per['c1'], 0.5))
    check("взвешивание активностью занижает доступность", day['c1'] < per['c1'],
          f"{day['c1']:.3f} < {per['c1']:.3f}")
    check("невзвешенное среднее завысило бы доступность", per['c1'] > day['c1'])
    try:
        availability(CELLS, 'weekly'); check("неизвестный горизонт отклонён", False)
    except ValueError:
        check("неизвестный горизонт отклонён", True)

    print("\nГенерация документов")
    MODEL = {'objects': [{'id': 'RQ-0100', 'title': 'Масса КА'}]}
    snapshot = json.dumps(MODEL, sort_keys=True)
    d1 = render_document(MODEL, 'req_spec')
    d2 = render_document(MODEL, 'req_spec')
    check("повторная генерация идентична", d1['digest'] == d2['digest'])
    check("генерация не изменяет модель", json.dumps(MODEL, sort_keys=True) == snapshot)
    check("другой шаблон даёт другой результат",
          render_document(MODEL, 'conops')['digest'] != d1['digest'])

    print("\nМатрица верификации")
    REQS = [{'id': 'RQ-0100', 'verification_events': [
                {'id': 'VE-0001', 'method': 'analysis', 'level': 'system', 'closes': False,
                 'approach': 'Суммирование масс по MEL', 'status': 'passed',
                 'evidence_ref': 'EV-0001', 'evidence_stale': True},
                {'id': 'VE-0002', 'method': 'test', 'level': 'system', 'closes': True,
                 'approach': 'Взвешивание после интеграции', 'status': 'planned'}]},
            {'id': 'RQ-0200', 'verification_events': [
                {'id': 'VE-0003', 'method': 'inspection', 'level': 'component',
                 'closes': True, 'status': 'planned'}]},
            {'id': 'RQ-0300'}]
    m = verification_matrix(REQS)
    check("строка на событие, а не на требование", len(m['rows']) == 3)
    check("требование без событий попадает в разрывы",
          any(g['req'] == 'RQ-0300' for g in m['gaps']))
    check("событие без подхода попадает в разрывы",
          any(g.get('event') == 'VE-0003' for g in m['gaps']))
    check("устаревшее свидетельство помечено в строке",
          any(r['evidence_stale'] for r in m['rows']))
    check("ячейки не заполняются вручную", all('approach' in r for r in m['rows']))

    print("\nЭкспорт и обмен")
    EX_REQS = [{'id': 'RQ-0100', 'attributes': {'statement': 'a', 'operator': 'le', 'value': 100}}]
    EX_LINKS = [{'from': 'ND-0001', 'to': 'RQ-0100', 'kind': 'trace'}]
    doc = to_exchange(EX_REQS, EX_LINKS)
    check("атрибуты сохраняются при выгрузке", doc['requirements'] == EX_REQS, doc['requirements'])
    check("связи сохраняются при выгрузке", doc['links'] == EX_LINKS)
    check("незнакомый атрибут не теряется молча",
          to_exchange([{'id': 'R', 'attributes': {'custom_x': 7}}], [])['requirements'][0]['attributes']['custom_x'] == 7)

    print("\nПакет передачи")
    full = {p: [] for p in PACKAGE_PARTS}
    full['requirements'] = [{'id': 'RQ-0100', 'status': 'Baseline'},
                            {'id': 'RQ-0101', 'status': 'Draft'}]
    p = transfer_package(full)
    check("полный пакет собран", p['complete'])
    check("небазированное перечислено предупреждением", p['warnings'] == ['RQ-0101'])
    part = transfer_package({'requirements': []})
    check("неполный пакет выявлен", not part['complete'] and 'architecture' in part['missing'])

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
