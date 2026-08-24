#!/usr/bin/env python3
"""Исполняемый эталон связи деревьев и полноты V&V (CR-003, ADR-019).

Основано на NASA SP-2016-6105 Rev 2:
  - верификация — соответствие требованиям; валидация — соответствие ожиданиям
    стейкхолдеров и ConOps: это разные матрицы и разные объекты привязки;
  - валидация выполняется на каждой фазе на продуктах фазы (моделях), а не только
    на конечном изделии при поставке;
  - методы верификации и валидации меняются от фазы к фазе по мере развития проекта;
  - логическая декомпозиция порождает ПРОИЗВОДНЫЕ требования, распределение —
    РАСПРЕДЕЛЁННЫЕ: свёртка бюджета применима только ко вторым;
  - квалификация проводится один раз на конструкцию, приёмка — на каждый экземпляр.
"""
import sys

# ---------- дерево требований ↔ дерево компонентов ----------
def descendants(components, root):
    out, stack = set(), [root]
    while stack:
        c = stack.pop()
        for k, v in components.items():
            if v.get('parent') == c and k not in out:
                out.add(k); stack.append(k)
    return out

def allocation_consistent(parent_req, child_req, components):
    """Потомок требования распределяется на тот же элемент либо на его потомка.
    Иначе декомпозиция уходит за пределы области родителя."""
    p = [a['component'] for a in parent_req.get('allocated_to', []) if 'component' in a]
    c = [a['component'] for a in child_req.get('allocated_to', []) if 'component' in a]
    if not p or not c:
        return True, None
    allowed = set()
    for pc in p:
        allowed |= {pc} | descendants(components, pc)
    stray = [x for x in c if x not in allowed]
    return (not stray), (f'потомок распределён вне области родителя: {stray}' if stray else None)

def interface_allocation_valid(req, components):
    """Интерфейсное требование распределяется на интерфейс, а не на один элемент."""
    if req.get('category') != 'interface':
        return True, None
    targets = req.get('allocated_to', [])
    ifaces = [t for t in targets if 'interface' in t]
    if not ifaces:
        if not targets:
            return False, 'интерфейсное требование не распределено: укажите интерфейс в allocated_to'
        return False, 'интерфейсное требование распределено на элемент, а не на интерфейс'
    for t in ifaces:
        owners = components.get(t['interface'], {}).get('owners', [])
        if len(owners) != 2:
            return False, f'интерфейс {t["interface"]} не имеет двух сторон'
    return True, None

def component_specification(reqs, component_id):
    """Спецификация элемента — множество требований, распределённых на него."""
    return sorted(r['id'] for r in reqs
                  if any(a.get('component') == component_id for a in r.get('allocated_to', [])))

# ---------- распределённые и производные требования ----------
def rollup_children(parent_id, links, reqs):
    """Свёртка бюджета — только по РАСПРЕДЕЛЁННЫМ потомкам (kind=allocated).
    Производные требования порождаются проектным решением и в бюджет не входят."""
    ids = [l['to'] for l in links
           if l['from'] == parent_id and l['kind'] == 'derive'
           and l.get('derivation_kind') == 'allocated']
    return [r for r in reqs if r['id'] in ids]

# ---------- верификация: события, а не одно поле ----------
CLOSING_KINDS = {'qualification', 'acceptance', 'certification'}

def verification_state(req):
    """Состояние верификации требования по совокупности её событий.

    Предварительное событие (расчёт по модели на ранней фазе) даёт промежуточную
    уверенность, но НЕ закрывает верификацию: закрывает только событие,
    помеченное как закрывающее и завершённое успешно."""
    events = req.get('verification_events', [])
    if not events:
        return 'не запланирована'
    closing = [e for e in events if e.get('closes')]
    if not closing:
        return 'план неполон: нет закрывающего события'
    if any(e['status'] == 'failed' for e in closing):
        return 'не выполнено'
    if all(e['status'] == 'passed' for e in closing):
        return 'верифицировано'
    if any(e['status'] == 'passed' and not e.get('closes') for e in events):
        return 'предварительно подтверждено'
    return 'запланирована'

def event_issues(ev, req):
    """Полнота описания отдельного события верификации."""
    p = []
    if not (ev.get('approach') or '').strip():
        p.append('не описано, как выполняется проверка')
    if ev.get('method') in ('test', 'analysis') and not (ev.get('means') or '').strip():
        p.append('не указано средство проверки')
    if ev.get('closes') and ev.get('kind') not in CLOSING_KINDS:
        p.append('закрывающим может быть только квалификационное, приёмочное или сертификационное событие')
    if ev.get('kind') == 'preliminary' and ev.get('closes'):
        p.append('предварительное событие не закрывает верификацию')
    if not ev.get('level'):
        p.append('не указан уровень проверки в структуре изделия')
    return p

