#!/usr/bin/env python3
"""Исполняемый эталон входов моделирования (CR-005, ADR-021).

Дефект, найденный при работе с экранами: сценарий ссылался на конфигурацию
группировки, модель аппарата, карту спроса, набор станций и адаптер протокола,
но ни один из них модель хранить не умела. Ссылки указывали в никуда,
`input_versions` перечислял версии несуществующих объектов, и воспроизводимость
держалась на том, что данные не покидали сеанс.

  разрешение ссылок  все пять ссылок сценария указывают на хранимые объекты
  версии входов      охватывают все ссылки; изменение входа делает результат stale
  ключ результата    воспроизводимость определяется ссылками, версиями и зерном
  MEL                резерв по зрелости применяется к позициям ведомости (CR-006)
  доля витка         отсутствие значения — ошибка, а не равномерное деление (CR-007)
"""
import sys, math

PREFIX_TYPE = {'CN': 'constellation', 'CU': 'component_usage', 'DM': 'demand_map',
               'GS': 'ground_stations', 'PA': 'protocol_adapter', 'TP': 'terminal_profile'}
# Требуемый тип диктует ПОЛЕ, а не префикс ссылки: иначе подстановка объекта
# другого вида в чужое поле проходит незамеченной — префикс и объект согласованы
# между собой, а поле нет.
FIELD_TYPE = {'constellation_ref': 'constellation', 'carrier_ref': 'component_usage',
              'demand_map_ref': 'demand_map', 'ground_stations_ref': 'ground_stations',
              'protocol_adapter_ref': 'protocol_adapter'}
SCENARIO_REFS = list(FIELD_TYPE)

def ref_type(ref):
    return PREFIX_TYPE.get((ref or '')[:2])

def resolve_scenario(scenario, store):
    """Ссылки сценария обязаны разрешаться в хранимые объекты нужного типа."""
    problems = []
    for f in SCENARIO_REFS:
        ref = scenario.get(f)
        if not ref:
            problems.append(f'{f}: ссылка не задана'); continue
        want = FIELD_TYPE[f]
        if ref_type(ref) is None:
            problems.append(f'{f}: «{ref}» не соответствует ни одному типу объекта'); continue
        if ref_type(ref) != want:
            problems.append(f'{f}: ссылка {ref} ведёт на {ref_type(ref)}, ожидался {want}'); continue
        obj = store.get(ref)
        if obj is None:
            problems.append(f'{f}: объект {ref} в модели отсутствует')
        elif obj.get('type') != want:
            problems.append(f'{f}: {ref} имеет тип {obj.get("type")}, ожидался {want}')
    return problems

def input_versions_complete(scenario):
    """Версии фиксируются для всех входов: иначе результат невоспроизводим."""
    iv = scenario.get('input_versions') or {}
    return [f'версия входа не зафиксирована: {scenario[f]}'
            for f in SCENARIO_REFS if scenario.get(f) and scenario[f] not in iv]

def result_key(scenario):
    """Ключ воспроизводимости: ссылки с версиями, зерно, версии модулей."""
    iv = scenario.get('input_versions') or {}
    parts = [f'{scenario.get(f)}@{iv.get(scenario.get(f), "?")}' for f in SCENARIO_REFS]
    parts.append(f'seed={scenario.get("rng_seed")}')
    parts += [f'{k}={v}' for k, v in sorted((scenario.get('module_versions') or {}).items())]
    return '|'.join(parts)

def becomes_stale(scenario, changed_ref, new_version):
    """Изменение версии входа обесценивает результат."""
    before = result_key(scenario)
    s2 = dict(scenario, input_versions=dict(scenario['input_versions'], **{changed_ref: new_version}))
    return result_key(s2) != before

# ---------- CR-006: ведомость масс ----------
MATURITY_MARGIN = {'new': 0.25, 'modified': 0.15, 'existing': 0.05}

def mel_dry_mass(mel, system_margin=0.10):
    if not mel:
        raise ValueError('ведомость масс не задана: политику резервов не к чему применить')
    base = sum(i['mass_kg'] * i.get('quantity', 1) * (1 + MATURITY_MARGIN[i['maturity']]) for i in mel)
    return base * (1 + system_margin)

def mel_by_subsystem(mel):
    out = {}
    for i in mel:
        out[i['subsystem']] = out.get(i['subsystem'], 0.0) + i['mass_kg'] * i.get('quantity', 1)
    return out

