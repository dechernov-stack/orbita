#!/usr/bin/env python3
"""Исполняемый эталон семантики хранилища (SQLite-прототип DDL из db/schema.sql).

Назначение: зафиксировать поведение, которое трудно задать прозой, и проверить его
до реализации на Kotlin/PostgreSQL. Проверяются требования:
  TZ-COM-002/TZ-REQ-003  двунаправленный обход трассировки
  TZ-COM-003             базирование и интервальная версионность
  TZ-MOD-004             единица и происхождение обязательны
  TZ-MOD-005             граф зависимостей, циклы, каскад stale
  TZ-MOD-007             стабильность ID, Cancelled сохраняется
  TZ-AI-004              неакцептованное предложение не влияет на расчёты
"""
import sqlite3, json, sys

def db():
    c = sqlite3.connect(':memory:')
    c.executescript("""
    PRAGMA foreign_keys=ON;
    CREATE TABLE objects(
      pk INTEGER PRIMARY KEY, id TEXT NOT NULL, type TEXT NOT NULL, version TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'Draft', doc TEXT NOT NULL,
      valid_from TEXT NOT NULL, valid_to TEXT, supersedes INTEGER, change_ref TEXT,
      CHECK (id GLOB '[NSRC][DVQMC]-[0-9][0-9][0-9][0-9]'),
      CHECK (valid_to IS NULL OR valid_to > valid_from));
    CREATE UNIQUE INDEX objects_current ON objects(id) WHERE valid_to IS NULL;
    CREATE TABLE links(from_id TEXT, to_id TEXT, kind TEXT DEFAULT 'trace',
      PRIMARY KEY(from_id,to_id,kind), CHECK(from_id <> to_id));
    CREATE TABLE params(
      object_id TEXT, name TEXT, value REAL, unit TEXT NOT NULL, provenance TEXT NOT NULL,
      formula TEXT, PRIMARY KEY(object_id,name),
      CHECK(length(trim(unit))>0),
      CHECK(json_extract(provenance,'$.source') IS NOT NULL),
      CHECK(value IS NOT NULL OR formula IS NOT NULL),
      CHECK(json_extract(provenance,'$.source') <> 'ai_proposed'
            OR json_extract(provenance,'$.ai.accepted') IS NOT NULL));
    CREATE TABLE param_deps(object_id TEXT, name TEXT, dep_object_id TEXT, dep_name TEXT,
      PRIMARY KEY(object_id,name,dep_object_id,dep_name),
      FOREIGN KEY(object_id,name) REFERENCES params(object_id,name) ON DELETE CASCADE);
    CREATE TABLE results(pk INTEGER PRIMARY KEY, scenario_id TEXT, kind TEXT,
      payload TEXT, input_versions TEXT NOT NULL, rng_seed INTEGER NOT NULL,
      stale INTEGER NOT NULL DEFAULT 0);
    """)
    return c

def add_obj(c, oid, typ, status='Draft', ver='1', doc=None, t='2026-01-01'):
    c.execute("INSERT INTO objects(id,type,version,status,doc,valid_from) VALUES(?,?,?,?,?,?)",
              (oid, typ, ver, status, json.dumps(doc or {}), t))

def ancestors(c, oid):
    return [r[0] for r in c.execute("""
      WITH RECURSIVE up(f,t,d) AS (
        SELECT from_id,to_id,1 FROM links WHERE to_id=? AND kind='trace'
        UNION ALL SELECT l.from_id,l.to_id,up.d+1 FROM links l JOIN up ON l.to_id=up.f
         WHERE l.kind='trace' AND up.d<32)
      SELECT DISTINCT f FROM up ORDER BY f""", (oid,))]

def descendants(c, oid):
    return [r[0] for r in c.execute("""
      WITH RECURSIVE dn(f,t,d) AS (
        SELECT from_id,to_id,1 FROM links WHERE from_id=? AND kind='trace'
        UNION ALL SELECT l.from_id,l.to_id,dn.d+1 FROM links l JOIN dn ON l.from_id=dn.t
         WHERE l.kind='trace' AND dn.d<32)
      SELECT DISTINCT t FROM dn ORDER BY t""", (oid,))]

