-- В2.1, данные: вхождение ×1 из каждой parent-связи текущих компонентов.
-- (Отдельно от V024: новые значения enum нельзя использовать в той же
-- транзакции, где они добавлены.)
WITH comp AS (
    SELECT id, project_id, doc->>'parent' AS parent,
           ROW_NUMBER() OVER (ORDER BY id) AS rn
    FROM objects
    WHERE type = 'component' AND valid_to IS NULL AND doc ? 'parent'
), numbered AS (
    SELECT c.*, 'CU-' || LPAD(c.rn::text, 4, '0') AS usage_id FROM comp c
)
INSERT INTO objects (id, type, version, status, doc, project_id, created_by)
SELECT
    n.usage_id,
    'component_usage',
    '1',
    'Draft',
    jsonb_strip_nulls(jsonb_build_object(
        'id', n.usage_id,
        'definition_ref', n.id,
        'quantity', 1,
        'parent_usage', p.usage_id,
        'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'),
        'provenance', jsonb_build_object('source', 'manual',
            'author', 'миграция V025: вхождения ×1 из parent-связей')
    )),
    n.project_id,
    'миграция V025: вхождения ×1 из parent-связей'
FROM numbered n
LEFT JOIN numbered p
    ON p.id = n.parent AND p.project_id = n.project_id;
