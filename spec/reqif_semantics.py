#!/usr/bin/env python3
"""Исполняемый эталон отображения модели в ReqIF (шаг 11.2, ADR-023).

ReqIF — стандарт OMG. Трудность не в XML, а в отображении: наши структурные
поля (условие с оператором, события верификации, свёртка бюджета) должны попасть
в ReqIF так, чтобы принимающий инструмент мог ими пользоваться, а не получить
строку с сериализованным JSON.

  типы          у каждого вида объекта свой SPEC-OBJECT-TYPE
  атрибуты      перечислимые поля — ENUMERATION, числа — INTEGER/REAL, даты — DATE
  структура     составное поле раскладывается на отдельные атрибуты, не в строку
  связи         трассировка и распределение — SPEC-RELATION с типами
  тождество     один объект даёт один и тот же IDENTIFIER между выгрузками
  обмен         круговой проход сохраняет атрибуты, связи и незнакомые поля
"""
import sys, hashlib

DATATYPES = {'string': 'DATATYPE-DEFINITION-STRING', 'integer': 'DATATYPE-DEFINITION-INTEGER',
             'real': 'DATATYPE-DEFINITION-REAL', 'date': 'DATATYPE-DEFINITION-DATE',
             'boolean': 'DATATYPE-DEFINITION-BOOLEAN', 'enum': 'DATATYPE-DEFINITION-ENUMERATION',
             'xhtml': 'DATATYPE-DEFINITION-XHTML'}

# Отображение полей требования. Составное условие раскладывается на три атрибута:
# принимающий инструмент должен уметь фильтровать по оператору и сортировать по значению.
REQUIREMENT_MAP = [
    ('id',                 'ReqIF.ForeignID',      'string'),
    ('statement',          'ReqIF.Text',           'xhtml'),
    ('level',              'Level',                'enum'),
    ('category',           'Category',             'enum'),
    ('rationale',          'Rationale',            'xhtml'),
    ('mop.name',           'MeasureName',          'string'),
    ('mop.operator',       'MeasureOperator',      'enum'),
    ('mop.value.value',    'MeasureValue',         'real'),
    ('mop.value.unit',     'MeasureUnit',          'string'),
    ('lifecycle.status',   'Status',               'enum'),
    ('owner',              'Owner',                'string'),
]
ENUM_VALUES = {
    'Level': ['project', 'system', 'element'],
    'Category': ['functional', 'performance', 'interface', 'operational',
                 'reliability', 'safety', 'environmental', 'constraint'],
    'MeasureOperator': ['eq', 'le', 'ge', 'lt', 'gt', 'range', 'tolerance'],
    'Status': ['Draft', 'Preliminary', 'Approved', 'Baseline', 'Cancelled'],
}
SPEC_OBJECT_TYPES = {'requirement': 'ST-REQUIREMENT', 'need': 'ST-NEED',
                     'service': 'ST-SERVICE', 'component': 'ST-COMPONENT',
                     'risk': 'ST-RISK'}
RELATION_TYPES = {'trace': 'RT-TRACE', 'derive': 'RT-DERIVE',
                  'allocation': 'RT-ALLOCATION', 'verification': 'RT-VERIFICATION'}

def identifier(kind, key):
    """IDENTIFIER обязан быть устойчивым: одна и та же сущность между выгрузками
    получает то же значение, иначе принимающий инструмент видит новый объект
    вместо изменённого и теряет историю."""
    return f'{kind}-' + hashlib.blake2b(f'{kind}:{key}'.encode(), digest_size=8).hexdigest()

def dig(obj, path):
    cur = obj
    for part in path.split('.'):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur

def datatype_definitions(mapping=REQUIREMENT_MAP):
    out = {}
    for _, name, kind in mapping:
        out[name] = {'type': DATATYPES[kind],
                     'values': ENUM_VALUES.get(name) if kind == 'enum' else None}
    return out

def mapping_issues(mapping=REQUIREMENT_MAP):
    p = []
    for src, name, kind in mapping:
        if kind not in DATATYPES:
            p.append(f'{name}: неизвестный тип данных {kind}')
        if kind == 'enum' and name not in ENUM_VALUES:
            p.append(f'{name}: перечисление без набора значений')
        if kind == 'string' and src.endswith(('operator', 'status', 'level', 'category')):
            p.append(f'{name}: перечислимое поле отображено строкой, фильтрация потеряется')
    return p

def to_spec_object(req, mapping=REQUIREMENT_MAP):
    vals = {}
    for src, name, _ in mapping:
        v = dig(req, src)
        if v is not None:
            vals[name] = v
    # незнакомые поля не теряются: уходят в отдельные атрибуты с префиксом
    known = {m[0].split('.')[0] for m in mapping}
    for k, v in req.items():
        if k not in known and not isinstance(v, (dict, list)):
            vals[f'X-{k}'] = v
    return {'IDENTIFIER': identifier('SO', req['id']),
            'TYPE': SPEC_OBJECT_TYPES['requirement'], 'VALUES': vals}

def from_spec_object(so, mapping=REQUIREMENT_MAP):
    out = {}
    for src, name, _ in mapping:
        if name in so['VALUES']:
            cur, parts = out, src.split('.')
            for part in parts[:-1]:
                cur = cur.setdefault(part, {})
            cur[parts[-1]] = so['VALUES'][name]
    for k, v in so['VALUES'].items():
        if k.startswith('X-'):
            out[k[2:]] = v
    return out

