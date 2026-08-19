-- Исправление ограничений V003 (CR-001/ADR-017).
--
-- Дефект: в V003 условия вида `doc->'mop'->>'operator' IN (...)` при ОТСУТСТВУЮЩЕМ
-- операторе дают NULL, а CHECK с результатом NULL в PostgreSQL считается
-- пройденным. Ограничения не срабатывали ровно в том случае, ради которого
-- написаны: «требование с показателем, но без оператора». Проверено запросом:
-- ('{"mop":{"name":"x"}}'::jsonb->'mop'->>'operator') IN ('le',...) → NULL.
--
-- Исправление: приводим отсутствующее значение к пустой строке, тогда условие
-- даёт FALSE и ограничение срабатывает. Набор допустимых операторов не меняется.

ALTER TABLE objects DROP CONSTRAINT IF EXISTS mop_has_operator;
ALTER TABLE objects ADD CONSTRAINT mop_has_operator CHECK (
    type <> 'requirement'
    OR doc->'mop' IS NULL
    OR COALESCE(doc->'mop'->>'operator', '') IN ('eq','le','ge','lt','gt','range','tolerance')
);

ALTER TABLE objects DROP CONSTRAINT IF EXISTS mop_range_complete;
ALTER TABLE objects ADD CONSTRAINT mop_range_complete CHECK (
    type <> 'requirement'
    OR doc->'mop' IS NULL
    OR COALESCE(doc->'mop'->>'operator', '') <> 'range'
    OR doc->'mop'->'upper' IS NOT NULL
);

ALTER TABLE objects DROP CONSTRAINT IF EXISTS mop_tolerance_complete;
ALTER TABLE objects ADD CONSTRAINT mop_tolerance_complete CHECK (
    type <> 'requirement'
    OR doc->'mop' IS NULL
    OR COALESCE(doc->'mop'->>'operator', '') <> 'tolerance'
    OR doc->'mop'->'tolerance' IS NOT NULL
);
