#!/usr/bin/env python3
"""Исполняемый эталон поведения контура требований (шаг 2).

Спецификация, а не пример: реализация обязана вести себя так же.
  TZ-REQ-002  QoS-профили по классам; непокрытый класс выявляется
  TZ-REQ-003  трассировка: разрывы, ссылка на сервис без класса
  TZ-REQ-004  правила качества формулировок
  TZ-REQ-005  flow down: покрытие в обе стороны
  TZ-REQ-006  базирование блокируется при незакрытых условиях
  TZ-REQ-007  матрица верификации; stale-свидетельство не засчитывается
  TZ-REQ-008  готовность к контрольной точке
"""
import re, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# ---------- TZ-REQ-004: правила качества формулировок ----------
VAGUE = ['достаточн', 'оптимальн', 'максимально возможн', 'при необходимости',
         'по возможности', 'соответствующ', 'надлежащ', 'минимально необходим']
MEASURED_CATEGORIES = {'performance', 'reliability'}

def check_quality(req):
    """Возвращает список нарушений. Пустой список = требование пригодно к базированию."""
    v, text = [], req.get('statement', '')
    low = text.lower()
    if 'должна' not in low and 'должен' not in low and 'должно' not in low:
        v.append('нет модального «должна»')
    # конъюнкция в нормативной части: два независимых требования в одном
    if re.search(r'\bи\b.*\bдолжн', low) or ' и/или ' in low:
        v.append('конъюнкция: разделить на отдельные требования')
    for w in VAGUE:
        if w in low:
            v.append(f'неизмеримое определение: «{w}»'); break
    if req.get('category') in MEASURED_CATEGORIES and not req.get('mop'):
        v.append(f'категория {req["category"]} требует измеримого показателя (MOP)')
    if not text.strip():
        v.append('пустая формулировка')
    return v

def has_open_tbd(req):
    mop = req.get('mop') or {}
    return bool(mop.get('tbd') or mop.get('tbr'))

# ---------- TZ-REQ-003 / 005: целостность связей ----------
def trace_gaps(objects, links):
    """Требования без источника (вверх) — разрыв цифровой нити."""
    has_parent = {l['to'] for l in links if l['kind'] == 'trace'}
    return sorted(o['id'] for o in objects
                  if o['type'] == 'requirement' and o.get('status') != 'Cancelled'
                  and o['id'] not in has_parent)

def service_link_valid(link, objects):
    """Ссылка требования на сервис обязана нести класс потребителя (Р9)."""
    target = next((o for o in objects if o['id'] == link['from']), None)
    if target and target['type'] == 'service':
        return bool(link.get('consumer_class'))
    return True

def allocation_coverage(objects, links):
    """Возвращает (нераспределённые системные требования, элементы без требований)."""
    alloc = [l for l in links if l['kind'] == 'allocation']
    allocated = {l['from'] for l in alloc}
    covered_elements = {l['to'] for l in alloc}
    unallocated = sorted(o['id'] for o in objects
                         if o['type'] == 'requirement' and o.get('level') == 'system'
                         and o.get('status') != 'Cancelled' and o['id'] not in allocated)
    bare = sorted(o['id'] for o in objects
                  if o['type'] == 'component' and o['id'] not in covered_elements)
    return unallocated, bare

def uncovered_consumer_classes(service, demand_classes):
    """TZ-REQ-002: класс есть в карте спроса, но профиля у сервиса нет."""
    profiled = {p['consumer_class'] for p in service.get('qos_profiles', [])}
    return sorted(demand_classes - profiled)

