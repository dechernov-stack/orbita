#!/usr/bin/env python3
"""Регенерация TRACE-DOWN из TRACE-UP. TRACE-UP — единственный источник истины;
дублирование связей вручную ведёт к расхождению (см. TZ-COM-002)."""
import re, glob
from collections import defaultdict

FILES = sorted(glob.glob('docs/tz/*.md'))
up_tz, ext = {}, {}
for f in FILES:
    for m in re.finditer(r'^### (TZ-[A-Z]+-\d{3})\..*?(?=^### |\Z)', open(f, encoding='utf-8').read(), re.M | re.S):
        rid, blk = m.group(1), m.group(0)
        u = re.search(r'^TRACE-UP:\s*(.+)$', blk, re.M)
        u = u.group(1).strip() if u else ''
        up_tz[rid] = set(re.findall(r'TZ-[A-Z]+-\d{3}', u))
        ext[rid] = [p.strip() for p in re.split(r'[;,]', u)
                    if p.strip() and not re.fullmatch(r'TZ-[A-Z]+-\d{3}', p.strip())]

down = defaultdict(set)
for rid, parents in up_tz.items():
    for p in parents:
        if p in up_tz: down[p].add(rid)

fmt = lambda xs: '; '.join(xs) if xs else '—'
for f in FILES:
    txt = open(f, encoding='utf-8').read()
    def repl(m):
        rid, blk = m.group(1), m.group(0)
        blk = re.sub(r'^TRACE-UP:.*$', 'TRACE-UP:   ' + fmt(ext[rid] + sorted(up_tz[rid])), blk, count=1, flags=re.M)
        return re.sub(r'^TRACE-DOWN:.*$', 'TRACE-DOWN: ' + fmt(sorted(down[rid])), blk, count=1, flags=re.M)
    open(f, 'w', encoding='utf-8').write(
        re.sub(r'^### (TZ-[A-Z]+-\d{3})\..*?(?=^### |\Z)', repl, txt, flags=re.M | re.S))
print(f"регенерировано: {len(up_tz)}")
