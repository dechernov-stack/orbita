#!/usr/bin/env python3
"""Круговой обмен ReqIF на демо-проекте и сверка с XSD OMG (шаг 11.2, CI).

Путь тот же, что у изделия: требования демо-проекта → отображение эталона
(spec/reqif_semantics.py) → служба обмена (ops/exchange/reqif_service.py) →
файл ReqIF → строгая проверка `reqif validate --use-reqif-schema` → разбор
той же библиотекой → сверка с исходными объектами.

Отдельного «тестового» пути нет нарочно: проверка, идущая другим кодом,
подтверждала бы другой код.

Запуск: python3 tools/check_reqif_roundtrip.py (нужен пакет reqif==0.0.47).
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'spec'))
sys.path.insert(0, str(ROOT / 'ops' / 'exchange'))

import reqif_semantics as rs  # noqa: E402

try:
    from reqif_service import build_reqif, parse_reqif  # noqa: E402
except ModuleNotFoundError as e:
    # Пропуск объявляется вслух и валит проверку там, где пакет обязан быть (CI)
    print(f'ОШИБКА: служба обмена недоступна: {e}. Установите пакет reqif==0.0.47')
    sys.exit(1)

EXPORTED_AT = '2026-08-20T12:00:00.000+00:00'


def demo_project():
    out = subprocess.run(
        [sys.executable, str(ROOT / 'spec' / 'demo_project.py'), '--dump'],
        capture_output=True, text=True, check=True, cwd=ROOT)
    return json.loads(out.stdout)


def export_payload(project):
    """Полезная нагрузка службы из отображения эталона — того же, что в ядре.

    Нужды выгружаются вместе с требованиями: связь трассировки ведёт от нужды,
    и файл, где конец связи не существует, семантически сломан для принимающего
    инструмента — это ловит `reqif validate`.
    """
    requirements = project['requirements']
    spec_objects = [rs.to_spec_object(r) for r in requirements]
    attributes = {name: kind for _, name, kind in rs.REQUIREMENT_MAP}
    # незнакомые поля модели получают строковые атрибуты X-*
    for so in spec_objects:
        for name in so['VALUES']:
            attributes.setdefault(name, 'string')
    datatypes = {name: {'kind': kind,
                        'values': rs.ENUM_VALUES.get(name) if kind == 'enum' else None}
                 for name, kind in attributes.items()}

    need_attributes = {'ReqIF.ForeignID': 'string', 'ReqIF.Text': 'xhtml',
                       'Stakeholder': 'string'}
    need_objects = [
        {'identifier': rs.identifier('SO', n['id']), 'type': rs.SPEC_OBJECT_TYPES['need'],
         'values': {'ReqIF.ForeignID': n['id'], 'ReqIF.Text': n['statement'],
                    'Stakeholder': n['stakeholder']['name']}}
        for n in project['needs']
    ]
    datatypes.setdefault('Stakeholder', {'kind': 'string', 'values': None})

    # Сервисы — звено цепочки «нужда → сервис → требование»: часть трассировки
    # ведёт от них, а связь без конца семантически ломает файл
    service_attributes = {'ReqIF.ForeignID': 'string', 'ReqIF.Text': 'xhtml'}
    service_objects = [
        {'identifier': rs.identifier('SO', s['id']), 'type': rs.SPEC_OBJECT_TYPES['service'],
         'values': {'ReqIF.ForeignID': s['id'], 'ReqIF.Text': s['name']}}
        for s in project['services']
    ]

    links = [{'from': t['ref'], 'to': r['id'], 'kind': 'trace'}
             for r in requirements for t in r.get('traces_up', [])]
    return {
        'title': 'Орбита: выгрузка требований',
        'exported_at': EXPORTED_AT,
        'datatypes': datatypes,
        'object_types': {
            rs.SPEC_OBJECT_TYPES['requirement']:
                {'long_name': 'Requirement', 'attributes': attributes},
            rs.SPEC_OBJECT_TYPES['need']:
                {'long_name': 'Need', 'attributes': need_attributes},
            rs.SPEC_OBJECT_TYPES['service']:
                {'long_name': 'Service', 'attributes': service_attributes},
        },
        'relation_types': {v: k for k, v in rs.RELATION_TYPES.items()},
        'objects': [{'identifier': so['IDENTIFIER'], 'type': so['TYPE'],
                     'values': so['VALUES']} for so in spec_objects]
                   + need_objects + service_objects,
        'relations': [{'identifier': r['IDENTIFIER'], 'type': r['TYPE'],
                       'source': r['SOURCE'], 'target': r['TARGET']}
                      for r in rs.to_spec_relations(links)],
    }


def main():
    ok = fail = 0

    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond:
            ok += 1
            print(f'  + {name}')
        else:
            fail += 1
            print(f'  - {name} {detail}')

    project = demo_project()
    requirements = project['requirements']
    payload = export_payload(project)
    xml = build_reqif(payload)
    check('выгрузка демо-проекта собирается', len(xml) > 1000, f'{len(xml)} байт')
    check('повторная выгрузка идентична', build_reqif(payload) == xml)

    with tempfile.NamedTemporaryFile('w', suffix='.reqif', delete=False,
                                     encoding='utf-8') as f:
        f.write(xml)
        path = f.name

    # Модулем, а не именем `reqif` из PATH: пакет установлен — CLI доступен,
    # независимо от того, куда pip положил скрипты
    strict = subprocess.run(
        [sys.executable, '-m', 'reqif.cli.main', 'validate', '--use-reqif-schema', path],
        capture_output=True, text=True)
    last = (strict.stdout.strip().splitlines() or ['?'])[-1]
    check('строгая сверка с XSD OMG без замечаний',
          '0 errors, 0 schema issues, 0 semantic issues' in last.replace(' found', ''),
          last)

    back = parse_reqif(path)
    check('файл разбирается той же библиотекой',
          len(back['objects'])
          == len(requirements) + len(project['needs']) + len(project['services']),
          f"{len(back['objects'])} объектов")
    check('нужды выгружены своим типом',
          sum(1 for o in back['objects'] if o['type'] == rs.SPEC_OBJECT_TYPES['need'])
          == len(project['needs']))

    by_foreign = {o['values'].get('ReqIF.ForeignID'): o for o in back['objects']
                  if o['type'] == rs.SPEC_OBJECT_TYPES['requirement']}
    lost_ids = [r['id'] for r in requirements if r['id'] not in by_foreign]
    check('все требования вернулись', lost_ids == [], str(lost_ids))

    diffs = []
    for r in requirements:
        restored = rs.from_spec_object(
            {'VALUES': by_foreign[r['id']]['values']})
        original = rs.from_spec_object(rs.to_spec_object(r))
        if restored != original:
            diffs.append((r['id'], restored, original))
    check('круговой обмен сохраняет атрибуты', diffs == [],
          diffs[0][0] if diffs else '')

    rq0100 = by_foreign.get('RQ-0100', {}).get('values', {})
    check('оператор условия вернулся перечислением', rq0100.get('MeasureOperator') == 'le',
          str(rq0100.get('MeasureOperator')))
    check('значение условия вернулось числом', rq0100.get('MeasureValue') == 100.0,
          repr(rq0100.get('MeasureValue')))
    check('связи трассировки вернулись', len(back['relations']) >= 1,
          str(len(back['relations'])))

    print(f'\nИтог: пройдено {ok}, провалено {fail}')
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    main()
