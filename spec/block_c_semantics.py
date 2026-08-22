#!/usr/bin/env python3
"""Исполняемый эталон блока C (Шаг 17): ConOps, TRL, решения, выпуски документов.

Правила видов, на которые модель ссылалась в пустоту: conops_ref валидации,
технологии в зрелости, запись решения, статусная модель документов.
"""
import sys


# ---------- C1: валидация против сценария ConOps ----------
def validation_conops_ok(validation, objects):
    """Ссылка резолвится: валидация против несуществующего сценария — не
    проверка, а обещание проверки."""
    ref = validation.get('conops_ref', '')
    if not ref:
        return True
    target = objects.get(ref)
    return target is not None and target.get('type') == 'conops'


# ---------- C2: технология ниже требуемого TRL к точке ----------
def trl_gaps(technologies, gate):
    """Блокирующие разрывы зрелости: только технологии, заявившие ЭТУ точку —
    чужая точка — чужой срок."""
    return sorted(
        t['id'] for t in technologies
        if t.get('gate') == gate and t.get('trl_current', 0) < t.get('trl_required', 0)
    )


# ---------- C3: запись решения ----------
def decision_issues(decision):
    """Решение decided без выбора или обоснования — «решили, но не скажем что»."""
    if decision.get('status') != 'decided':
        return []
    issues = []
    selected = decision.get('selected', '')
    names = [a['name'] for a in decision.get('alternatives', [])]
    if not selected:
        issues.append('нет выбранной альтернативы')
    elif selected not in names:
        issues.append('выбранная альтернатива не среди перечисленных')
    if not decision.get('rationale', ''):
        issues.append('нет обоснования')
    return issues


# ---------- C5: выпуск документа ----------
def issue_stale(issue, current_digest):
    """«Документ устарел» — факт сравнения слепков, а не ощущение."""
    return issue['digest'] != current_digest


def issue_creation_ok(issue):
    """Создание — это выпуск: сразу approved не бывает."""
    return issue.get('status') == 'issued'


def approve_ok(issue):
    """Одобрение безымянным не бывает."""
    return issue.get('status') != 'approved' or bool(issue.get('approved_by'))


# ================= проверки =================
def _run_checks():
    ok = fail = 0

    def check(name, cond, detail=''):
        nonlocal ok, fail
        ok, fail = (ok + 1, fail) if cond else (ok, fail + 1)
        print(f"  {'+' if cond else '-'} {name}" + ('' if cond else f' {detail}'))

    print("C1: валидация против ConOps")
    objs = {'CO-0001': {'type': 'conops'}, 'ND-0001': {'type': 'need'}}
    check("ссылка на существующий сценарий резолвится",
          validation_conops_ok({'conops_ref': 'CO-0001'}, objs))
    check("ссылка в никуда отклоняется",
          not validation_conops_ok({'conops_ref': 'CO-0099'}, objs))
    check("ссылка на объект другого вида отклоняется",
          not validation_conops_ok({'conops_ref': 'ND-0001'}, objs))

    print("\nC2: TRL к точке")
    tech = [
        {'id': 'TL-0001', 'gate': 'SRR', 'trl_current': 3, 'trl_required': 5},
        {'id': 'TL-0002', 'gate': 'SRR', 'trl_current': 6, 'trl_required': 5},
        {'id': 'TL-0003', 'gate': 'SDR', 'trl_current': 2, 'trl_required': 6},
    ]
    check("ниже требуемого к своей точке — разрыв", trl_gaps(tech, 'SRR') == ['TL-0001'])
    check("чужая точка — чужой срок", 'TL-0003' not in trl_gaps(tech, 'SRR'))
    check("достигнутый уровень разрыва не даёт", 'TL-0002' not in trl_gaps(tech, 'SRR'))

    print("\nC3: запись решения")
    alts = [{'name': 'Walker 8/2'}, {'name': 'ССО 6/3'}]
    check("открытое решение не требует выбора",
          decision_issues({'status': 'open', 'alternatives': alts}) == [])
    check("decided без выбора отклоняется",
          'нет выбранной альтернативы' in decision_issues(
              {'status': 'decided', 'alternatives': alts, 'rationale': 'дешевле'}))
    check("выбор не из перечисленных отклоняется",
          'выбранная альтернатива не среди перечисленных' in decision_issues(
              {'status': 'decided', 'alternatives': alts, 'selected': 'GEO', 'rationale': 'x'}))
    check("decided без обоснования отклоняется",
          'нет обоснования' in decision_issues(
              {'status': 'decided', 'alternatives': alts, 'selected': 'Walker 8/2'}))
    check("полная запись принимается",
          decision_issues({'status': 'decided', 'alternatives': alts,
                           'selected': 'Walker 8/2', 'rationale': 'покрытие выше'}) == [])

    print("\nC5: выпуск документа")
    check("создание — это выпуск", issue_creation_ok({'status': 'issued'}))
    check("сразу approved не бывает", not issue_creation_ok({'status': 'approved'}))
    check("расхождение слепков — документ устарел",
          issue_stale({'digest': 'aaaa'}, 'bbbb'))
    check("совпадение слепков — документ актуален",
          not issue_stale({'digest': 'aaaa'}, 'aaaa'))
    check("одобрение безымянным не бывает",
          not approve_ok({'status': 'approved', 'approved_by': ''}))
    check("одобрение с именем принимается",
          approve_ok({'status': 'approved', 'approved_by': 'вед. системный инженер'}))

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
