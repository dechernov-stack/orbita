#!/usr/bin/env python3
"""Целостность трассировки ТЗ (TZ-COM-002, TZ-REQ-003).

Проверяет:
  1) все ссылки TZ-*/ADR-* разрешаются;
  2) граф симметричен: TRACE-DOWN — строгий обратный к TRACE-UP.
TRACE-UP — источник истины; TRACE-DOWN генерируется (tools/regen_trace.py).
"""
import re, glob, sys, os
from collections import defaultdict

up, down, defined = defaultdict(set), defaultdict(set), set()
for f in sorted(glob.glob('docs/tz/*.md')):
    txt = open(f, encoding='utf-8').read()
    for blk in re.split(r'^### ', txt, flags=re.M)[1:]:
        m = re.match(r'(TZ-[A-Z]+-\d{3})', blk)
        if not m: continue
        rid = m.group(1); defined.add(rid)
        for kind, store in (('TRACE-UP', up), ('TRACE-DOWN', down)):
            mm = re.search(rf'^{kind}:\s*(.+)$', blk, re.M)
            if mm: store[rid] |= set(re.findall(r'TZ-[A-Z]+-\d{3}', mm.group(1)))

adrs = {os.path.basename(p)[:7] for p in glob.glob('docs/adr/ADR-*.md')}
adr_refs = set()
for f in glob.glob('docs/tz/*.md'):
    adr_refs |= set(re.findall(r'ADR-\d{3}', open(f, encoding='utf-8').read()))

errs = []
for rid, s in list(up.items()) + list(down.items()):
    for r in s:
        if r not in defined: errs.append(f"{rid}: ссылка на несуществующее {r}")
for r in adr_refs - adrs:
    errs.append(f"ссылка на отсутствующий {r}")
for a, t in down.items():
    for b in t:
        if a not in up.get(b, set()): errs.append(f"асимметрия: {a}→{b} без обратной")
for a, t in up.items():
    for b in t:
        if a not in down.get(b, set()): errs.append(f"асимметрия: {b}→{a} без прямой")

print(f"требований: {len(defined)}, ADR: {len(adrs)}, ошибок: {len(errs)}")
for e in errs[:20]: print("  ", e)
sys.exit(1 if errs else 0)