# ---------- CR-007: доля витка ----------
def orbit_energy_balance(generated_wh, modes, orbit_h):
    """Доля витка в режиме обязана быть задана. Равномерное деление — молчаливое
    допущение, дающее правдоподобное, но бессмысленное число."""
    missing = [m['name'] for m in modes if m.get('orbit_fraction') is None]
    if missing:
        raise ValueError(f'доля витка не задана для режимов: {missing}')
    total = sum(m['orbit_fraction'] for m in modes)
    if not math.isclose(total, 1.0, abs_tol=1e-6):
        raise ValueError(f'доли витка в сумме дают {total}, а не 1')
    consumed = sum(m['power_w'] * m['orbit_fraction'] * orbit_h for m in modes)
    return generated_wh - consumed

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    STORE = {'CN-0001': {'type': 'constellation'}, 'CU-0001': {'type': 'component_usage'},
             'DM-0001': {'type': 'demand_map'},   'GS-0001': {'type': 'ground_stations'},
             'PA-0001': {'type': 'protocol_adapter'}}
    SC = {'id': 'SC-0001', 'constellation_ref': 'CN-0001', 'carrier_ref': 'CU-0001',
          'demand_map_ref': 'DM-0001', 'ground_stations_ref': 'GS-0001',
          'protocol_adapter_ref': 'PA-0001', 'rng_seed': 42,
          'input_versions': {'CN-0001': '1', 'CU-0001': '2', 'DM-0001': '1',
                             'GS-0001': '1', 'PA-0001': '3'},
          'module_versions': {'ballistics': '0.4.0'}}

    print("Разрешение ссылок сценария")
    check("полный сценарий разрешается", resolve_scenario(SC, STORE) == [], resolve_scenario(SC, STORE))
    check("отсутствующий объект выявлен",
          any('отсутствует' in p for p in resolve_scenario({**SC, 'demand_map_ref': 'DM-9999'}, STORE)))
    check("ссылка неизвестного вида выявлена",
          any('не соответствует' in p for p in resolve_scenario({**SC, 'carrier_ref': 'XX-0001'}, STORE)))
    check("незаданная ссылка выявлена",
          any('не задана' in p for p in resolve_scenario({**SC, 'ground_stations_ref': None}, STORE)))
    check("подмена объекта другого вида выявлена",
          any('ожидался' in p for p in resolve_scenario({**SC, 'demand_map_ref': 'CU-0001'}, STORE)),
          resolve_scenario({**SC, 'demand_map_ref': 'CU-0001'}, STORE))
    check("объект с несоответствующим типом в хранилище выявлен",
          any('имеет тип' in p for p in resolve_scenario(SC, {**STORE, 'DM-0001': {'type': 'component'}})))
    check("все пять входов проверяются", len(SCENARIO_REFS) == 5)

    print("\nВерсии входов и воспроизводимость")
    check("версии зафиксированы для всех входов", input_versions_complete(SC) == [])
    check("пропущенная версия выявлена",
          input_versions_complete({**SC, 'input_versions': {'CN-0001': '1'}}) != [])
    k = result_key(SC)
    check("ключ результата воспроизводим", result_key(dict(SC)) == k)
    check("ключ включает зерно", 'seed=42' in k)
    check("ключ включает версии модулей", 'ballistics=0.4.0' in k)
    check("другое зерно даёт другой ключ", result_key({**SC, 'rng_seed': 43}) != k)
    check("изменение версии входа обесценивает результат", becomes_stale(SC, 'DM-0001', '2'))
    check("та же версия результат не обесценивает", not becomes_stale(SC, 'DM-0001', '1'))
    check("версия модуля входит в ключ",
          result_key({**SC, 'module_versions': {'ballistics': '0.5.0'}}) != k)

    print("\nCR-006: ведомость масс")
    MEL = [{'name': 'Корпус', 'subsystem': 'structure', 'mass_kg': 8, 'maturity': 'existing'},
           {'name': 'СЭП', 'subsystem': 'power', 'mass_kg': 6, 'maturity': 'modified'},
           {'name': 'Приёмник', 'subsystem': 'comms', 'mass_kg': 2, 'maturity': 'new', 'quantity': 2},
           {'name': 'ПН', 'subsystem': 'payload', 'mass_kg': 10, 'maturity': 'new'}]
    m = mel_dry_mass(MEL)
    check("масса считается по ведомости с резервами", m > sum(i['mass_kg'] for i in MEL), f"{m:.2f}")
    check("кратность позиции учтена", mel_by_subsystem(MEL)['comms'] == 4)
    check("новая позиция даёт больший резерв, чем существующая",
          mel_dry_mass([{'name': 'x', 'subsystem': 'power', 'mass_kg': 10, 'maturity': 'new'}])
          > mel_dry_mass([{'name': 'x', 'subsystem': 'power', 'mass_kg': 10, 'maturity': 'existing'}]))
    check("разбивка по подсистемам собирается", set(mel_by_subsystem(MEL)) ==
          {'structure', 'power', 'comms', 'payload'})
    try:
        mel_dry_mass([]); check("отсутствие ведомости — ошибка, а не ноль", False)
    except ValueError:
        check("отсутствие ведомости — ошибка, а не ноль", True)

    print("\nCR-007: доля витка режима")
    MODES = [{'name': 'standby', 'power_w': 12, 'orbit_fraction': 0.55},
             {'name': 'rx', 'power_w': 25, 'orbit_fraction': 0.30},
             {'name': 'downlink', 'power_w': 45, 'orbit_fraction': 0.15}]
    bal = orbit_energy_balance(60.0, MODES, 1.594)
    check("баланс считается по заданным долям", isinstance(bal, float))
    check("баланс не равен нулю по построению", abs(bal) > 1e-9, bal)
    try:
        orbit_energy_balance(60.0, [{'name': 'rx', 'power_w': 25}], 1.594)
        check("незаданная доля витка — ошибка, а не равномерное деление", False)
    except ValueError:
        check("незаданная доля витка — ошибка, а не равномерное деление", True)
    try:
        orbit_energy_balance(60.0, [{'name': 'a', 'power_w': 10, 'orbit_fraction': 0.4},
                                    {'name': 'b', 'power_w': 10, 'orbit_fraction': 0.4}], 1.594)
        check("доли, не дающие единицу, отклонены", False)
    except ValueError:
        check("доли, не дающие единицу, отклонены", True)
    check("рост потребления снижает баланс",
          orbit_energy_balance(60.0, [{**MODES[0], 'power_w': 30}] + MODES[1:], 1.594) < bal)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
