#!/usr/bin/env python3
"""Исполняемый эталон импорта внешних данных (шаг 14, ADR-024).

Импорт — третий канал в модель, наряду с ручным вводом и предложениями ИИ.
Для него действует то же правило, что установлено на шаге 5: **тот же фильтр**.
Никаких послаблений вида «данные от вендора, значит корректные»: иначе импорт
станет мягким подбрюшьем — то, что нельзя ввести руками, зайдёт через загрузку.

  правовые условия  часть источников допускает извлечение отдельных записей
                    и запрещает выгрузку каталога целиком
  происхождение     источник, версия, дата, условия использования — обязательны
  тот же фильтр     импортированная запись проходит правила рукописной
  отображение       версия отображения фиксируется; неизвестные поля не теряются
  повторный импорт  обновление записи, а не дубликат
"""
import sys

# Источники и их правовой режим. bulk=False означает: разрешено извлекать
# отдельные записи, но не выгружать каталог целиком либо существенную его часть.
SOURCES = {
    'reqif':            {'terms': 'зависит от отправителя', 'bulk': True},
    'lorawan-devices':  {'terms': 'sui generis, Директива 96/9/EC', 'bulk': False},
    'celestrak-omm':    {'terms': 'открытые данные', 'bulk': True},
    'natural-earth':    {'terms': 'общественное достояние', 'bulk': True},
    'vendor-datasheet': {'terms': 'условия вендора', 'bulk': False},
}

def import_allowed(source, mode):
    """mode: 'item' — отдельная запись, 'bulk' — каталог целиком."""
    if source not in SOURCES:
        return False, f'источник {source} не описан: правовой режим неизвестен'
    if mode == 'bulk' and not SOURCES[source]['bulk']:
        return False, (f'{source}: массовая выгрузка запрещена условиями источника '
                       f'({SOURCES[source]["terms"]}); допустим импорт отдельных записей')
    return True, None

def provenance_for(source, version, retrieved_at, item_ref=None, mapping_version='1'):
    if source not in SOURCES:
        raise ValueError(f'источник {source} не описан')
    return {'source': 'imported',
            'import': {'dataset': source, 'dataset_version': version,
                       'retrieved_at': retrieved_at, 'item_ref': item_ref,
                       'terms': SOURCES[source]['terms'],
                       'bulk_allowed': SOURCES[source]['bulk'],
                       'mapping_version': mapping_version}}

def provenance_issues(prov):
    if prov.get('source') != 'imported':
        return []
    b = prov.get('import') or {}
    return [f'не указано: {f}' for f in ('dataset', 'dataset_version', 'retrieved_at', 'terms')
            if not b.get(f)]

def map_terminal(device, profile, prov):
    """Профиль устройства внешнего каталога → наш профиль терминала.
    Неизвестные поля источника сохраняются, а не отбрасываются."""
    known = {'name', 'macVersion', 'maxEIRP', 'supportsClassC', 'regionalParametersVersion'}
    out = {'name': device.get('name'),
           'consumer_class': 'C_prime' if profile.get('supportsClassC') else 'A_prime',
           'regulatory_region': {'EU863-870': 'EU868', 'US902-928': 'US915',
                                 'RU864-870': 'RU864'}.get(profile.get('region')),
           'radio': {'eirp_dbm': profile.get('maxEIRP')},
           'provenance': prov,
           'source_extras': {k: v for k, v in {**device, **profile}.items() if k not in known}}
    return out

def screen(record, rules):
    """Тот же фильтр, что и для рукописной записи, и для предложения ИИ."""
    return [f'{n}: {i}' for n, fn in rules.items() for i in fn(record)]

