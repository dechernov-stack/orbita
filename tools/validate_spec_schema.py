#!/usr/bin/env python3
"""Сверка исполняемых эталонов с нормативными схемами.

Причина: дважды эталон расходился со схемой — поле `comparison`, которого в схеме
не было (CR-001), и значение `geometry`, отсутствовавшее в enum (шаг 3). Оба раза
расхождение прошло все проверки, потому что проверять было нечем.

Проверяется: каждый строковый литерал, который эталон сравнивает с полем модели,
присутствует в соответствующем enum схемы.
"""
import json, glob, re, sys

# Сверяются только ОДНОЗНАЧНЫЕ имена полей — те, что означают одно и то же во всей
# модели. Поля вроде kind, status, method, level, maturity употребляются в разных
# смыслах (вид связи и вид события, статус объекта и статус проверки), сверка по имени
# дала бы ложные срабатывания. Охват намеренно узкий: он покрывает тот класс ошибок,
# на котором проект уже обжигался дважды.
BINDINGS = [
    ('limiting_factor', 'contracts/service-zone', ['properties','boundary','properties','limiting_factor','enum']),
    ('operator',        'core/requirement',       ['properties','mop','properties','operator','enum']),
    ('rollup',          'core/requirement',       ['properties','mop','properties','rollup','enum']),
    ('derivation_kind', 'core/requirement',       None),   # проверяется по списку ниже
    ('consumer_class',  'core/service',           ['properties','qos_profiles','items','properties','consumer_class','enum']),
    ('product_kind',    'core/validation',        ['properties','product_kind','enum']),
    ('delivery_mode',   'core/scenario',          ['properties','delivery_mode','enum']),
]
# значения, заданные миграцией, а не схемой
DB_ENUMS = {'derivation_kind': {'allocated','derived'}}

def dig(doc, path):
    for k in path:
        doc = doc[k] if isinstance(doc, dict) else doc[int(k)]
    return doc

schemas = {}
for f in glob.glob('schemas/**/*.json', recursive=True):
    key = f.replace('schemas/','').replace('.schema.json','')
    schemas[key] = json.load(open(f, encoding='utf-8'))

specs = {f: open(f, encoding='utf-8').read() for f in sorted(glob.glob('spec/*.py'))}
errs, checked = [], 0

for field, schema_key, path in BINDINGS:
    if path is None:
        allowed = DB_ENUMS[field]
    else:
        if schema_key not in schemas:
            errs.append(f"схема {schema_key} не найдена"); continue
        try:
            allowed = set(dig(schemas[schema_key], path))
        except (KeyError, IndexError, TypeError):
            errs.append(f"путь к enum не найден: {schema_key} → {'.'.join(path)}"); continue
    # (а) функция эталона, названная как поле схемы, порождает значения этого поля.
    # Именно этот случай и упал на шаге 3: limiting_factor возвращала 'geometry',
    # которого не было в enum.
    for fname, src in specs.items():
        mdef = re.search(rf"^def {field}\(", src, re.M)
        if mdef:
            body = src[mdef.start():]
            nxt = re.search(r"^(def |# ---|print\()", body[1:], re.M)
            if nxt: body = body[:nxt.start() + 1]
            for lit in re.findall(r"return\s+['\"]([^'\"]+)['\"]", body):
                checked += 1
                if lit not in allowed:
                    errs.append(f"{fname}: функция {field}() возвращает '{lit}', "
                                f"отсутствующее в {schema_key} (допустимо: {sorted(allowed)})")
            for lit in re.findall(r"return\s+['\"]([^'\"]+)['\"]\s+if", body):
                pass
    # (б) литералы, которые эталон сравнивает с этим полем.
    # Строки с пометкой `# negative` пропускаются: там намеренно недопустимые
    # значения для проверки отклонения.
    pat = re.compile(rf"""\[['"]{field}['"]\]\s*==\s*['"]([^'"]+)['"]"""
                     rf"""|\.get\(['"]{field}['"]\)\s*==\s*['"]([^'"]+)['"]"""
                     rf"""|['"]{field}['"]\s*:\s*['"]([^'"]+)['"]""")
    for fname, src in specs.items():
        for mm in pat.finditer(src):
            line = src[src.rfind('\n', 0, mm.start()) + 1: src.find('\n', mm.start())]
            if '# negative' in line:
                continue
            lit = next(g for g in mm.groups() if g)
            checked += 1
            if lit not in allowed:
                errs.append(f"{fname}: значение '{lit}' поля '{field}' отсутствует в "
                            f"{schema_key} (допустимо: {sorted(allowed)})")

print(f"сверено литералов: {checked}, расхождений: {len(errs)}")
for e in errs: print("  ", e)
sys.exit(1 if errs else 0)
