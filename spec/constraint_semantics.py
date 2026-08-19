#!/usr/bin/env python3
"""Исполняемый эталон условий требований и свёртки бюджетов (CR-001, ADR-017).

  оператор     «не более 500 г» ≠ «не менее 500 г» ≠ «ровно 500 г ± 5 г»
  верификация  результат сравнивается ПО ОПЕРАТОРУ, а не по умолчанию
  свёртка      сумма дочерних бюджетов против родительского (связь derive)
  единицы      свёртка величин в разных единицах отклоняется, а не приводится молча
  согласие     формулировка и оператор не должны противоречить друг другу
"""
import math, sys

# ---------- оператор ----------
def satisfies(mop, actual):
    """Выполняется ли условие требования фактическим значением."""
    op, v = mop['operator'], mop['value']['value']
    if op == 'le': return actual <= v
    if op == 'ge': return actual >= v
    if op == 'lt': return actual <  v
    if op == 'gt': return actual >  v
    if op == 'eq': return actual == v
    if op == 'range': return v <= actual <= mop['upper']['value']
    if op == 'tolerance': return abs(actual - v) <= mop['tolerance']['value']
    raise ValueError(f'неизвестный оператор: {op}')

# Единицы хранятся кодами СИ (CLAUDE.md §3); подписи — забота представления.
# Точка подключения обязана быть в модели, иначе локализацию зашьют в код.
UNIT_LABELS_RU = {'g':'г','kg':'кг','m':'м','km':'км','s':'с','W':'Вт','Wh':'Вт·ч',
                  'Hz':'Гц','dB':'дБ','degC':'°C','K':'К','bit/s':'бит/с','1':''}

def render(mop, unit_label=lambda u: u):
    """Человекочитаемая запись условия. unit_label — подстановка подписи единицы."""
    u = unit_label(mop['value']['unit']); v = mop['value']['value']
    if mop['operator'] == 'le': return f'не более {v} {u}'
    if mop['operator'] == 'ge': return f'не менее {v} {u}'
    if mop['operator'] == 'lt': return f'менее {v} {u}'
    if mop['operator'] == 'gt': return f'более {v} {u}'
    if mop['operator'] == 'eq': return f'ровно {v} {u}'
    if mop['operator'] == 'range': return f'от {v} до {mop["upper"]["value"]} {u}'
    if mop['operator'] == 'tolerance': return f'{v} ± {mop["tolerance"]["value"]} {u}'

def validate_mop(mop):
    """Структурная целостность условия."""
    e = []
    if 'operator' not in mop: e.append('оператор обязателен')
    if 'value' not in mop: e.append('значение обязательно')
    elif not mop['value'].get('unit'): e.append('единица обязательна')
    if mop.get('operator') == 'range' and 'upper' not in mop: e.append('range требует upper')
    if mop.get('operator') == 'tolerance' and 'tolerance' not in mop: e.append('tolerance требует допуска')
    if mop.get('operator') == 'range' and 'upper' in mop:
        if mop['upper']['value'] <= mop['value']['value']: e.append('верхняя граница не выше нижней')
        if mop['upper']['unit'] != mop['value']['unit']: e.append('единицы границ диапазона различны')
    return e

# ---------- согласованность формулировки и оператора ----------
PHRASE_OP = [('не более', 'le'), ('не превыша', 'le'), ('не должна превыша', 'le'),
             ('не менее', 'ge'), ('не ниже', 'ge'), ('ровно', 'eq'),
             ('в пределах', 'range'), ('от ', 'range')]

def statement_matches_operator(statement, mop):
    """Формулировка и оператор не должны противоречить. Возвращает None либо описание расхождения."""
    low = statement.lower()
    for phrase, op in PHRASE_OP:
        if phrase in low:
            if op != mop['operator'] and not (op == 'range' and phrase == 'от '):
                return f'формулировка говорит «{phrase}» ({op}), а оператор — {mop["operator"]}'
            return None
    return None

