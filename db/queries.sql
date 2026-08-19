-- Ключевые запросы. Проверены на исполняемом прототипе tests/storage_semantics.py.

-- 1. ПРЕДКИ объекта (обход трассировки вверх до нужд). TZ-COM-002, TZ-REQ-003.
--    Направление хранится один раз; обратный обход вычисляется.
WITH RECURSIVE up AS (
    SELECT from_id, to_id, 1 AS depth FROM links WHERE to_id = :id AND kind = 'trace'
    UNION ALL
    SELECT l.from_id, l.to_id, up.depth + 1
      FROM links l JOIN up ON l.to_id = up.from_id
     WHERE l.kind = 'trace' AND up.depth < 32          -- защита от циклов
)
SELECT DISTINCT from_id AS ancestor, min(depth) AS depth FROM up GROUP BY from_id ORDER BY depth;

-- 2. ПОТОМКИ объекта (вниз до свидетельств).
WITH RECURSIVE down AS (
    SELECT from_id, to_id, 1 AS depth FROM links WHERE from_id = :id AND kind = 'trace'
    UNION ALL
    SELECT l.from_id, l.to_id, down.depth + 1
      FROM links l JOIN down ON l.from_id = down.to_id
     WHERE l.kind = 'trace' AND down.depth < 32
)
SELECT DISTINCT to_id AS descendant, min(depth) AS depth FROM down GROUP BY to_id ORDER BY depth;

-- 3. РАЗРЫВЫ ТРАССИРОВКИ: требование без источника. TZ-REQ-003.
SELECT o.id FROM objects o
 WHERE o.valid_to IS NULL AND o.type = 'requirement' AND o.status <> 'Cancelled'
   AND NOT EXISTS (SELECT 1 FROM links l WHERE l.to_id = o.id AND l.kind = 'trace');

-- 4. НЕРАСПРЕДЕЛЁННЫЕ системные требования. TZ-REQ-005.
SELECT o.id FROM objects o
 WHERE o.valid_to IS NULL AND o.type = 'requirement' AND o.doc->>'level' = 'system'
   AND NOT EXISTS (SELECT 1 FROM links l WHERE l.from_id = o.id AND l.kind = 'allocation');

-- 5. СРЕЗ МОДЕЛИ НА ДАТУ (отчёт о зрелости на произвольный момент). TZ-OUT-003.
SELECT id, type, status, version FROM objects
 WHERE valid_from <= :at AND (valid_to IS NULL OR valid_to > :at);

-- 6. КАСКАД STALE: результаты, зависящие от изменённого параметра. TZ-MOD-005.
WITH RECURSIVE affected AS (
    SELECT object_id, name FROM param_deps WHERE dep_object_id = :obj AND dep_name = :param
    UNION
    SELECT d.object_id, d.name FROM param_deps d
      JOIN affected a ON d.dep_object_id = a.object_id AND d.dep_name = a.name
)
UPDATE results SET stale = true
 WHERE NOT stale AND (input_versions ? :obj
    OR EXISTS (SELECT 1 FROM affected a WHERE input_versions ? a.object_id));

-- 7. ЦИКЛ В ФОРМУЛАХ: проверка перед вставкой зависимости. TZ-MOD-005.
WITH RECURSIVE reach AS (
    SELECT dep_object_id AS o, dep_name AS n FROM param_deps
     WHERE object_id = :to_obj AND name = :to_name
    UNION
    SELECT d.dep_object_id, d.dep_name FROM param_deps d
      JOIN reach r ON d.object_id = r.o AND d.name = r.n
)
SELECT EXISTS (SELECT 1 FROM reach WHERE o = :from_obj AND n = :from_name) AS creates_cycle;

-- 8. НЕАКЦЕПТОВАННЫЕ ПРЕДЛОЖЕНИЯ ИИ. TZ-AI-004, TZ-OUT-002.
SELECT object_id, name, provenance->'ai'->>'prompt_package_id' AS package
  FROM params
 WHERE provenance->>'source' = 'ai_proposed'
   AND (provenance->'ai'->>'accepted')::boolean IS NOT TRUE;

-- 9. ЗРЕЛОСТЬ ПАКЕТА К КОНТРОЛЬНОЙ ТОЧКЕ. TZ-OUT-003.
SELECT o.id, o.type, o.status AS actual, g.required
  FROM objects o JOIN gate_requirements g ON g.object_type = o.type AND g.gate = :gate
 WHERE o.valid_to IS NULL AND o.status <> 'Cancelled'
   AND array_position(ARRAY['Draft','Preliminary','Approved','Baseline']::text[], o.status::text)
     < array_position(ARRAY['Draft','Preliminary','Approved','Baseline']::text[], g.required);
