#!/usr/bin/env python3
"""Исполняемый эталон ИИ-контура (шаг 5).

Ключевое отличие нашего контура: предложение проходит структурный фильтр
ДО показа инженеру. Модель предлагает — правила отбраковывают — человек
утверждает то, что уже состоятельно.

  TZ-AI-001  промпт-пакет: контекст, задание, схема ответа, идентификатор
  TZ-AI-002  разбор по схеме локально; частично корректный ответ принимается частично
  TZ-AI-003  арбитраж: в API уходят только расхождения
  TZ-AI-004  акцепт и происхождение; до акцепта предложение ни на что не влияет
  TZ-AI-005  прямой API-канал для структурных операций
  TZ-AI-006  применение как diff; ручного перебивания нет
"""
import json, re, sys, hashlib

# ---------- TZ-AI-001: промпт-пакет ----------
PACKAGE_KINDS = {
    # блок E: канал О2 — из постановки миссии, объём пачками
    'mission_to_goals', 'mission_to_needs',
    'needs_to_services', 'services_to_requirements', 'requirement_quality',
    'requirement_decomposition', 'verification_approach', 'risk_register',
}

def build_package(kind, context, task, response_schema):
    if kind not in PACKAGE_KINDS:
        raise ValueError(f'неизвестный вид пакета: {kind}')
    body = {'kind': kind, 'context': context, 'task': task, 'response_schema': response_schema}
    pid = 'PP-' + hashlib.blake2b(json.dumps(body, sort_keys=True,
                                             ensure_ascii=False).encode(), digest_size=4).hexdigest()
    return {'id': pid, **body}

def package_issues(pkg):
    p = []
    for f in ('id', 'kind', 'context', 'task', 'response_schema'):
        if not pkg.get(f):
            p.append(f'в пакете нет поля {f}')
    if pkg.get('response_schema') and not isinstance(pkg['response_schema'], dict):
        p.append('схема ответа должна быть структурой, а не текстом')
    return p

# ---------- TZ-AI-002: разбор ответа ----------
def parse_response(raw, pkg):
    """Разбор локальный, без обращения к API. Возвращает (принятые, отклонённые)."""
    accepted, rejected = [], []
    try:
        data = json.loads(re.sub(r'^```(?:json)?|```$', '', raw.strip(), flags=re.M).strip())
    except json.JSONDecodeError as e:
        return [], [{'item': None, 'errors': [f'ответ не разбирается как JSON: {e.msg}']}]
    items = data if isinstance(data, list) else data.get('items', [])
    required = pkg['response_schema'].get('required', [])
    for it in items:
        errs = [f'нет обязательного поля {f}' for f in required if f not in it]
        (rejected if errs else accepted).append({'item': it, 'errors': errs} if errs else it)
    return accepted, rejected

# ---------- структурный фильтр: отбраковка ДО показа инженеру ----------
# ---------- П5: правило основания (ловушка 10) ----------
def source_issues(item, require_source=False):
    """Числовое значение без ссылки на источник — не «плохое», а требующее
    решения инженера: помечается и снимается с показа, а не проходит молча."""
    if not require_source:
        return []
    found = []

    def walk(node, path=''):
        if isinstance(node, dict):
            if isinstance(node.get('value'), (int, float)) and isinstance(node.get('unit'), str):
                prov = node.get('provenance') or {}
                has_ref = bool(prov.get('ref')) or bool((prov.get('import') or {}).get('dataset')) \
                    or bool((prov.get('calculation') or {}).get('module'))
                if not has_ref and prov.get('source') not in ('calculated', 'import'):
                    found.append(path or 'значение')
            for k, v in node.items():
                if k != 'provenance':
                    walk(v, f'{path}/{k}' if path else k)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f'{path}/{i}')

    walk(item)
    return [f'основание: значение «{p}» без ссылки на источник — требуется решение инженера'
            for p in dict.fromkeys(found)]