# ---------- свёртка бюджетов по декомпозиции ----------
def rollup_check(parent, children):
    """Состоятельность декомпозиции: свёртка дочерних против родительского бюджета."""
    pm = parent['mop']
    rule = pm.get('rollup', 'none')
    if rule == 'none':
        return {'applicable': False}
    units = {c['mop']['value']['unit'] for c in children} | {pm['value']['unit']}
    if len(units) > 1:
        return {'applicable': True, 'error': f'единицы не совпадают: {sorted(units)}'}
    vals = [c['mop']['value']['value'] for c in children]
    if not vals:
        return {'applicable': True, 'error': 'нет дочерних требований'}
    agg = {'sum': sum(vals), 'max': max(vals), 'min': min(vals)}[rule]
    limit = pm['value']['value']
    ok = satisfies(pm, agg)
    return {'applicable': True, 'rule': rule, 'aggregate': agg, 'limit': limit,
            'consistent': ok, 'remaining': limit - agg if pm['operator'] in ('le','lt') else None,
            'unit': pm['value']['unit']}

def q(v, u): return {'value': v, 'unit': u, 'provenance': {'source': 'manual'}}

# ================= проверки =================
# Проверки исполняются только при прямом запуске: этот модуль импортируется
# эталоном requirements_semantics.py, и sys.exit при импорте обрывал бы его
# на середине — CI видел бы код 0 при невыполненных проверках.
if __name__ == '__main__':
    ok = fail = 0
    def check(name, cond, detail=''):
        global ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("Оператор различает требования (дефект CR-001)")
    le500 = {'name':'Сухая масса','operator':'le','value':q(500,'g')}
    ge500 = {'name':'Сухая масса','operator':'ge','value':q(500,'g')}
    eq500 = {'name':'Сухая масса','operator':'tolerance','value':q(500,'g'),'tolerance':q(5,'g')}
    check("«не более 500 г»: 480 проходит", satisfies(le500, 480))
    check("«не более 500 г»: 520 не проходит", not satisfies(le500, 520))
    check("«не менее 500 г»: 480 НЕ проходит", not satisfies(ge500, 480))
    check("«не менее 500 г»: 520 проходит", satisfies(ge500, 520))
    check("одно значение, разные операторы → разный вердикт",
          satisfies(le500, 480) != satisfies(ge500, 480))
    check("«ровно 500 ± 5»: 503 проходит", satisfies(eq500, 503))
    check("«ровно 500 ± 5»: 507 не проходит", not satisfies(eq500, 507))
    rng = {'name':'Температура','operator':'range','value':q(-20,'degC'),'upper':q(50,'degC')}
    check("диапазон: 25 внутри", satisfies(rng, 25))
    check("диапазон: 60 снаружи", not satisfies(rng, 60))
    check("строгий оператор отличается от нестрогого",
          satisfies(le500, 500) and not satisfies({'operator':'lt','value':q(500,'g')}, 500))

    print("\nЧитаемая запись условия")
    ru = lambda u: UNIT_LABELS_RU.get(u, u)
    check("le → «не более»", render(le500) == 'не более 500 g', render(le500))
    check("ge → «не менее»", render(ge500) == 'не менее 500 g', render(ge500))
    check("tolerance → «±»", render(eq500) == '500 ± 5 g', render(eq500))
    check("range → «от … до …»", render(rng) == 'от -20 до 50 degC', render(rng))
    check("подпись единицы подставляется, код в модели не меняется",
          render(le500, ru) == 'не более 500 г' and le500['value']['unit'] == 'g',
          render(le500, ru))
    check("неизвестная единица выводится кодом, а не теряется",
          render({'operator':'le','value':q(5,'sr')}, ru) == 'не более 5 sr')

    print("\nСтруктурная целостность условия")
    check("корректное условие без замечаний", validate_mop(le500) == [])
    check("отсутствие оператора выявлено", 'оператор обязателен' in validate_mop({'value':q(1,'kg')}))
    check("отсутствие единицы выявлено",
          any('единица' in e for e in validate_mop({'operator':'le','value':{'value':1,'unit':'','provenance':{'source':'manual'}}})))
    check("range без upper отклонён", any('upper' in e for e in validate_mop({'operator':'range','value':q(1,'kg')})))
    check("tolerance без допуска отклонён", any('допуск' in e for e in validate_mop({'operator':'tolerance','value':q(1,'kg')})))
    check("перевёрнутый диапазон отклонён",
          any('не выше' in e for e in validate_mop({'operator':'range','value':q(50,'degC'),'upper':q(-20,'degC')})))
    check("разные единицы границ отклонены",
          any('единицы границ' in e for e in validate_mop({'operator':'range','value':q(0,'degC'),'upper':q(300,'K')})))

    print("\nСогласованность формулировки и оператора")
    check("«не более» + le — согласовано",
          statement_matches_operator('Масса КА не должна превышать 500 г.', le500) is None)
    check("«не более» + ge — расхождение выявлено",
          statement_matches_operator('Масса КА не должна превышать 500 г.', ge500) is not None)
    check("«не менее» + ge — согласовано",
          statement_matches_operator('Запас линии должен быть не менее 3 дБ.', ge500) is None)

    print("\nСвёртка бюджетов по декомпозиции (derive)")
    parent = {'id':'RQ-0100','mop':{'name':'Сухая масса КА','operator':'le','value':q(100,'kg'),'rollup':'sum'}}
    kids_ok  = [{'id':'RQ-0101','mop':{'name':'Платформа','operator':'le','value':q(60,'kg')}},
                {'id':'RQ-0102','mop':{'name':'ПН','operator':'le','value':q(30,'kg')}}]
    kids_bad = [{'id':'RQ-0101','mop':{'name':'Платформа','operator':'le','value':q(60,'kg')}},
                {'id':'RQ-0102','mop':{'name':'ПН','operator':'le','value':q(50,'kg')}}]
    r1, r2 = rollup_check(parent, kids_ok), rollup_check(parent, kids_bad)
    check("состоятельная декомпозиция принята", r1['consistent'], r1)
    check("остаток бюджета вычислен", abs(r1['remaining'] - 10) < 1e-9, r1['remaining'])
    check("превышение родительского бюджета выявлено", not r2['consistent'], r2)
    check("величина превышения видна", r2['remaining'] < 0, r2['remaining'])
    check("разные единицы дочерних отклонены, а не приведены молча",
          'error' in rollup_check(parent, [{'id':'x','mop':{'operator':'le','value':q(60000,'g')}}]))
    check("отсутствие потомков — не молчаливое согласие",
          'error' in rollup_check(parent, []))
    check("rollup=none не проверяется",
          rollup_check({'mop':{'operator':'le','value':q(3,'dB'),'rollup':'none'}}, kids_ok)['applicable'] is False)
    lat = {'mop':{'name':'Задержка','operator':'le','value':q(120,'s'),'rollup':'sum'}}
    segs = [{'mop':{'value':q(40,'s'),'operator':'le'}},{'mop':{'value':q(30,'s'),'operator':'le'}},
            {'mop':{'value':q(35,'s'),'operator':'le'}}]
    check("бюджет времени реакции складывается по участкам",
          rollup_check(lat, segs)['consistent'] and rollup_check(lat, segs)['aggregate'] == 105)
    tmax = {'mop':{'name':'Предельная температура','operator':'le','value':q(60,'degC'),'rollup':'max'}}
    check("предельные величины сворачиваются по максимуму",
          rollup_check(tmax, [{'mop':{'value':q(55,'degC'),'operator':'le'}},
                              {'mop':{'value':q(58,'degC'),'operator':'le'}}])['aggregate'] == 58)

    print("\nВерификация по оператору, а не по умолчанию")
    def verification_status(mop, actual, stale=False):
        if stale or actual is None: return 'не проверено'
        return 'выполнено' if satisfies(mop, actual) else 'не выполнено'
    check("масса 480 г при «не более 500» — выполнено", verification_status(le500, 480) == 'выполнено')
    check("масса 480 г при «не менее 500» — НЕ выполнено", verification_status(ge500, 480) == 'не выполнено')
    check("устаревшее свидетельство не засчитано", verification_status(le500, 480, stale=True) == 'не проверено')

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)
