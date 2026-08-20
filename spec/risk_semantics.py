#!/usr/bin/env python3
"""Исполняемый эталон реестра рисков (шаг 7).

Основание: NPR 8000.4; Прил. 6 регламента БП-PA, Прил. 7 БП-PPA.
Реестр рисков — входной материал MCR и KDP; без него пакет передачи неполон.

  формулировка   условие — событие — последствие, три части обязательны
  оценка         вероятность и последствия по шкале 1–5
  критичность    матрица, а не произведение; последствия весомее вероятности
  эскалация      порог выводит риск на уровень программы
  реагирование   стратегия и мероприятия обязательны выше порога
  остаточный     риск после мероприятий не выше исходного
  жизненный цикл закрытый риск сохраняется, не удаляется
"""
import sys

SCALE = range(1, 6)
CATEGORIES = {'technical', 'cost', 'schedule', 'safety'}
STRATEGIES = {'mitigate', 'accept', 'transfer', 'avoid'}

# Матрица критичности 5×5: строки — последствия (1..5), столбцы — вероятность (1..5).
# Последствия весомее вероятности: редкое, но тяжёлое событие не считается малым.
MATRIX = {
    5: ['high',   'high',   'high',   'high',   'high'  ],
    4: ['medium', 'high',   'high',   'high',   'high'  ],
    3: ['low',    'medium', 'medium', 'high',   'high'  ],
    2: ['low',    'low',    'medium', 'medium', 'high'  ],
    1: ['low',    'low',    'low',    'low',    'medium'],
}
ORDER = {'low': 0, 'medium': 1, 'high': 2}
ESCALATION_THRESHOLD = 'high'

def criticality(prob, impact):
    if prob not in SCALE or impact not in SCALE:
        raise ValueError('вероятность и последствия задаются по шкале 1–5')
    return MATRIX[impact][prob - 1]

def needs_escalation(risk):
    return ORDER[criticality(risk['probability'], risk['impact'])] >= ORDER[ESCALATION_THRESHOLD]

def statement_issues(text):
    """Формулировка «условие — событие — последствие»: три части."""
    parts = [p.strip() for p in text.split('—') if p.strip()]
    if len(parts) < 3:
        return ['формулировка должна содержать условие, событие и последствие']
    return []

def risk_issues(risk):
    p = list(statement_issues(risk.get('statement', '')))
    if risk.get('category') not in CATEGORIES:
        p.append('категория риска не задана или недопустима')
    for f in ('probability', 'impact'):
        if risk.get(f) not in SCALE:
            p.append(f'{f}: значение вне шкалы 1–5')
    if not risk.get('owner'):
        p.append('у риска нет владельца')
    if risk.get('probability') in SCALE and risk.get('impact') in SCALE:
        if needs_escalation(risk):
            if risk.get('strategy') not in STRATEGIES:
                p.append('для риска высокой критичности не задана стратегия реагирования')
            elif risk['strategy'] != 'accept' and not risk.get('actions'):
                p.append('стратегия требует перечня мероприятий')
            if not risk.get('due'):
                p.append('для риска высокой критичности не задан срок')
    return p

def residual_ok(risk):
    """Остаточный риск после мероприятий не может быть выше исходного.

    Сравнения по классу критичности недостаточно: классы грубые, и ухудшение
    внутри класса «высокий» осталось бы незамеченным. Мероприятия снижают
    вероятность и (или) последствия, но не повышают ни то, ни другое."""
    r = risk.get('residual')
    if not r:
        return True
    if r['probability'] > risk['probability'] or r['impact'] > risk['impact']:
        return False
    return (ORDER[criticality(r['probability'], r['impact'])]
            <= ORDER[criticality(risk['probability'], risk['impact'])])

def register_summary(risks):
    """Сводка для обзора: распределение по критичности, к эскалации, просроченные."""
    active = [r for r in risks if r.get('status') != 'closed']
    dist = {'low': 0, 'medium': 0, 'high': 0}
    for r in active:
        dist[criticality(r['probability'], r['impact'])] += 1
    return {'total': len(risks), 'active': len(active),
            'distribution': dist,
            'escalate': sorted(r['id'] for r in active if needs_escalation(r)),
            'closed_retained': sorted(r['id'] for r in risks if r.get('status') == 'closed')}