def qualification_scope(events):
    """Квалификация проводится один раз на конструкцию, приёмка — на экземпляр."""
    q = [e for e in events if e.get('kind') == 'qualification']
    a = [e for e in events if e.get('kind') == 'acceptance']
    issues = []
    if len(q) > 1 and len({e.get('design_version') for e in q}) == 1:
        issues.append('повторная квалификация одной и той же конструкции')
    if a and not all(e.get('unit') for e in a):
        issues.append('приёмочное событие без указания экземпляра')
    return issues

# ---------- свидетельства ----------
def evidence_state(ev_doc, current_config):
    """Свидетельство действительно для той конфигурации, на которой получено."""
    if ev_doc.get('superseded_by'):
        return 'заменено'
    if ev_doc.get('configuration') != current_config:
        return 'неприменимо к текущей конфигурации'
    if ev_doc.get('stale'):
        return 'устарело'
    return 'действительно'

def evidence_chain(docs):
    """Цепочка свидетельств: предварительный расчёт по модели → физическое испытание."""
    return [d['id'] for d in sorted(docs, key=lambda d: d['date'])]

# ---------- валидация: отдельно от верификации ----------
def validation_issues(activity):
    """Валидация привязывается к ожиданию стейкхолдера (нужде/сервису), не к требованию."""
    p = []
    tgt = activity.get('target', '')
    if tgt.startswith('RQ-'):
        p.append('валидация привязана к требованию, а не к ожиданию стейкхолдера')
    if not tgt.startswith(('ND-', 'SV-')):
        p.append('цель валидации не указана')
    if not activity.get('conops_ref'):
        p.append('нет ссылки на сценарий ConOps')
    if not activity.get('product_kind'):
        p.append('не указано, на чём выполняется валидация (модель, макет, изделие)')
    return p

