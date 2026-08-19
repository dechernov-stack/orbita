#!/usr/bin/env python3
"""Исполняемый эталон полноты верификации (CR-002, ADR-018).

Метод верификации сам по себе не делает требование проверяемым: «Анализ» — это
иконка, а не порядок действий. Проверяется, что описан подход, названы средства
и определён критерий успеха.

  подход        что именно делается; не пересказ формулировки требования
  средства      чем проверяется — обязательно для испытания и анализа
  критерий      выводится из условия (mop) либо задан явно
  базирование   требование без полного описания верификации не базируется
"""
import re, sys

STOP = {'должна','должен','должно','система','не','более','менее','и','в','с','на','для',
        'что','как','по','из','к','а','или','при','до','от','это'}

def _words(t):
    return {w for w in re.findall(r'[а-яёa-z0-9]+', (t or '').lower()) if w not in STOP and len(w) > 2}

def is_restatement(approach, statement):
    """Подход, который лишь пересказывает требование, содержания не несёт."""
    a, s = _words(approach), _words(statement)
    if not a:
        return True
    overlap = len(a & s) / len(a)
    return overlap >= 0.7

MEANS_REQUIRED = {'test', 'analysis'}

def verification_issues(req):
    """Замечания к полноте верификации. Пустой список = требование проверяемо."""
    v = req.get('verification') or {}
    issues = []
    m = v.get('method')
    if not m:
        return ['метод верификации не назначен']
    ap = (v.get('approach') or '').strip()
    if not ap:
        issues.append('не описано, как именно выполняется проверка')
    elif len(ap) < 20:
        issues.append('описание проверки слишком краткое, чтобы быть выполнимым')
    elif is_restatement(ap, req.get('statement', '')):
        issues.append('описание проверки пересказывает требование, а не задаёт порядок действий')
    if m in MEANS_REQUIRED and not (v.get('means') or '').strip():
        issues.append(f'для метода «{m}» не указано средство проверки (стенд, методика, модель)')
    mop = req.get('mop') or {}
    if not mop.get('operator') and not (v.get('success_criterion') or '').strip():
        issues.append('критерий успеха не выводится из условия и не задан явно')
    return issues

def is_verifiable(req):
    return not verification_issues(req)

def success_criterion(req, render_fn=None):
    """Критерий успеха: из условия требования либо заданный явно."""
    v = req.get('verification') or {}
    if v.get('success_criterion'):
        return v['success_criterion']
    mop = req.get('mop') or {}
    if mop.get('operator') and render_fn:
        return f"{mop.get('name','показатель')}: {render_fn(mop)}"
    return None

# ================= проверки =================
ok = fail = 0
def check(name, cond, detail=''):
    global ok, fail
    if cond: ok += 1; print(f"  + {name}")
    else:    fail += 1; print(f"  - {name} {detail}")

MASS = {'id':'RQ-0100',
        'statement':'Сухая масса космического аппарата не должна превышать 100 кг.',
        'mop':{'name':'Сухая масса','operator':'le','value':{'value':100,'unit':'kg'}}}

print("Метод без подхода не делает требование проверяемым")
only_method = {**MASS, 'verification':{'method':'analysis','phase':'PhaseA'}}
check("метод без описания подхода отклонён",
      any('как именно' in i for i in verification_issues(only_method)))
check("отсутствие метода выявлено отдельно",
      verification_issues({**MASS,'verification':{}}) == ['метод верификации не назначен'])

print("\nПодход должен нести содержание")
short = {**MASS,'verification':{'method':'analysis','approach':'Расчёт массы','means':'MEL'}}
check("слишком краткое описание отклонено",
      any('слишком краткое' in i for i in verification_issues(short)))
parrot = {**MASS,'verification':{'method':'analysis','means':'MEL',
          'approach':'Проверить, что сухая масса космического аппарата не превышает 100 кг.'}}
check("пересказ требования вместо порядка действий отклонён",
      any('пересказывает' in i for i in verification_issues(parrot)))
real = {**MASS,'verification':{'method':'analysis','phase':'PhaseA',
        'means':'Сводный перечень оборудования (MEL) с резервами по зрелости',
        'approach':'Суммирование масс подсистем по MEL с применением резервов по зрелости '
                   'элементов и системного резерва; результат сверяется с независимой '
                   'оценкой по аналогам платформ того же класса.',
        'conditions':'Заправленная конфигурация, худший случай резервов'}}
check("содержательный подход принят", verification_issues(real) == [], verification_issues(real))
check("требование признано проверяемым", is_verifiable(real))

print("\nСредства проверки")
no_means = {**real, 'verification':{**real['verification'], 'means':''}}
check("анализ без средства отклонён",
      any('средство' in i for i in verification_issues(no_means)))
test_no_means = {**MASS,'verification':{'method':'test','means':'',
                 'approach':'Взвешивание собранного аппарата после интеграции с фиксацией '
                            'показаний поверенного оборудования в протоколе.'}}
check("испытание без средства отклонено",
      any('средство' in i for i in verification_issues(test_no_means)))
insp = {**MASS,'verification':{'method':'inspection',
        'approach':'Проверка наличия и подписей в ведомости массовых характеристик, '
                   'сверка версии документа с конфигурационным журналом.'}}
check("инспекция без средства допустима", verification_issues(insp) == [], verification_issues(insp))

print("\nКритерий успеха")
check("критерий выводится из условия требования",
      success_criterion(MASS, lambda m: f"не более {m['value']['value']} {m['value']['unit']}")
      == 'Сухая масса: не более 100 kg')
no_mop = {'id':'RQ-0200','statement':'Система должна вести журнал команд.',
          'verification':{'method':'demonstration',
                          'approach':'Демонстрация записи команд в журнал на сценарии '
                                     'штатного сеанса с последующим просмотром содержимого.'}}
check("требование без условия и без явного критерия отклонено",
      any('критерий успеха' in i for i in verification_issues(no_mop)))
with_crit = {**no_mop, 'verification':{**no_mop['verification'],
             'success_criterion':'Журнал содержит все переданные команды с отметками времени'}}
check("явный критерий закрывает замечание", verification_issues(with_crit) == [],
      verification_issues(with_crit))
check("явный критерий возвращается как есть",
      success_criterion(with_crit) == 'Журнал содержит все переданные команды с отметками времени')

print("\nВлияние на базирование")
def can_baseline(req):
    return not verification_issues(req)
check("проверяемое требование базируется", can_baseline(real))
check("требование с иконкой вместо подхода не базируется", not can_baseline(only_method))
check("несколько замечаний перечисляются вместе",
      len(verification_issues({**MASS,'verification':{'method':'test'}})) >= 2,
      verification_issues({**MASS,'verification':{'method':'test'}}))

print(f"\nИтог: пройдено {ok}, провалено {fail}")
sys.exit(1 if fail else 0)
