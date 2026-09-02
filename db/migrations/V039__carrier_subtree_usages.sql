-- V039: вхождения поддерева КА (ADR-044, дополнение к V038).
--
-- V038 разложила модель аппарата в узлы-определения (платформа · ПН ·
-- подсистемы) под узлом КА, но вхождений им не завела — на стенде дерево
-- вхождений показывало под КА прежние ручные узлы, а узлы из модели жили
-- только как определения. Здесь каждому такому узлу без вхождения заводится
-- вхождение под вхождением КА, не привязанным к построению (вхождение
-- проекта ×1): кратность подсистемы — из её параметра quantity (pcs), иначе 1.
-- Идемпотентно: узел, у которого вхождение под этим родителем уже есть,
-- пропускается. Проверяется схемой миграции на чистой базе и тестом.
--
-- legacy: узлы, заведённые руками до V038 (без profile), не трогаются —
--   их вхождения уже есть, слияние с узлами из модели решает владелец.

DO $$
DECLARE
    ka      RECORD;
    ka_use  text;
    node    RECORD;
    parent_use text;
    cu_max  int;
    qty     int;
BEGIN
    SELECT COALESCE(MAX(substring(id from 4)::int), 0) INTO cu_max FROM objects WHERE id ~ '^CU-[0-9]{4}$';

    FOR ka IN SELECT * FROM objects
               WHERE type::text = 'component' AND valid_to IS NULL AND status::text <> 'Cancelled'
                 AND doc->'profile'->>'role' = 'spacecraft' ORDER BY id LOOP
        -- вхождение КА, не привязанное к построению: под ним живёт поддерево
        SELECT u.id INTO ka_use FROM objects u
         WHERE u.valid_to IS NULL AND u.type::text = 'component_usage' AND u.status::text <> 'Cancelled'
           AND u.doc->>'definition_ref' = ka.id AND (u.doc->'constellation_ref') IS NULL
         ORDER BY u.id LIMIT 1;
        IF ka_use IS NULL THEN CONTINUE; END IF;

        -- обход поддерева определений в ширину: родители раньше детей
        FOR node IN WITH RECURSIVE sub AS (
                        SELECT o.id, o.doc, 1 AS depth FROM objects o
                         WHERE o.valid_to IS NULL AND o.type::text = 'component' AND o.status::text <> 'Cancelled'
                           AND o.doc->>'parent' = ka.id AND (o.doc->'profile') IS NOT NULL
                        UNION ALL
                        SELECT o.id, o.doc, sub.depth + 1 FROM objects o JOIN sub ON o.doc->>'parent' = sub.id
                         WHERE o.valid_to IS NULL AND o.type::text = 'component' AND o.status::text <> 'Cancelled'
                    )
                    SELECT id, doc, depth FROM sub ORDER BY depth, id LOOP
            -- вхождение родителя: вхождение КА либо уже заведённое вхождение узла-родителя
            IF node.doc->>'parent' = ka.id THEN
                parent_use := ka_use;
            ELSE
                SELECT u.id INTO parent_use FROM objects u
                 WHERE u.valid_to IS NULL AND u.type::text = 'component_usage' AND u.status::text <> 'Cancelled'
                   AND u.doc->>'definition_ref' = node.doc->>'parent'
                   AND u.doc->>'parent_usage' IS NOT NULL
                 ORDER BY u.id LIMIT 1;
                IF parent_use IS NULL THEN CONTINUE; END IF;
            END IF;
            -- уже есть вхождение этого узла под этим родителем — пропуск
            IF EXISTS (SELECT 1 FROM objects u
                        WHERE u.valid_to IS NULL AND u.type::text = 'component_usage' AND u.status::text <> 'Cancelled'
                          AND u.doc->>'definition_ref' = node.id AND u.doc->>'parent_usage' = parent_use) THEN
                CONTINUE;
            END IF;
            qty := COALESCE((SELECT (p->'quantity'->>'value')::numeric::int
                               FROM jsonb_array_elements(COALESCE(node.doc->'parameters', '[]'::jsonb)) p
                              WHERE p->>'name' = 'quantity' AND p->'quantity'->>'unit' = 'pcs' LIMIT 1), 1);
            cu_max := cu_max + 1;
            INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
            VALUES ('CU-' || lpad(cu_max::text, 4, '0'), 'component_usage', '1', 'Draft', jsonb_build_object(
                        'id', 'CU-' || lpad(cu_max::text, 4, '0'),
                        'definition_ref', node.id, 'parent_usage', parent_use, 'quantity', GREATEST(qty, 1),
                        'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'),
                        'provenance', jsonb_build_object('source', 'imported', 'import', jsonb_build_object(
                            'dataset', 'миграция V039: вхождения поддерева КА', 'dataset_version', 'V039',
                            'retrieved_at', to_char(now(), 'YYYY-MM-DD')))),
                    now(), 'V039', 'migration:V039', ka.project_id);
        END LOOP;
    END LOOP;
END $$;