# ================= проверки =================
def _run_checks():

    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    COMPONENTS = {
        'CM-0001': {'kind':'system'},
        'CM-0010': {'kind':'segment','parent':'CM-0001'},
        'CM-0011': {'kind':'subsystem','parent':'CM-0010'},
        'CM-0020': {'kind':'segment','parent':'CM-0001'},
        'IF-0001': {'kind':'interface','owners':['CM-0010','CM-0020']},
        'IF-0002': {'kind':'interface','owners':['CM-0010']},
    }

    print("Дерево требований ↔ дерево компонентов")
    pr = {'id':'RQ-0100','allocated_to':[{'component':'CM-0001'}]}
    ch_ok  = {'id':'RQ-0101','allocated_to':[{'component':'CM-0011'}]}
    ch_bad = {'id':'RQ-0102','allocated_to':[{'component':'CM-9999'}]}
    r1, _ = allocation_consistent(pr, ch_ok, COMPONENTS)
    r2, why = allocation_consistent(pr, ch_bad, COMPONENTS)
    check("потомок на подчинённом элементе — согласовано", r1)
    check("потомок вне области родителя выявлен", not r2, why)
    mid = {'id':'RQ-0110','allocated_to':[{'component':'CM-0010'}]}
    sib = {'id':'RQ-0111','allocated_to':[{'component':'CM-0020'}]}
    r3, why3 = allocation_consistent(mid, sib, COMPONENTS)
    check("потомок на соседней ветви выявлен", not r3, why3)
    check("нераспределённые требования не блокируют проверку",
          allocation_consistent({'id':'x'}, ch_ok, COMPONENTS)[0])

    print("\nИнтерфейсные требования")
    iface_ok  = {'id':'RQ-0200','category':'interface','allocated_to':[{'interface':'IF-0001'}]}
    iface_bad = {'id':'RQ-0201','category':'interface','allocated_to':[{'component':'CM-0010'}]}
    iface_one = {'id':'RQ-0202','category':'interface','allocated_to':[{'interface':'IF-0002'}]}
    check("интерфейсное требование на интерфейсе принято", interface_allocation_valid(iface_ok, COMPONENTS)[0])
    check("интерфейсное требование на элементе отклонено", not interface_allocation_valid(iface_bad, COMPONENTS)[0])
    check("интерфейс без второй стороны отклонён", not interface_allocation_valid(iface_one, COMPONENTS)[0])

    print("\nСпецификация элемента")
    REQS = [pr, ch_ok, {'id':'RQ-0105','allocated_to':[{'component':'CM-0011'}]}, sib]
    check("спецификация элемента собирается из распределённых требований",
          component_specification(REQS,'CM-0011') == ['RQ-0101','RQ-0105'],
          component_specification(REQS,'CM-0011'))
    check("элемент без требований даёт пустую спецификацию",
          component_specification(REQS,'CM-0099') == [])

    print("\nРаспределённые и производные требования")
    LINKS = [{'from':'RQ-0100','to':'RQ-0101','kind':'derive','derivation_kind':'allocated'},
             {'from':'RQ-0100','to':'RQ-0105','kind':'derive','derivation_kind':'allocated'},
             {'from':'RQ-0100','to':'RQ-0107','kind':'derive','derivation_kind':'derived'}]
    ALL = REQS + [{'id':'RQ-0107'}]
    kids = rollup_children('RQ-0100', LINKS, ALL)
    check("в свёртку входят только распределённые потомки",
          [r['id'] for r in kids] == ['RQ-0101','RQ-0105'], [r['id'] for r in kids])
    check("производное требование в бюджет не входит",
          all(r['id'] != 'RQ-0107' for r in kids))

    print("\nВерификация: несколько событий на требование")
    prelim = {'id':'VE-1','method':'analysis','phase':'PhaseA','level':'system','kind':'preliminary',
              'approach':'Расчёт массы по MEL с резервами по зрелости элементов',
              'means':'Модель MEL','status':'passed','closes':False}
    final  = {'id':'VE-2','method':'test','phase':'PhaseD','level':'system','kind':'qualification',
              'approach':'Взвешивание собранного аппарата после интеграции с фиксацией в протоколе',
              'means':'Весовой стенд, поверенное оборудование','status':'planned','closes':True,
              'design_version':'v1'}
    req = {'id':'RQ-0100','verification_events':[prelim, final]}
    check("предварительный расчёт не закрывает верификацию",
          verification_state(req) == 'предварительно подтверждено', verification_state(req))
    done = {'id':'RQ-0100','verification_events':[prelim, {**final,'status':'passed'}]}
    check("успешное закрывающее событие верифицирует требование",
          verification_state(done) == 'верифицировано')
    failed = {'id':'RQ-0100','verification_events':[prelim, {**final,'status':'failed'}]}
    check("провал закрывающего события — не выполнено", verification_state(failed) == 'не выполнено')
    check("отсутствие событий — верификация не запланирована",
          verification_state({'id':'x'}) == 'не запланирована')
    check("план без закрывающего события выявлен",
          verification_state({'id':'x','verification_events':[prelim]}) == 'план неполон: нет закрывающего события')

    print("\nПолнота отдельного события")
    check("полное событие без замечаний", event_issues(final, req) == [], event_issues(final, req))
    check("событие без описания подхода отклонено",
          any('как выполняется' in i for i in event_issues({**final,'approach':''}, req)))
    check("испытание без средства отклонено",
          any('средство' in i for i in event_issues({**final,'means':''}, req)))
    check("предварительное событие не может быть закрывающим",
          any('не закрывает' in i for i in event_issues({**prelim,'closes':True}, req)))
    check("событие без уровня проверки отклонено",
          any('уровень' in i for i in event_issues({**final,'level':None}, req)))

    print("\nКвалификация и приёмка")
    check("повторная квалификация одной конструкции выявлена",
          qualification_scope([final, {**final,'id':'VE-3'}]) != [])
    check("квалификация другой версии конструкции допустима",
          qualification_scope([final, {**final,'id':'VE-3','design_version':'v2'}]) == [])
    check("приёмка без указания экземпляра выявлена",
          qualification_scope([{'kind':'acceptance'}]) != [])
    check("приёмка с экземпляром принята",
          qualification_scope([{'kind':'acceptance','unit':'FM-01'}]) == [])

    print("\nСвидетельства: предварительный расчёт → физическое испытание")
    docs = [{'id':'EV-1','kind':'analysis_report','maturity':'preliminary','date':'2026-03-01',
             'configuration':'C1','superseded_by':'EV-2'},
            {'id':'EV-2','kind':'test_report','maturity':'final','date':'2027-06-01',
             'configuration':'C1'}]
    check("цепочка свидетельств упорядочена по времени", evidence_chain(docs) == ['EV-1','EV-2'])
    check("заменённое свидетельство помечено", evidence_state(docs[0],'C1') == 'заменено')
    check("итоговое свидетельство действительно", evidence_state(docs[1],'C1') == 'действительно')
    check("свидетельство для другой конфигурации неприменимо",
          evidence_state(docs[1],'C2') == 'неприменимо к текущей конфигурации')

    print("\nВалидация отдельно от верификации")
    val_ok = {'target':'ND-0007','conops_ref':'CO-0003','product_kind':'model','phase':'PhaseA'}
    check("валидация ожидания на модели фазы принята", validation_issues(val_ok) == [], validation_issues(val_ok))
    check("валидация, привязанная к требованию, отклонена",
          any('а не к ожиданию' in i for i in validation_issues({**val_ok,'target':'RQ-0100'})))
    check("валидация без ссылки на ConOps отклонена",
          any('ConOps' in i for i in validation_issues({**val_ok,'conops_ref':None})))
    check("валидация без указания продукта отклонена",
          any('на чём выполняется' in i for i in validation_issues({**val_ok,'product_kind':None})))
    check("валидация допустима на сервисе", validation_issues({**val_ok,'target':'SV-0002'}) == [])

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