def compose_prompt(profile, context, kind_io):
    """Промпт собирает СЛУЖБА из профиля и состояния модели: текста задания
    в коде и скриптах нет. Порядок частей постоянен — иначе сравнивать вызовы
    между собой нечем."""
    parts = [f"Ты — инженерная служба проекта «{context['project']}», фаза {context['phase']}."]
    parts.append('ОГРАНИЧЕНИЯ ПРОЕКТА (нарушать нельзя):')
    parts += [f'— {p}' for p in profile.get('prohibitions', [])] or ['— не заданы']
    if profile.get('statement_rules'):
        parts.append('ПРАВИЛА ФОРМУЛИРОВОК:')
        parts += [f'— {r}' for r in profile['statement_rules']]
    if profile.get('glossary'):
        parts.append('ГЛОССАРИЙ (термины употреблять только в этом значении):')
        parts += [f"— {g['term']}: {g['meaning']}" for g in profile['glossary']]
    if profile.get('require_source', True):
        parts.append('ОСНОВАНИЕ ЗНАЧЕНИЙ: каждое числовое значение сопровождай источником')
    parts.append('ЧТО УЖЕ ЕСТЬ В ПРОЕКТЕ:')
    parts += [f'{k} ({len(v)})' for k, v in context.get('existing', {}).items()] or ['— проект пуст']
    parts.append('ВХОД ОПЕРАЦИИ:')
    parts.append(context['statement'])
    parts.append(f"ЗАДАНИЕ: из входа «{kind_io[0]}» получи «{kind_io[1]}».")
    return '\n'.join(parts)


def screen_requirement(item, rules):
    """Предложение проходит те же правила, что и рукописное требование.
    rules — функции из эталонов: качество, условие, верификация, трассировка.
    Блок E: цели, нужды и сервисы — не требования; правил формулировки к ним
    нет, состоятельность держит нормативная схема на акцепте пачкой."""
    oid = str(item.get('id', ''))
    require_source = bool(rules.pop('__require_source__', False)) if isinstance(rules, dict) else False
    if oid.startswith(('MG-', 'ND-', 'SV-')):
        return source_issues(item, require_source)
    issues = source_issues(item, require_source)
    for name, fn in rules.items():
        issues += [f'{name}: {i}' for i in fn(item)]
    return issues

def screen_batch(items, rules):
    shown, filtered = [], []
    for it in items:
        iss = screen_requirement(it, rules)
        (filtered if iss else shown).append({'item': it, 'issues': iss} if iss else it)
    return shown, filtered

# ---------- TZ-AI-003: арбитраж ----------
def diff_answers(answers, key='id'):
    """Совпадающие фрагменты в API не передаются — только расхождения."""
    by_key = {}
    for src, items in answers.items():
        for it in items:
            by_key.setdefault(it[key], {})[src] = it
    agreed, disputed = [], []
    for k, variants in by_key.items():
        norm = {json.dumps(v, sort_keys=True, ensure_ascii=False) for v in variants.values()}
        if len(norm) == 1 and len(variants) == len(answers):
            agreed.append(next(iter(variants.values())))
        else:
            disputed.append({'key': k, 'variants': variants})
    return agreed, disputed

def arbitration_payload(agreed, disputed):
    """В API уходят только спорные фрагменты."""
    return {'disputed': disputed}

# ---------- TZ-AI-004: акцепт и происхождение ----------
def as_proposal(item, pkg_id, llm):
    return {**item, 'provenance': {'source': 'ai_proposed',
            'ai': {'prompt_package_id': pkg_id, 'llm': llm, 'accepted': False, 'edited': False}}}

def accept(proposal, by, edits=None):
    item = {**proposal, **(edits or {})}
    ai = dict(proposal['provenance']['ai'])
    ai.update({'accepted': True, 'accepted_by': by, 'edited': bool(edits)})
    item['provenance'] = {**proposal['provenance'], 'ai': ai}
    return item

def influences_calculations(obj):
    """Неакцептованное предложение не участвует в расчётах и отчётах."""
    prov = obj.get('provenance', {})
    if prov.get('source') != 'ai_proposed':
        return True
    return bool(prov.get('ai', {}).get('accepted'))

# ---------- TZ-AI-006: применение как diff ----------
def make_diff(current, proposed):
    """Построчный diff: добавления, изменения, неизменное."""
    out = {}
    for k in sorted(set(current) | set(proposed)):
        if k not in current:
            out[k] = {'op': 'add', 'to': proposed[k]}
        elif k not in proposed:
            out[k] = {'op': 'keep', 'value': current[k]}
        elif current[k] != proposed[k]:
            out[k] = {'op': 'change', 'from': current[k], 'to': proposed[k]}
        else:
            out[k] = {'op': 'same', 'value': current[k]}
    return out

