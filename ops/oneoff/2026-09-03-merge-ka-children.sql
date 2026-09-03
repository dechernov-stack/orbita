-- Разовый скрипт стенда (ответ владельца 03.09, п. 1): ручные узлы «Полезная
-- нагрузка IoT» (CM-0003) и «Служебная платформа» (CM-0004), заведённые до
-- V038 без параметров, сливаются в узлы из модели аппарата — CM-0017 (ПН)
-- и CM-0011 (платформа). Канон — узел с параметрами.
--
-- Что делается, с историей (новые версии, старые закрыты с основанием):
--  1. дубли переводятся в Cancelled с ключом merged_into;
--  2. их вхождения CU-0002/CU-0003 отменяются — вхождения узлов из модели
--     (CU-0010/CU-0011) уже стоят под тем же КА (V039);
--  3. интерфейс IF-0002 (Baseline) и технологии TL-0001…TL-0004 (Baseline)
--     переводятся на канонические узлы новой версией по процедуре
--     изменения (change_ref = merge-2026-09-03);
--  4. распределённых требований и строк links на дубли нет (проверено).
-- Идемпотентно: если CM-0003 уже Cancelled — ничего не делает.
DO $$
DECLARE
    r        RECORD;
    newver   text;
    ts       timestamptz := now();
    ref      text := 'merge-2026-09-03';
    canon    jsonb := '{"CM-0003": "CM-0017", "CM-0004": "CM-0011"}'::jsonb;
    newdoc   jsonb;
BEGIN
    IF EXISTS (SELECT 1 FROM objects WHERE id = 'CM-0003' AND valid_to IS NULL AND status::text = 'Cancelled') THEN
        RAISE NOTICE 'слияние уже выполнено';
        RETURN;
    END IF;

    -- 1. дубли → Cancelled с адресом канона
    FOR r IN SELECT * FROM objects WHERE id IN ('CM-0003', 'CM-0004') AND valid_to IS NULL LOOP
        newver := (r.version::int + 1)::text;
        UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, ref) WHERE pk = r.pk;
        INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
        VALUES (r.id, r.type, newver, 'Cancelled',
                jsonb_set(jsonb_set(r.doc, '{lifecycle,status}', '"Cancelled"'), '{lifecycle,version}', to_jsonb(newver))
                    || jsonb_build_object('merged_into', canon->>r.id),
                ts, r.pk, ref, 'merge:2026-09-03', r.project_id);
    END LOOP;

    -- 2. вхождения дублей → Cancelled
    FOR r IN SELECT * FROM objects WHERE type::text = 'component_usage' AND valid_to IS NULL
                                     AND doc->>'definition_ref' IN ('CM-0003', 'CM-0004') LOOP
        newver := (r.version::int + 1)::text;
        UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, ref) WHERE pk = r.pk;
        INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
        VALUES (r.id, r.type, newver, 'Cancelled',
                jsonb_set(jsonb_set(r.doc, '{lifecycle,status}', '"Cancelled"'), '{lifecycle,version}', to_jsonb(newver))
                    || jsonb_build_object('merged_into', canon->>(r.doc->>'definition_ref')),
                ts, r.pk, ref, 'merge:2026-09-03', r.project_id);
    END LOOP;

    -- 3. интерфейсы (owners) и технологии (components) — на канонические узлы
    FOR r IN SELECT * FROM objects WHERE valid_to IS NULL AND status::text <> 'Cancelled'
                                     AND type::text IN ('interface', 'technology')
                                     AND (doc::text LIKE '%CM-0003%' OR doc::text LIKE '%CM-0004%') LOOP
        newdoc := r.doc;
        IF newdoc ? 'owners' THEN
            newdoc := jsonb_set(newdoc, '{owners}', (SELECT jsonb_agg(COALESCE(canon->>x, x)) FROM jsonb_array_elements_text(newdoc->'owners') x));
        END IF;
        IF newdoc ? 'components' THEN
            newdoc := jsonb_set(newdoc, '{components}', (SELECT jsonb_agg(DISTINCT COALESCE(canon->>x, x)) FROM jsonb_array_elements_text(newdoc->'components') x));
        END IF;
        IF newdoc = r.doc THEN CONTINUE; END IF;
        newver := (r.version::int + 1)::text;
        newdoc := jsonb_set(newdoc, '{lifecycle,version}', to_jsonb(newver));
        UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, ref) WHERE pk = r.pk;
        INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
        VALUES (r.id, r.type, newver, r.status, newdoc, ts, r.pk, ref, 'merge:2026-09-03', r.project_id);
    END LOOP;
END $$;