def creates_cycle(c, fo, fn, to, tn):
    r = c.execute("""
      WITH RECURSIVE reach(o,n) AS (
        SELECT dep_object_id,dep_name FROM param_deps WHERE object_id=? AND name=?
        UNION SELECT d.dep_object_id,d.dep_name FROM param_deps d
              JOIN reach r ON d.object_id=r.o AND d.name=r.n)
      SELECT EXISTS(SELECT 1 FROM reach WHERE o=? AND n=?)""", (to,tn,fo,fn)).fetchone()[0]
    return bool(r)

def baseline_update(c, oid, new_doc, change_ref=None, t='2026-02-01'):
    """Изменение Baseline-объекта: только закрытием интервала с указанием основания."""
    row = c.execute("SELECT pk,status,version FROM objects WHERE id=? AND valid_to IS NULL",(oid,)).fetchone()
    pk, status, ver = row
    if status == 'Baseline' and not change_ref:
        raise ValueError(f"TZ-COM-003: изменение Baseline-объекта {oid} требует основания")
    c.execute("UPDATE objects SET valid_to=? WHERE pk=?", (t, pk))
    c.execute("""INSERT INTO objects(id,type,version,status,doc,valid_from,supersedes,change_ref)
                 SELECT id,type,?,?,?,?,?,? FROM objects WHERE pk=?""",
              (str(int(ver)+1), 'Draft', json.dumps(new_doc), t, pk, change_ref, pk))

# ---------------- проверки ----------------
ok, fail = 0, 0
def check(name, cond, detail=''):
    global ok, fail
    if cond: ok += 1; print(f"  ✓ {name}")
    else:    fail += 1; print(f"  ✗ {name} {detail}")

print("TZ-COM-002 / TZ-REQ-003: двунаправленный обход")
c = db()
for oid, t in [('ND-0001','need'),('SV-0001','service'),('RQ-0001','requirement'),
               ('RQ-0002','requirement'),('CM-0001','component')]:
    add_obj(c, oid, t)
c.executemany("INSERT INTO links VALUES(?,?,'trace')",
              [('ND-0001','SV-0001'),('SV-0001','RQ-0001'),('RQ-0001','RQ-0002'),('RQ-0002','CM-0001')])
check("предки RQ-0002 до нужды", ancestors(c,'RQ-0002')==['ND-0001','RQ-0001','SV-0001'], ancestors(c,'RQ-0002'))
check("потомки ND-0001 до элемента", descendants(c,'ND-0001')==['CM-0001','RQ-0001','RQ-0002','SV-0001'])
check("связь хранится один раз",
      c.execute("SELECT count(*) FROM links").fetchone()[0]==4)

print("\nTZ-COM-003 / TZ-MOD-007: базирование и версионность")
c = db(); add_obj(c,'RQ-0100','requirement',status='Baseline',doc={'statement':'v1'})
try:
    baseline_update(c,'RQ-0100',{'statement':'v2'}); check("изменение Baseline без основания отклонено", False)
except ValueError: check("изменение Baseline без основания отклонено", True)
baseline_update(c,'RQ-0100',{'statement':'v2'},change_ref='CR-001')
cur = c.execute("SELECT version,status,json_extract(doc,'$.statement') FROM objects WHERE id='RQ-0100' AND valid_to IS NULL").fetchone()
old = c.execute("SELECT version,json_extract(doc,'$.statement') FROM objects WHERE id='RQ-0100' AND valid_to IS NOT NULL").fetchone()
check("новая версия текущая", cur==('2','Draft','v2'), cur)
check("предыдущая версия доступна", old==('1','v1'), old)
check("текущая версия ровно одна",
      c.execute("SELECT count(*) FROM objects WHERE id='RQ-0100' AND valid_to IS NULL").fetchone()[0]==1)
try:
    add_obj(c,'RQ-0100','requirement'); check("повторный ID отклонён", False)
except sqlite3.IntegrityError: check("повторный ID отклонён", True)
add_obj(c,'RQ-0101','requirement',status='Cancelled')
check("Cancelled сохраняется и доступен",
      c.execute("SELECT status FROM objects WHERE id='RQ-0101'").fetchone()[0]=='Cancelled')
check("срез на дату отдаёт версию 1",
      c.execute("""SELECT version FROM objects WHERE id='RQ-0100'
                   AND valid_from<='2026-01-15' AND (valid_to IS NULL OR valid_to>'2026-01-15')""").fetchone()[0]=='1')