def to_spec_relations(links):
    out = []
    for l in links:
        if l['kind'] not in RELATION_TYPES:
            raise ValueError(f'вид связи {l["kind"]} не отображён в SPEC-RELATION-TYPE')
        out.append({'IDENTIFIER': identifier('SR', f'{l["from"]}>{l["to"]}:{l["kind"]}'),
                    'TYPE': RELATION_TYPES[l['kind']],
                    'SOURCE': identifier('SO', l['from']), 'TARGET': identifier('SO', l['to'])})
    return out

def flattened_as_string(so):
    """Признак ошибки: составное значение сложено в одну строку."""
    return [k for k, v in so['VALUES'].items()
            if isinstance(v, str) and v.strip().startswith(('{', '['))]

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    REQ = {'id': 'RQ-0100', 'level': 'system', 'category': 'performance',
           'statement': 'Сухая масса КА не должна превышать 100 кг.',
           'rationale': 'Ограничение средства выведения',
           'mop': {'name': 'Сухая масса', 'operator': 'le',
                   'value': {'value': 100, 'unit': 'kg'}, 'rollup': 'sum'},
           'lifecycle': {'status': 'Baseline', 'version': '3'},
           'owner': 'вед. системный инженер', 'custom_field': 'значение заказчика'}

    print("Отображение типов")
    check("отображение без замечаний", mapping_issues() == [], mapping_issues())
    dd = datatype_definitions()
    check("оператор условия — перечисление, не строка",
          dd['MeasureOperator']['type'] == DATATYPES['enum'])
    check("набор значений оператора задан",
          set(dd['MeasureOperator']['values']) == set(ENUM_VALUES['MeasureOperator']))
    check("значение показателя — число, не строка",
          dd['MeasureValue']['type'] == DATATYPES['real'])
    check("формулировка — размеченный текст", dd['ReqIF.Text']['type'] == DATATYPES['xhtml'])
    bad = mapping_issues([('mop.operator', 'MeasureOperator', 'string')])
    check("перечислимое поле строкой выявлено", any('фильтрация' in i for i in bad), bad)
    check("у каждого вида объекта свой тип",
          len(set(SPEC_OBJECT_TYPES.values())) == len(SPEC_OBJECT_TYPES))

    print("\nСоставное поле не сворачивается в строку")
    so = to_spec_object(REQ)
    check("условие разложено на три атрибута",
          {'MeasureOperator', 'MeasureValue', 'MeasureUnit'} <= set(so['VALUES']))
    check("оператор доступен отдельным значением", so['VALUES']['MeasureOperator'] == 'le')
    check("единица доступна отдельным значением", so['VALUES']['MeasureUnit'] == 'kg')
    check("сериализованных структур в значениях нет", flattened_as_string(so) == [])
    bad_so = {'VALUES': {'Measure': '{"operator":"le","value":100}'}}
    check("сериализованная структура выявляется", flattened_as_string(bad_so) == ['Measure'])

    print("\nУстойчивость идентификаторов")
    check("один объект — один идентификатор", to_spec_object(REQ)['IDENTIFIER'] == so['IDENTIFIER'])
    changed = to_spec_object({**REQ, 'lifecycle': {'status': 'Draft', 'version': '4'}})
    check("изменение содержимого идентификатор не меняет",
          changed['IDENTIFIER'] == so['IDENTIFIER'])
    check("другой объект — другой идентификатор",
          to_spec_object({**REQ, 'id': 'RQ-0101'})['IDENTIFIER'] != so['IDENTIFIER'])
    check("идентификатор связи устойчив",
          to_spec_relations([{'from': 'A', 'to': 'B', 'kind': 'trace'}])[0]['IDENTIFIER']
          == to_spec_relations([{'from': 'A', 'to': 'B', 'kind': 'trace'}])[0]['IDENTIFIER'])

    print("\nСвязи")
    rels = to_spec_relations([{'from': 'RQ-0100', 'to': 'RQ-0101', 'kind': 'derive'},
                              {'from': 'ND-0001', 'to': 'RQ-0100', 'kind': 'trace'}])
    check("виды связей различаются типами",
          {r['TYPE'] for r in rels} == {'RT-DERIVE', 'RT-TRACE'})
    check("концы связи ссылаются на идентификаторы объектов",
          rels[0]['SOURCE'] == identifier('SO', 'RQ-0100'))
    try:
        to_spec_relations([{'from': 'A', 'to': 'B', 'kind': 'что-то'}])
        check("неотображённый вид связи отклонён", False)
    except ValueError:
        check("неотображённый вид связи отклонён", True)

    print("\nКруговой обмен")
    back = from_spec_object(so)
    check("идентификатор сохраняется", back['id'] == REQ['id'])
    check("оператор условия сохраняется", back['mop']['operator'] == 'le')
    check("значение и единица сохраняются",
          back['mop']['value'] == {'value': 100, 'unit': 'kg'})
    check("статус сохраняется", back['lifecycle']['status'] == 'Baseline')
    check("незнакомое поле не теряется", back['custom_field'] == 'значение заказчика')
    check("формулировка сохраняется", back['statement'] == REQ['statement'])
    twice = from_spec_object(to_spec_object(back))
    check("повторный проход ничего не меняет", twice == back)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
