#!/usr/bin/env python3
"""Исполняемый эталон редактирования через интерфейс (шаг 15).

Экраны умеют показывать, но не умеют вводить: единственный вход данных —
`seedDemo`. Мастер ведёт инженера по чужому заполненному проекту, а не от пустого
к пакету. Это не косметика — отсутствует рабочий слой.

Дополнительно: сессия параллельного проектирования означает НЕСКОЛЬКИХ человек
одновременно. Модель однопользовательская; без проверки версии второй молча
затрёт первого.

  создание      объект получает идентификатор, происхождение и автора
  изменение     требует версию; расхождение — отказ с показом чужой правки
  удаление      статус Cancelled, объект остаётся
  правила       те же функции, что для импорта и предложений ИИ
  черновик      неполнота допустима; Baseline — нет
  отмена        предыдущая версия доступна
"""
import sys

def create(store, obj, author, next_id):
    if any(o['id'] == obj.get('id') for o in store):
        raise ValueError(f'объект {obj["id"]} уже существует')
    new = {**obj, 'id': obj.get('id') or next_id,
           'version': 1, 'status': obj.get('status', 'Draft'),
           'provenance': {'source': 'manual', 'author': author},
           'edited_by': author}
    return store + [new], new

def update(store, oid, changes, base_version, author):
    """Оптимистичная блокировка: правка требует версии, на которой основана."""
    cur = next((o for o in store if o['id'] == oid), None)
    if cur is None:
        raise ValueError(f'объект {oid} не найден')
    if cur['version'] != base_version:
        return store, {'conflict': True, 'your_base': base_version,
                       'current_version': cur['version'],
                       'changed_by': cur.get('edited_by'),
                       'their_values': {k: cur.get(k) for k in changes}}
    if cur.get('status') == 'Baseline':
        return store, {'blocked': True,
                       'reason': 'объект базирован: изменение через процедуру с основанием'}
    new = {**cur, **changes, 'version': cur['version'] + 1, 'edited_by': author}
    out = [new if o['id'] == oid else o for o in store]
    return out, {'ok': True, 'version': new['version']}

def cancel(store, oid, author):
    """Удаление — смена статуса. Объект остаётся: на него могут ссылаться."""
    cur = next((o for o in store if o['id'] == oid), None)
    if cur is None:
        raise ValueError(f'объект {oid} не найден')
    new = {**cur, 'status': 'Cancelled', 'version': cur['version'] + 1, 'edited_by': author}
    return [new if o['id'] == oid else o for o in store], new

def can_promote(obj, rules):
    """Черновик допускает неполноту; перевод в Baseline — нет."""
    issues = [i for fn in rules.values() for i in fn(obj)]
    return (not issues), issues

def history_of(versions, oid):
    return [v for v in versions if v['id'] == oid]

def undo(versions, oid):
    h = history_of(versions, oid)
    if len(h) < 2:
        return None
    return h[-2]

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    RULES = {
        'формулировка': lambda o: [] if len(o.get('statement', '')) > 10
                                  else ['формулировка пуста или слишком коротка'],
        'условие': lambda o: [] if (o.get('mop') or {}).get('operator')
                             else ['не задан оператор условия'],
        'владелец': lambda o: [] if o.get('owner') else ['не указан владелец'],
    }

    print("Создание")
    store, obj = create([], {'statement': 'Система должна обеспечивать доставку.'},
                        author='инженер А', next_id='RQ-0001')
    check("объект получает идентификатор", obj['id'] == 'RQ-0001')
    check("объект создаётся черновиком", obj['status'] == 'Draft')
    check("происхождение — ручной ввод", obj['provenance']['source'] == 'manual')
    check("автор зафиксирован", obj['provenance']['author'] == 'инженер А')
    check("версия начинается с единицы", obj['version'] == 1)
    try:
        create(store, {'id': 'RQ-0001'}, 'инженер Б', 'RQ-0002')
        check("повторный идентификатор отклонён", False)
    except ValueError:
        check("повторный идентификатор отклонён", True)

    print("\nИзменение и одновременная работа")
    store, res = update(store, 'RQ-0001', {'owner': 'инженер А'}, base_version=1, author='инженер А')
    check("правка на актуальной версии принята", res.get('ok'))
    check("версия увеличена", res['version'] == 2)
    store2, conflict = update(store, 'RQ-0001', {'owner': 'инженер Б'},
                              base_version=1, author='инженер Б')
    check("правка на устаревшей версии отклонена", conflict.get('conflict'))
    check("чужая правка не затёрта",
          next(o for o in store2 if o['id'] == 'RQ-0001')['owner'] == 'инженер А')
    check("показано, кто изменил", conflict['changed_by'] == 'инженер А')
    check("показано чужое значение", conflict['their_values']['owner'] == 'инженер А')
    check("названа актуальная версия", conflict['current_version'] == 2)
    store3, res3 = update(store, 'RQ-0001', {'owner': 'инженер Б'},
                          base_version=2, author='инженер Б')
    check("повтор с актуальной версией проходит", res3.get('ok'))
    check("последний автор зафиксирован",
          next(o for o in store3 if o['id'] == 'RQ-0001')['edited_by'] == 'инженер Б')

    print("\nБазирование и правила")
    draft = {'id': 'RQ-0002', 'statement': 'Система должна', 'status': 'Draft'}
    okp, issues = can_promote(draft, RULES)
    check("неполный черновик существует, но не базируется", not okp and len(issues) >= 2, issues)
    full = {'id': 'RQ-0003', 'statement': 'Сухая масса КА не должна превышать 100 кг.',
            'mop': {'operator': 'le'}, 'owner': 'инженер А', 'status': 'Draft'}
    check("полный объект базируется", can_promote(full, RULES)[0])
    based = [{**full, 'status': 'Baseline', 'version': 3}]
    _, blocked = update(based, 'RQ-0003', {'statement': 'иначе'}, base_version=3, author='инженер А')
    check("правка базированного объекта блокируется", blocked.get('blocked'))
    check("названа причина блокировки", 'процедуру' in blocked['reason'])
    check("правила те же, что для импорта и ИИ", can_promote(full, RULES)[1] == [])

    print("\nУдаление и отмена")
    store4, cancelled = cancel(store3, 'RQ-0001', author='инженер А')
    check("удаление даёт статус Cancelled", cancelled['status'] == 'Cancelled')
    check("объект остаётся в хранилище", any(o['id'] == 'RQ-0001' for o in store4))
    check("версия увеличена при отмене", cancelled['version'] > 3)
    versions = [{'id': 'RQ-0001', 'version': 1, 'owner': None},
                {'id': 'RQ-0001', 'version': 2, 'owner': 'инженер А'},
                {'id': 'RQ-0001', 'version': 3, 'owner': 'инженер Б'}]
    check("история версий доступна", len(history_of(versions, 'RQ-0001')) == 3)
    prev = undo(versions, 'RQ-0001')
    check("предыдущая версия извлекается", prev['version'] == 2 and prev['owner'] == 'инженер А')
    check("для единственной версии отмены нет",
          undo([{'id': 'X', 'version': 1}], 'X') is None)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