# ---------- TZ-REQ-007: верификация ----------
def verification_status(req, results):
    """не проверено | выполнено | не выполнено. Устаревшее свидетельство не засчитывается.
    Сравнение выполняется ПО ОПЕРАТОРУ условия (ADR-017), а не по умолчанию."""
    ver = req.get('verification') or {}
    if not ver.get('method'):
        return 'не проверено'
    ref = ver.get('evidence_ref')
    if not ref:
        return 'не проверено'
    res = results.get(ref)
    if res is None or res.get('stale'):
        return 'не проверено'
    mop = req.get('mop') or {}
    if 'operator' not in mop or 'value' not in mop:
        return 'не проверено'
    from constraint_semantics import satisfies
    return 'выполнено' if satisfies(mop, res.get('value')) else 'не выполнено'

# ---------- TZ-REQ-006 / 008: базирование и зрелость ----------
def can_baseline(req, results):
    """Условия перевода требования в Baseline."""
    reasons = []
    reasons += check_quality(req)
    if has_open_tbd(req):
        reasons.append('незакрытые TBD/TBR')
    if not (req.get('verification') or {}).get('method'):
        reasons.append('не назначен метод верификации')
    return (not reasons), reasons

ORDER = ['Draft', 'Preliminary', 'Approved', 'Baseline']
GATES = {'SRR': {'requirement': 'Baseline', 'service': 'Approved', 'component': 'Preliminary'},
         'SDR': {'requirement': 'Baseline', 'service': 'Baseline', 'component': 'Approved'}}

def readiness(objects, gate):
    """Объекты, не достигшие требуемой зрелости к контрольной точке."""
    req_map, gaps = GATES[gate], []
    for o in objects:
        need = req_map.get(o['type'])
        if not need or o.get('status') == 'Cancelled':
            continue
        if ORDER.index(o.get('status', 'Draft')) < ORDER.index(need):
            gaps.append({'id': o['id'], 'actual': o.get('status', 'Draft'), 'required': need})
    return sorted(gaps, key=lambda g: g['id'])

