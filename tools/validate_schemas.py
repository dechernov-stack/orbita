#!/usr/bin/env python3
"""Валидация схем и негативных наборов. Запускается в CI (TZ-MOD-001, TZ-MOD-003)."""
import json, glob, sys
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

docs, errs = {}, []
for f in sorted(glob.glob('schemas/**/*.json', recursive=True)):
    d = json.load(open(f, encoding='utf-8'))
    docs[d['$id']] = d
registry = Registry()
for sid, d in docs.items():
    registry = registry.with_resource(sid, Resource.from_contents(d))

for sid, d in docs.items():
    try:
        Draft202012Validator.check_schema(d)
    except Exception as e:
        errs.append(f"невалидная схема {sid}: {e}")

print(f"схем: {len(docs)}, ошибок: {len(errs)}")
for e in errs:
    print(" ", e)
sys.exit(1 if errs else 0)