def traced(risk):
    """Риск связан с тем, что им затронуто: требование, элемент или технология."""
    return bool(risk.get('affects'))

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("Матрица критичности")
    check("низкая вероятность и низкие последствия — низкая критичность",
          criticality(1, 1) == 'low')
    check("высокая вероятность и тяжёлые последствия — высокая",
          criticality(5, 5) == 'high')
    check("монотонность по вероятности",
          all(ORDER[criticality(p, 3)] <= ORDER[criticality(p + 1, 3)] for p in range(1, 5)))
    check("монотонность по последствиям",
          all(ORDER[criticality(3, i)] <= ORDER[criticality(3, i + 1)] for i in range(1, 5)))
    check("последствия весомее вероятности: редкое тяжёлое не считается малым",
          ORDER[criticality(2, 5)] >= ORDER[criticality(5, 2)],
          f"{criticality(2,5)} vs {criticality(5,2)}")
    check("матрица несимметрична: тяжёлые последствия весят больше",
          criticality(1, 5) == 'high' and criticality(5, 1) == 'medium')
    check("критичность не сводится к произведению оценок",
          criticality(1, 5) != criticality(5, 1))
    try:
        criticality(0, 3); check("значение вне шкалы отклонено", False)
    except ValueError:
        check("значение вне шкалы отклонено", True)

    print("\nФормулировка риска")
    good = ('При задержке поставки приёмника — срыв срока интеграции — '
            'сдвиг готовности к SRR на два месяца')
    check("полная формулировка принята", statement_issues(good) == [])
    check("формулировка без последствия отклонена",
          statement_issues('При задержке поставки — срыв срока интеграции') != [])
    check("одно предложение без структуры отклонено",
          statement_issues('Риск срыва сроков') != [])

    print("\nПолнота записи")
    RISK = {'id': 'RSK-0001', 'statement': good, 'category': 'schedule',
            'probability': 4, 'impact': 4, 'owner': 'руководитель проекта',
            'strategy': 'mitigate', 'actions': ['резервный поставщик'],
            'due': '2026-12-01', 'affects': ['CM-0011'], 'status': 'open'}
    check("полная запись без замечаний", risk_issues(RISK) == [], risk_issues(RISK))
    check("риск без владельца отклонён",
          any('владельца' in i for i in risk_issues({**RISK, 'owner': ''})))
    check("недопустимая категория отклонена",
          any('категория' in i for i in risk_issues({**RISK, 'category': 'прочее'})))
    check("оценка вне шкалы отклонена",
          any('шкал' in i for i in risk_issues({**RISK, 'probability': 7})))
    check("высокая критичность без стратегии отклонена",
          any('стратегия' in i for i in risk_issues({**RISK, 'strategy': None})))
    check("стратегия без мероприятий отклонена",
          any('мероприятий' in i for i in risk_issues({**RISK, 'actions': []})))
    check("принятие риска мероприятий не требует",
          risk_issues({**RISK, 'strategy': 'accept', 'actions': []}) == [])
    check("высокая критичность без срока отклонена",
          any('срок' in i for i in risk_issues({**RISK, 'due': None})))
    low = {**RISK, 'probability': 1, 'impact': 1, 'strategy': None, 'actions': [], 'due': None}
    check("низкая критичность не требует стратегии и срока", risk_issues(low) == [], risk_issues(low))

    print("\nЭскалация")
    check("риск высокой критичности выводится на уровень программы", needs_escalation(RISK))
    check("риск низкой критичности не эскалируется", not needs_escalation(low))
    check("порог применяется к критичности, а не к оценкам напрямую",
          needs_escalation({'probability': 2, 'impact': 5}) and not needs_escalation({'probability': 5, 'impact': 1}))

    print("\nОстаточный риск")
    check("снижение после мероприятий принято",
          residual_ok({**RISK, 'residual': {'probability': 2, 'impact': 3}}))
    check("остаточный риск выше исходного отклонён",
          not residual_ok({**RISK, 'residual': {'probability': 5, 'impact': 5}}))
    check("ухудшение внутри одного класса критичности выявляется",
          not residual_ok({'probability': 2, 'impact': 5,
                           'residual': {'probability': 5, 'impact': 5}}))
    check("равный остаточный риск допустим",
          residual_ok({**RISK, 'residual': {'probability': 4, 'impact': 4}}))
    check("отсутствие оценки остатка не является ошибкой", residual_ok(RISK))

    print("\nРеестр и связи")
    REGISTER = [RISK,
                {**RISK, 'id': 'RSK-0002', 'probability': 1, 'impact': 2},
                {**RISK, 'id': 'RSK-0003', 'status': 'closed'},
                {**RISK, 'id': 'RSK-0004', 'probability': 3, 'impact': 3}]
    s = register_summary(REGISTER)
    check("закрытый риск сохраняется в реестре", s['closed_retained'] == ['RSK-0003'])
    check("закрытый риск не входит в активные", s['active'] == 3)
    check("распределение по критичности посчитано",
          s['distribution'] == {'low': 1, 'medium': 1, 'high': 1}, s['distribution'])
    check("к эскалации отобраны только высокие", s['escalate'] == ['RSK-0001'])
    check("риск связан с затронутым объектом", traced(RISK))
    check("несвязанный риск выявляется", not traced({**RISK, 'affects': []}))

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