# ================= проверки =================
# Проверки исполняются только при прямом запуске: модуль импортируется
# другими эталонами (в частности demo_project.py), и sys.exit при импорте
# обрывал бы их на середине — CI видел бы код 0 при невыполненных проверках.
if __name__ == '__main__':
    ok = fail = 0
    def check(name, cond, detail=''):
        global ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("TZ-REQ-004: качество формулировок")
    good = {'id':'RQ-0001','statement':'Система должна обеспечивать вероятность доставки не менее 0,9 за сутки.',
            'category':'performance','mop':{'name':'Вероятность доставки','operator':'ge',
                                            'value':{'value':0.9,'unit':'1'}}}
    check("корректное требование без замечаний", check_quality(good) == [], check_quality(good))
    check("нет модального «должна»",
          'нет модального «должна»' in check_quality({'id':'x','statement':'Обеспечивается доставка данных.','category':'functional'}))
    check("неизмеримое определение выявлено",
          any('неизмеримое' in v for v in check_quality(
              {'id':'x','statement':'Система должна обеспечивать достаточную пропускную способность.','category':'functional'})))
    check("performance без MOP отклонено",
          any('MOP' in v for v in check_quality(
              {'id':'x','statement':'Система должна обеспечивать высокую доступность сервиса.','category':'performance'})))
    check("конъюнкция выявлена",
          any('онъюнкц' in v for v in check_quality(
              {'id':'x','statement':'Система должна принимать данные и должна их передавать.','category':'functional'})))
    check("пустая формулировка отклонена",
          any('пустая' in v for v in check_quality({'id':'x','statement':'   ','category':'functional'})))

    print("\nTZ-REQ-003 / TZ-REQ-005: целостность связей")
    objs = [{'id':'ND-0001','type':'need'},{'id':'SV-0001','type':'service'},
            {'id':'RQ-0001','type':'requirement','level':'system'},
            {'id':'RQ-0002','type':'requirement','level':'system'},
            {'id':'CM-0001','type':'component'},{'id':'CM-0002','type':'component'}]
    links = [{'from':'ND-0001','to':'SV-0001','kind':'trace'},
             {'from':'SV-0001','to':'RQ-0001','kind':'trace','consumer_class':'A_prime'},
             {'from':'RQ-0001','to':'CM-0001','kind':'allocation'}]
    check("требование без источника выявлено", trace_gaps(objs, links) == ['RQ-0002'], trace_gaps(objs, links))
    check("ссылка на сервис с классом принята",
          service_link_valid({'from':'SV-0001','to':'RQ-0001','consumer_class':'A_prime'}, objs))
    check("ссылка на сервис без класса отклонена",
          not service_link_valid({'from':'SV-0001','to':'RQ-0002'}, objs))
    un, bare = allocation_coverage(objs, links)
    check("нераспределённое требование выявлено", un == ['RQ-0002'], un)
    check("элемент без требований выявлен", bare == ['CM-0002'], bare)

    print("\nTZ-REQ-002: покрытие классов потребителей")
    svc = {'id':'SV-0001','qos_profiles':[{'consumer_class':'A_prime'},{'consumer_class':'B_prime'}]}
    check("непокрытый класс выявлен",
          uncovered_consumer_classes(svc, {'A_prime','B_prime','C_prime'}) == ['C_prime'])
    check("полное покрытие не даёт замечаний",
          uncovered_consumer_classes(svc, {'A_prime','B_prime'}) == [])

    print("\nTZ-REQ-007: верификация и свидетельства")
    results = {'RES-1':{'value':0.94,'stale':False}, 'RES-2':{'value':0.94,'stale':True},
               'RES-3':{'value':0.80,'stale':False}}
    base = {'mop':{'name':'Доставка','operator':'ge','value':{'value':0.9,'unit':'1'}}}
    check("свидетельство подтверждает выполнение",
          verification_status({**base,'verification':{'method':'analysis','evidence_ref':'RES-1'}}, results) == 'выполнено')
    check("устаревшее свидетельство не засчитано",
          verification_status({**base,'verification':{'method':'analysis','evidence_ref':'RES-2'}}, results) == 'не проверено')
    check("недостижение цели выявлено",
          verification_status({**base,'verification':{'method':'analysis','evidence_ref':'RES-3'}}, results) == 'не выполнено')
    check("без метода — не проверено",
          verification_status({**base,'verification':{}}, results) == 'не проверено')

    print("\nTZ-REQ-006: условия базирования")
    ready = {**good, 'verification':{'method':'analysis','evidence_ref':'RES-1'}}
    okb, why = can_baseline(ready, results)
    check("пригодное требование базируется", okb, why)
    okb2, why2 = can_baseline({**ready, 'mop':{'name':'Доставка','operator':'ge','value':{'value':0.9,'unit':'1'},'tbd':True}}, results)
    check("незакрытый TBD блокирует базирование", not okb2 and any('TBD' in w for w in why2), why2)
    okb3, why3 = can_baseline({**good}, results)
    check("отсутствие метода верификации блокирует", not okb3 and any('верификации' in w for w in why3), why3)

    print("\nTZ-REQ-008: готовность к контрольной точке")
    pkg = [{'id':'RQ-0001','type':'requirement','status':'Baseline'},
           {'id':'RQ-0002','type':'requirement','status':'Preliminary'},
           {'id':'SV-0001','type':'service','status':'Approved'},
           {'id':'CM-0001','type':'component','status':'Draft'},
           {'id':'RQ-0009','type':'requirement','status':'Cancelled'}]
    g = readiness(pkg, 'SRR')
    check("к SRR выявлены только незрелые", [x['id'] for x in g] == ['CM-0001','RQ-0002'], [x['id'] for x in g])
    check("Cancelled не попадает в отчёт", all(x['id'] != 'RQ-0009' for x in g))
    g2 = readiness(pkg, 'SDR')
    check("к SDR требования строже", len(g2) > len(g), f"SRR={len(g)}, SDR={len(g2)}")

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)