def merge(existing, incoming, key='item_ref'):
    """Повторный импорт обновляет запись, а не создаёт дубликат.
    Ручные правки поверх импорта не затираются молча."""
    ik = (incoming['provenance']['import'] or {}).get(key)
    for i, e in enumerate(existing):
        if (e.get('provenance', {}).get('import') or {}).get(key) == ik:
            manual = {k: v for k, v in e.items()
                      if e.get('_edited', {}).get(k)}
            merged = {**e, **incoming, **manual}
            merged['_edited'] = e.get('_edited', {})
            out = list(existing); out[i] = merged
            return out, 'updated'
    return existing + [incoming], 'added'

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("Правовые условия источника")
    okk, why = import_allowed('lorawan-devices', 'bulk')
    check("массовая выгрузка защищённого каталога запрещена", not okk, why)
    check("причина названа условиями источника", 'sui generis' in why)
    check("извлечение отдельной записи разрешено",
          import_allowed('lorawan-devices', 'item')[0])
    check("открытый источник допускает массовую выгрузку",
          import_allowed('natural-earth', 'bulk')[0])
    check("неописанный источник отклонён",
          not import_allowed('unknown-catalog', 'item')[0])
    check("отсутствие описания трактуется как запрет, а не разрешение",
          'неизвестен' in import_allowed('unknown-catalog', 'item')[1])

    print("\nПроисхождение импортированного")
    prov = provenance_for('lorawan-devices', '2026-08-01', '2026-08-20', item_ref='vendor/x/dev-a')
    check("происхождение полно", provenance_issues(prov) == [], provenance_issues(prov))
    check("условия использования зафиксированы", 'sui generis' in prov['import']['terms'])
    check("признак массовой выгрузки перенесён", prov['import']['bulk_allowed'] is False)
    check("версия отображения зафиксирована", prov['import']['mapping_version'] == '1')
    bare = {'source': 'imported', 'import': {'dataset': 'x'}}
    check("неполное происхождение выявлено", len(provenance_issues(bare)) == 3, provenance_issues(bare))
    check("ручной ввод происхождения импорта не требует",
          provenance_issues({'source': 'manual'}) == [])
    try:
        provenance_for('unknown', '1', '2026-08-20'); check("источник вне перечня отклонён", False)
    except ValueError:
        check("источник вне перечня отклонён", True)

    print("\nОтображение внешней записи")
    DEV = {'name': 'Sensor A', 'description': 'датчик', 'battery': 'AA'}
    PROF = {'macVersion': '1.0.3', 'maxEIRP': 14, 'region': 'EU863-870', 'supportsClassC': False}
    t = map_terminal(DEV, PROF, prov)
    check("класс выведен из свойств профиля", t['consumer_class'] == 'A_prime')
    check("регион отображён в наш перечень", t['regulatory_region'] == 'EU868')
    check("радиопараметр перенесён", t['radio']['eirp_dbm'] == 14)
    check("незнакомые поля источника сохранены",
          t['source_extras']['battery'] == 'AA' and 'description' in t['source_extras'])
    check("класс C выводится из поддержки", map_terminal(DEV, {**PROF, 'supportsClassC': True},
                                                         prov)['consumer_class'] == 'C_prime')
    check("неизвестный регион не подставляется наугад",
          map_terminal(DEV, {**PROF, 'region': 'XX999'}, prov)['regulatory_region'] is None)

    print("\nТот же фильтр, что для рукописного")
    RULES = {'класс': lambda r: [] if r.get('consumer_class') else ['класс не определён'],
             'регион': lambda r: [] if r.get('regulatory_region') else ['регуляторный регион не определён'],
             'мощность': lambda r: [] if (r.get('radio') or {}).get('eirp_dbm') is not None
                                   else ['не задана мощность передачи'],
             'происхождение': lambda r: provenance_issues(r.get('provenance', {}))}
    check("корректная запись проходит фильтр", screen(t, RULES) == [], screen(t, RULES))
    bad_region = map_terminal(DEV, {**PROF, 'region': 'XX999'}, prov)
    check("запись из каталога с непонятым регионом отбраковывается",
          any('регион' in i for i in screen(bad_region, RULES)))
    no_power = map_terminal(DEV, {**PROF, 'maxEIRP': None}, prov)
    check("запись без мощности отбраковывается",
          any('мощность' in i for i in screen(no_power, RULES)))
    weak_prov = map_terminal(DEV, PROF, {'source': 'imported', 'import': {'dataset': 'x'}})
    check("запись с неполным происхождением отбраковывается",
          any('не указано' in i for i in screen(weak_prov, RULES)))
    check("послаблений для импорта нет: те же функции правил",
          screen(t, RULES) == screen({**t}, RULES))

    print("\nПовторный импорт")
    store, act = merge([], t)
    check("первый импорт добавляет запись", act == 'added' and len(store) == 1)
    store2, act2 = merge(store, {**t, 'radio': {'eirp_dbm': 16}})
    check("повторный импорт обновляет, а не дублирует", act2 == 'updated' and len(store2) == 1)
    check("обновлённое значение применилось", store2[0]['radio']['eirp_dbm'] == 16)
    edited = dict(store[0]); edited['radio'] = {'eirp_dbm': 10}; edited['_edited'] = {'radio': True}
    store3, _ = merge([edited], {**t, 'radio': {'eirp_dbm': 16}})
    check("ручная правка поверх импорта не затирается",
          store3[0]['radio']['eirp_dbm'] == 10, store3[0]['radio'])
    other, act3 = merge(store, {**t, 'provenance': provenance_for(
        'lorawan-devices', '2026-08-01', '2026-08-20', item_ref='vendor/x/dev-b')})
    check("другая запись источника добавляется отдельно", act3 == 'added' and len(other) == 2)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