print("\nTZ-MOD-004 / TZ-AI-004: единицы, происхождение, акцепт")
c = db()
def try_param(**kw):
    try:
        c.execute("INSERT INTO params(object_id,name,value,unit,provenance,formula) VALUES(?,?,?,?,?,?)",
                  (kw['o'],kw['n'],kw.get('v'),kw.get('u',''),json.dumps(kw.get('p',{})),kw.get('f')))
        return True
    except sqlite3.IntegrityError: return False
check("значение без единицы отклонено", not try_param(o='CM-0001',n='mass',v=50,u='',p={'source':'manual'}))
check("значение без происхождения отклонено", not try_param(o='CM-0001',n='mass2',v=50,u='kg',p={}))
check("предложение ИИ без признака акцепта отклонено",
      not try_param(o='CM-0001',n='mass3',v=50,u='kg',p={'source':'ai_proposed','ai':{}}))
check("корректный параметр принят", try_param(o='CM-0001',n='mass',v=50,u='kg',p={'source':'manual'}))
try_param(o='CM-0001',n='power',v=12,u='W',p={'source':'ai_proposed','ai':{'accepted':False,'prompt_package_id':'PP-1'}})
unaccepted = c.execute("""SELECT count(*) FROM params WHERE json_extract(provenance,'$.source')='ai_proposed'
                          AND json_extract(provenance,'$.ai.accepted')=0""").fetchone()[0]
check("неакцептованные предложения выявляются отчётом", unaccepted==1)

print("\nСемантика ограничений БД: NULL проходит CHECK")
# Ограничение считается выполненным при результате TRUE ИЛИ NULL. Сравнение
# с отсутствующим полем JSON даёт NULL — и запись проходит. Дефект найден
# на исполнении в V003 (оператор условия) и повторно в V005 (цель валидации).
c = sqlite3.connect(':memory:')
c.executescript("""
CREATE TABLE naive(id TEXT, typ TEXT, doc TEXT,
  CHECK (typ <> 'requirement' OR json_extract(doc,'$.operator') IN ('le','ge')));
CREATE TABLE fixed(id TEXT, typ TEXT, doc TEXT,
  CHECK (typ <> 'requirement' OR COALESCE(json_extract(doc,'$.operator'),'') IN ('le','ge')));
""")
def ins(tbl, doc):
    try:
        c.execute(f"INSERT INTO {tbl} VALUES('x','requirement',?)", (doc,)); return True
    except sqlite3.IntegrityError:
        return False
check("наивное ограничение пропускает запись без поля", ins('naive', '{}'))
check("наивное ограничение ловит недопустимое значение", not ins('naive', '{"operator":"xx"}'))  # negative
check("COALESCE закрывает дыру: запись без поля отклонена", not ins('fixed', '{}'))
check("COALESCE не мешает допустимому значению", ins('fixed', '{"operator":"le"}'))

print("\nTZ-MOD-005: зависимости, циклы, каскад stale")
c = db()
for n,f in [('a',None),('b','a*2'),('cc','b+1')]:
    c.execute("INSERT INTO params VALUES(?,?,?,?,?,?)",('CM-0001',n,1.0,'kg',json.dumps({'source':'manual'}),f))
c.executemany("INSERT INTO param_deps VALUES(?,?,?,?)",
              [('CM-0001','b','CM-0001','a'),('CM-0001','cc','CM-0001','b')])
# цепочка: cc → b → a. Добавление «a зависит от cc» замыкает цикл; «cc зависит от a» — нет.
check("цикл выявлен: a зависит от cc", creates_cycle(c,'CM-0001','a','CM-0001','cc'))
check("не цикл: cc зависит от a (избыточно, но допустимо)", not creates_cycle(c,'CM-0001','cc','CM-0001','a'))
c.execute("INSERT INTO results VALUES(1,'SC-0001','kpi-vector','{}',?,42,0)",
          (json.dumps({'CM-0001':'1'}),))
c.execute("UPDATE results SET stale=1 WHERE stale=0 AND json_extract(input_versions,'$.\"CM-0001\"') IS NOT NULL")
check("результат помечен stale при изменении входа",
      c.execute("SELECT stale FROM results WHERE pk=1").fetchone()[0]==1)
check("зерно ГПСЧ сохранено с результатом",
      c.execute("SELECT rng_seed FROM results WHERE pk=1").fetchone()[0]==42)

print(f"\nИтог: пройдено {ok}, провалено {fail}")
sys.exit(1 if fail else 0)
