#!/usr/bin/env python3
"""Обход кода клиента: в интерфейсе нет расчётов (STEP-6 §3.2, ловушка 5).

Пересчёт свёртки, нормировки или критерия на стороне клиента создаёт вторую
реализацию правила — и она разойдётся с серверной. Разойдётся молча: обе
покажут число, просто разное.

Проверяется механически, а не на глаз:
  1. над величинами модели в клиенте нет арифметики;
  2. над ними нет сравнений, порождающих вердикт (сервер шлёт готовый признак);
  3. в клиенте нет таблиц соответствия, дублирующих серверные (оператор → знак,
     код единицы → подпись): и то и другое приходит готовым.

Проверка намеренно грубая: она запрещает шаблон, а не пытается понять смысл.
Ложное срабатывание снимается переносом расчёта на сервер, а не правкой списка.
"""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / 'web' / 'src'

# Величины модели: всё, что несёт смысл предметной области, а не разметку.
MODEL_QUANTITIES = [
    'used', 'limit', 'remaining', 'overrunValue', 'fillPercent',
    'value', 'valueMax', 'tolerance', 'eventsDone', 'eventsTotal',
]

# Арифметика над величиной модели: `bar.used / bar.limit`, `used * 100`, …
ARITHMETIC = re.compile(
    r'\b(?:' + '|'.join(MODEL_QUANTITIES) + r')\b\s*[-+*/%]\s*'
    r'|[-+*/%]\s*\w+\.(?:' + '|'.join(MODEL_QUANTITIES) + r')\b'
)

# Вердикт из сравнения величин: `used > limit`, `remaining < 0`, …
VERDICT = re.compile(
    r'\b\w*\.?(?:' + '|'.join(MODEL_QUANTITIES) + r')\b\s*(?:[<>]=?|===|!==)\s*'
)

# Таблицы соответствия, дублирующие серверные: знак оператора и подпись единицы.
DUPLICATE_TABLES = [
    (re.compile(r"['\"](?:ge|le|lt|gt)['\"]\s*:"), 'таблица «оператор → знак»: строку условия рендерит сервер'),
    (re.compile(r"['\"]kg['\"]\s*:"), 'таблица «код единицы → подпись»: подписи приходят из /unit-labels'),
    (re.compile(r'\bMath\.(?:min|max|round|abs|floor|ceil)\b'), 'округление величины модели выполняет сервер'),
]

# Разметочная арифметика: к предметной области не относится и разрешена явно.
LAYOUT_ALLOWED = [
    ('row.depth * 16', 'отступ в пикселях по уровню дерева — разметка, не правило'),
    # Подстановка const-поля схемы (шаг 16): сравнение со значением СХЕМЫ,
    # а не между величинами модели — без него подстановка зациклила бы рендер.
    ('value !== schema.const', 'const-поле: значение принадлежит схеме, не инженеру'),
]


def main() -> int:
    problems: list[str] = []
    files = sorted(SRC.rglob('*.ts')) + sorted(SRC.rglob('*.tsx'))
    if not files:
        print('обход клиента: исходники не найдены', file=sys.stderr)
        return 1

    for path in files:
        for lineno, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            code = line.split('//')[0]
            if any(allowed in code for allowed, _ in LAYOUT_ALLOWED):
                continue
            where = f'{path.relative_to(SRC.parent)}:{lineno}'
            if ARITHMETIC.search(code):
                problems.append(f'{where}: арифметика над величиной модели — {code.strip()}')
            if VERDICT.search(code):
                problems.append(f'{where}: вердикт из сравнения величин — {code.strip()}')
            for pattern, why in DUPLICATE_TABLES:
                if pattern.search(code):
                    problems.append(f'{where}: {why} — {code.strip()}')

    print(f'обход клиента: файлов {len(files)}, расчётов {len(problems)}')
    for p in problems:
        print('  ', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