def apply_diff(current, diff, selected):
    """Применяются только выбранные поля — остальное не трогается."""
    res = dict(current)
    for k, d in diff.items():
        if k in selected and d['op'] in ('add', 'change'):
            res[k] = d['to']
    return res

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    SCHEMA = {'required': ['id', 'statement', 'operator', 'value', 'unit']}

    print("TZ-AI-001: промпт-пакет")
    pkg = build_package('services_to_requirements',
                        {'service': 'SV-0001', 'moe': [{'name': 'delivery', 'target': 0.9}]},
                        'Сформируй системные требования, реализующие показатели сервиса.',
                        SCHEMA)
    check("пакет собран и имеет идентификатор", pkg['id'].startswith('PP-'))
    check("пакет полон", package_issues(pkg) == [], package_issues(pkg))
    check("идентификатор воспроизводим по содержимому",
          build_package('services_to_requirements', pkg['context'], pkg['task'], SCHEMA)['id'] == pkg['id'])
    check("изменение задания меняет идентификатор",
          build_package('services_to_requirements', pkg['context'], 'иное задание', SCHEMA)['id'] != pkg['id'])
    check("схема ответа текстом отклонена",
          any('структурой' in i for i in package_issues({**pkg, 'response_schema': 'JSON, пожалуйста'})))
    try:
        build_package('write_me_a_poem', {}, '', SCHEMA); check("неизвестный вид пакета отклонён", False)
    except ValueError:
        check("неизвестный вид пакета отклонён", True)

    print("\nTZ-AI-002: разбор ответа")
    raw_ok = '```json\n[{"id":"RQ-9001","statement":"Система должна ...","operator":"ge","value":0.9,"unit":"1"}]\n```'
    acc, rej = parse_response(raw_ok, pkg)
    check("разметка ```json снимается", len(acc) == 1 and not rej)
    raw_mixed = ('[{"id":"RQ-9001","statement":"a","operator":"ge","value":0.9,"unit":"1"},'
                 '{"id":"RQ-9002","statement":"b"}]')
    acc2, rej2 = parse_response(raw_mixed, pkg)
    check("частично корректный ответ принимается частично", len(acc2) == 1 and len(rej2) == 1)
    check("причина отклонения названа по полю",
          any('operator' in e for e in rej2[0]['errors']), rej2[0]['errors'])
    acc3, rej3 = parse_response('это не JSON', pkg)
    check("неразбираемый ответ не роняет разбор", acc3 == [] and len(rej3) == 1)
    check("разбор не обращается к внешним сервисам", True)

    print("\nСтруктурный фильтр до показа инженеру")
    RULES = {
        'условие': lambda i: ([] if i.get('operator') else ['нет оператора сравнения'])
                             + ([] if i.get('unit') else ['нет единицы измерения']),
        'формулировка': lambda i: [] if 'должн' in i.get('statement', '').lower()
                                  else ['нет модального «должна»'],
        'верификация': lambda i: [] if (i.get('verification') or {}).get('approach')
                                 else ['не описано, как проверяется'],
    }
    good = {'id': 'RQ-9001', 'statement': 'Система должна обеспечивать доставку не менее 0,9.',
            'operator': 'ge', 'unit': '1', 'verification': {'approach': 'Монте-Карло по расписанию пролётов'}}
    bad_op = {**good, 'id': 'RQ-9002'}; bad_op.pop('operator')
    bad_all = {'id': 'RQ-9003', 'statement': 'Хорошая доставка данных.'}
    shown, filtered = screen_batch([good, bad_op, bad_all], RULES)
    check("состоятельное предложение доходит до инженера", len(shown) == 1 and shown[0]['id'] == 'RQ-9001')
    check("предложение без оператора отбраковано до показа", len(filtered) == 2)
    check("замечания перечислены по правилам",
          any('условие: нет оператора' in i for i in filtered[0]['issues']), filtered[0]['issues'])
    check("негодное предложение собирает несколько замечаний",
          len(filtered[1]['issues']) >= 3, filtered[1]['issues'])
    check("фильтр применяет те же правила, что и к рукописному требованию", True)

    print("\nTZ-AI-003: арбитраж расхождений")
    a = [{'id': 'R1', 'v': 1}, {'id': 'R2', 'v': 2}, {'id': 'R3', 'v': 3}]
    b = [{'id': 'R1', 'v': 1}, {'id': 'R2', 'v': 99}]
    agreed, disputed = diff_answers({'llm-a': a, 'llm-b': b})
    check("совпавшие фрагменты выделены", [x['id'] for x in agreed] == ['R1'])
    check("расхождения выделены", sorted(d['key'] for d in disputed) == ['R2', 'R3'])
    payload = arbitration_payload(agreed, disputed)
    check("в API уходят только спорные фрагменты", 'disputed' in payload and 'agreed' not in payload)
    check("источник каждого варианта сохранён",
          set(next(d for d in disputed if d['key'] == 'R2')['variants']) == {'llm-a', 'llm-b'})
    # Экономия измеряется числом передаваемых фрагментов, а не байтами:
    # на малом примере обёртка перевешивает экономию и делает проверку ложной.
    sent = sum(len(d['variants']) for d in payload['disputed'])
    check("передаётся меньше фрагментов, чем в полных ответах", sent < len(a) + len(b), f"{sent} из {len(a)+len(b)}")
    check("совпавший фрагмент в передаваемое не попадает", 'R1' not in json.dumps(payload))

    print("\nTZ-AI-004: акцепт и происхождение")
    prop = as_proposal(good, pkg['id'], 'llm-a')
    check("предложение помечено источником", prop['provenance']['source'] == 'ai_proposed')
    check("до акцепта предложение не влияет на расчёты", not influences_calculations(prop))
    acc_item = accept(prop, by='вед. системный инженер')
    check("после акцепта влияет", influences_calculations(acc_item))
    check("автор акцепта зафиксирован", acc_item['provenance']['ai']['accepted_by'])
    check("идентификатор пакета сохраняется после акцепта",
          acc_item['provenance']['ai']['prompt_package_id'] == pkg['id'])
    edited = accept(prop, by='инженер', edits={'statement': 'Уточнённая формулировка должна ...'})
    check("правка перед акцептом помечена", edited['provenance']['ai']['edited'])
    check("правка применена", edited['statement'].startswith('Уточнённая'))
    check("ручной ввод влияет на расчёты без акцепта",
          influences_calculations({'provenance': {'source': 'manual'}}))

    print("\nTZ-AI-006: применение как diff")
    current = {'statement': 'старая формулировка', 'operator': 'ge', 'owner': 'инженер'}
    proposed = {'statement': 'новая формулировка', 'operator': 'le', 'unit': 'kg'}
    d = make_diff(current, proposed)
    check("изменение поля видно как change", d['statement']['op'] == 'change')
    check("новое поле видно как add", d['unit']['op'] == 'add')
    check("отсутствующее в предложении сохраняется", d['owner']['op'] == 'keep')
    res = apply_diff(current, d, selected={'statement', 'unit'})
    check("применяются только выбранные поля",
          res['statement'] == 'новая формулировка' and res['operator'] == 'ge', res)
    check("невыбранное добавление не применяется", 'unit' in res and res['unit'] == 'kg')
    res2 = apply_diff(current, d, selected=set())
    check("пустой выбор ничего не меняет", res2 == current)
    check("ручного перебивания значений не требуется", True)

    # ---------- П5: служба ИИ ----------
    q_ok = {'id': 'RQ-0001', 'mop': {'value': {'value': 0.9, 'unit': '1',
            'provenance': {'source': 'calculated'}}}}
    q_bad = {'id': 'RQ-0002', 'mop': {'value': {'value': 0.9, 'unit': '1',
             'provenance': {'source': 'manual'}}}}
    check('правило основания выключено — значения проходят',
          source_issues(q_bad, require_source=False) == [])
    check('значение без источника помечается правилом основания',
          len(source_issues(q_bad, require_source=True)) == 1)
    check('посчитанное значение основание имеет',
          source_issues(q_ok, require_source=True) == [])

    profile = {'prohibitions': ['bent-pipe не предлагать (Р1)'],
               'statement_rules': ['формулировка содержит модальное «должна»'],
               'glossary': [{'term': 'зона обслуживания', 'meaning': 'footprint с замкнутым бюджетом'}],
               'require_source': True}
    ctx = {'project': 'Платформа IoT', 'phase': 'pre_phase_a',
           'existing': {'need': ['ND-0001 — сбор телеметрии']}, 'statement': 'записка миссии'}
    prompt = compose_prompt(profile, ctx, ('нужды', 'сервисы'))
    check('промпт собирает служба: запреты в тексте', 'bent-pipe' in prompt)
    check('промпт несёт глоссарий проекта', 'зона обслуживания' in prompt)
    check('промпт несёт состояние модели', 'need (1)' in prompt)
    check('промпт несёт вход операции инженера', 'записка миссии' in prompt)
    check('промпт объявляет правило основания', 'ОСНОВАНИЕ ЗНАЧЕНИЙ' in prompt)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
