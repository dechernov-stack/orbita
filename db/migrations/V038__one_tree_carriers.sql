-- V038: одно дерево носителей (ADR-044). Модель аппарата (объект spacecraft,
-- SP-NNNN) растворяется в дереве состава: узел «КА» (component,
-- profile.role = spacecraft) с поддеревом «Платформа» · «Полезная нагрузка» ·
-- подсистемы ведомости масс. Величины ложатся в parameters узлов (имена —
-- ключи анкет Ф-06, единицы — справочника), структурные не-величины (пресет,
-- режимы, каналы, политика буфера, зрелость) — в profile. Сценарии ссылаются
-- на ВХОЖДЕНИЕ КА (carrier_ref → CU-NNNN); подгруппа построения = вхождение
-- КА ×N, где N — число аппаратов подгруппы.
--
-- Ничего не теряется:
-- legacy: объект spacecraft получает новую версию в статусе Cancelled с
--   ключом dissolved_into (куда ушёл) — история читается как прежде,
--   новых объектов этого вида сервер не принимает (Boundary, ADR-044).
-- legacy: kind = system у узла с segment = 'space' — прежняя запись «КА как
--   система космического сегмента». ЕДИНСТВЕННЫЙ такой узел проекта без
--   профиля усыновляется как узел КА (kind → element, profile.role =
--   spacecraft): его вхождения и распределённые на него требования остаются
--   на месте. Иначе узел КА создаётся новым; его родитель — единственный
--   сегмент с segment = 'space', если такой один, иначе корень.
-- legacy: текущие сценарии получают новую версию по процедуре изменения
--   (TZ-COM-003): старая закрывается с основанием change_ref = 'V038', новая
--   наследует статус (базированный остаётся базированным) и несёт carrier_ref.
-- legacy: исторические версии сценариев хранят spacecraft_ref как было —
--   констрейнт новой формы проверяет только текущие строки (valid_to IS NULL).
-- legacy: сценарий, чья ссылка ведёт на несуществующую модель аппарата,
--   автоматикой не чинится — миграция останавливается со списком (как V0075).

ALTER TABLE objects DROP CONSTRAINT IF EXISTS scenario_refs_typed;

CREATE FUNCTION pg_temp.v038_bump(v text) RETURNS text LANGUAGE sql AS $f$
    SELECT CASE WHEN v ~ '\.'
                THEN regexp_replace(v, '[0-9]+$', '') || ((regexp_replace(v, '^.*\.', ''))::int + 1)::text
                ELSE (v::int + 1)::text END
$f$;

-- параметр узла из числа контракта: пустое или не-число — пустой список
CREATE FUNCTION pg_temp.v038_param(pname text, pvalue jsonb, punit text) RETURNS jsonb LANGUAGE sql AS $f$
    SELECT CASE WHEN pvalue IS NULL OR jsonb_typeof(pvalue) <> 'number' THEN '[]'::jsonb
                ELSE jsonb_build_array(jsonb_build_object(
                         'name', pname,
                         'quantity', jsonb_build_object('value', pvalue, 'unit', punit,
                             'provenance', jsonb_build_object('source', 'imported', 'author', 'migration:V038'))))
           END
$f$;

DO $$
DECLARE
    sp        RECORD;
    ka_row    RECORD;
    cn        RECORD;
    sc        RECORD;
    item      jsonb;
    mel_pl    jsonb;
    ka_doc    jsonb;
    prof      jsonb;
    params    jsonb;
    cm_max    int;
    cu_max    int;
    n_adopt   int;
    n_seg     int;
    ka_id     text;
    pf_id     text;
    pl_id     text;
    seg_id    text;
    seg_usage text;
    cu_id     text;
    fallback  text;
    newver    text;
    bad       text;
    ts        timestamptz := now();
    prov      jsonb := jsonb_build_object(
                  'source', 'imported',
                  'import', jsonb_build_object(
                      'dataset', 'миграция V038: модель аппарата растворена в дереве состава',
                      'dataset_version', 'V038',
                      'retrieved_at', to_char(now(), 'YYYY-MM-DD')));
BEGIN
    SELECT COALESCE(MAX(substring(id from 4)::int), 0) INTO cm_max FROM objects WHERE id ~ '^CM-[0-9]{4}$';
    SELECT COALESCE(MAX(substring(id from 4)::int), 0) INTO cu_max FROM objects WHERE id ~ '^CU-[0-9]{4}$';

    FOR sp IN SELECT * FROM objects
               WHERE type::text = 'spacecraft' AND valid_to IS NULL AND status::text <> 'Cancelled'
               ORDER BY id LOOP

        -- 1. узел КА: усыновление единственного «системного» узла космического сегмента
        SELECT count(*) INTO n_adopt FROM objects o
         WHERE o.valid_to IS NULL AND o.project_id = sp.project_id AND o.type::text = 'component'
           AND o.status::text <> 'Cancelled' AND o.doc->>'kind' = 'system'
           AND o.doc->>'segment' = 'space' AND (o.doc->'profile') IS NULL;
        prof := jsonb_strip_nulls(jsonb_build_object('role', 'spacecraft', 'preset', sp.doc->'preset', 'modes', sp.doc->'modes'));
        IF n_adopt = 1 THEN
            SELECT * INTO ka_row FROM objects o
             WHERE o.valid_to IS NULL AND o.project_id = sp.project_id AND o.type::text = 'component'
               AND o.status::text <> 'Cancelled' AND o.doc->>'kind' = 'system'
               AND o.doc->>'segment' = 'space' AND (o.doc->'profile') IS NULL;
            ka_id := ka_row.id;
            newver := pg_temp.v038_bump(ka_row.version);
            ka_doc := ka_row.doc || jsonb_build_object('kind', 'element', 'profile', prof);
            ka_doc := jsonb_set(ka_doc, '{lifecycle,version}', to_jsonb(newver));
            UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, 'V038') WHERE pk = ka_row.pk;
            INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
            VALUES (ka_id, 'component', newver, ka_row.status, ka_doc, ts, ka_row.pk, 'V038', 'migration:V038', sp.project_id);
        ELSE
            SELECT count(*) INTO n_seg FROM objects o
             WHERE o.valid_to IS NULL AND o.project_id = sp.project_id AND o.type::text = 'component'
               AND o.status::text <> 'Cancelled' AND o.doc->>'kind' = 'segment' AND o.doc->>'segment' = 'space';
            seg_id := NULL;
            IF n_seg = 1 THEN
                SELECT o.id INTO seg_id FROM objects o
                 WHERE o.valid_to IS NULL AND o.project_id = sp.project_id AND o.type::text = 'component'
                   AND o.status::text <> 'Cancelled' AND o.doc->>'kind' = 'segment' AND o.doc->>'segment' = 'space';
            END IF;
            cm_max := cm_max + 1;
            ka_id := 'CM-' || lpad(cm_max::text, 4, '0');
            ka_doc := jsonb_strip_nulls(jsonb_build_object(
                'id', ka_id, 'name', 'Космический аппарат (' || sp.id || ')', 'kind', 'element',
                'parent', seg_id, 'profile', prof,
                'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov));
            INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
            VALUES (ka_id, 'component', '1', 'Draft', ka_doc, ts, 'V038', 'migration:V038', sp.project_id);
        END IF;

        -- 2. платформа: величины — параметрами по анкете PF-9001, остальное — профилем
        cm_max := cm_max + 1;
        pf_id := 'CM-' || lpad(cm_max::text, 4, '0');
        params := pg_temp.v038_param('dry_mass', sp.doc->'platform'->'dry_mass_kg', 'kg')
               || pg_temp.v038_param('wet_mass', sp.doc->'platform'->'wet_mass_kg', 'kg')
               || pg_temp.v038_param('design_life', sp.doc->'platform'->'design_life_years', 'a')
               || pg_temp.v038_param('reliability_at_eol', sp.doc->'platform'->'reliability_at_eol', '1')
               || pg_temp.v038_param('sa_area', sp.doc->'platform'->'power'->'sa_area_m2', 'm2')
               || pg_temp.v038_param('sa_efficiency', sp.doc->'platform'->'power'->'sa_efficiency', '1')
               || pg_temp.v038_param('sa_degradation_pct_per_year', sp.doc->'platform'->'power'->'sa_degradation_pct_per_year', '%')
               || pg_temp.v038_param('battery_energy', sp.doc->'platform'->'power'->'battery_wh', 'Wh')
               || pg_temp.v038_param('battery_max_dod', sp.doc->'platform'->'power'->'battery_max_dod', '1')
               || pg_temp.v038_param('attitude_accuracy', sp.doc->'platform'->'attitude'->'pointing_accuracy_deg', 'deg');
        prof := jsonb_strip_nulls(jsonb_build_object(
            'role', 'platform',
            'mel_margin_policy', sp.doc->'platform'->'mel_margin_policy',
            'power', CASE WHEN (sp.doc->'platform'->'power'->'sa_mounting') IS NOT NULL
                          THEN jsonb_build_object('sa_mounting', sp.doc->'platform'->'power'->'sa_mounting') END,
            'attitude', CASE WHEN (sp.doc->'platform'->'attitude'->'modes') IS NOT NULL
                             THEN jsonb_build_object('modes', sp.doc->'platform'->'attitude'->'modes') END,
            'propulsion', sp.doc->'platform'->'propulsion'));
        INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
        VALUES (pf_id, 'component', '1', 'Draft', jsonb_build_object(
                    'id', pf_id, 'name', 'Платформа', 'kind', 'subsystem', 'parent', ka_id,
                    'profile', prof, 'parameters', params,
                    'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov),
                ts, 'V038', 'migration:V038', sp.project_id);

        -- 3. подсистемы ведомости масс — компонентами под платформой; строка ПН — сам узел ПН
        mel_pl := NULL;
        FOR item IN SELECT value FROM jsonb_array_elements(COALESCE(sp.doc->'platform'->'mel', '[]'::jsonb)) LOOP
            IF item->>'subsystem' = 'payload' THEN
                mel_pl := item;
                CONTINUE;
            END IF;
            cm_max := cm_max + 1;
            INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
            VALUES ('CM-' || lpad(cm_max::text, 4, '0'), 'component', '1', 'Draft', jsonb_build_object(
                        'id', 'CM-' || lpad(cm_max::text, 4, '0'),
                        'name', COALESCE(item->>'name', 'подсистема'), 'kind', 'component', 'parent', pf_id,
                        'profile', jsonb_strip_nulls(jsonb_build_object('role', 'subsystem',
                            'subsystem', item->'subsystem', 'maturity', item->'maturity')),
                        'parameters', pg_temp.v038_param('mass', item->'mass_kg', 'kg')
                                   || pg_temp.v038_param('quantity', item->'quantity', 'pcs'),
                        'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov),
                    ts, 'V038', 'migration:V038', sp.project_id);
        END LOOP;

        -- 4. полезная нагрузка: каналы, политика буфера и маяк — профилем; масса и буфер — параметрами
        cm_max := cm_max + 1;
        pl_id := 'CM-' || lpad(cm_max::text, 4, '0');
        INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
        VALUES (pl_id, 'component', '1', 'Draft', jsonb_build_object(
                    'id', pl_id, 'name', COALESCE(mel_pl->>'name', 'Полезная нагрузка'), 'kind', 'subsystem', 'parent', ka_id,
                    'profile', jsonb_strip_nulls(jsonb_build_object(
                        'role', 'payload', 'maturity', mel_pl->'maturity',
                        'architecture', sp.doc->'payload'->'architecture',
                        'links', sp.doc->'payload'->'links',
                        'onboard', (sp.doc->'payload'->'onboard') - 'buffer_mb',
                        'ephemeris_beacon', sp.doc->'payload'->'ephemeris_beacon')),
                    'parameters', pg_temp.v038_param('mass', mel_pl->'mass_kg', 'kg')
                               || pg_temp.v038_param('buffer_size', sp.doc->'payload'->'onboard'->'buffer_mb', 'MB'),
                    'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov),
                ts, 'V038', 'migration:V038', sp.project_id);

        -- 5. вхождения КА по построениям: подгруппа = вхождение ×N
        seg_usage := NULL;
        SELECT u.id INTO seg_usage FROM objects u
         WHERE u.valid_to IS NULL AND u.project_id = sp.project_id AND u.type::text = 'component_usage'
           AND u.status::text <> 'Cancelled'
           AND u.doc->>'definition_ref' = (SELECT doc->>'parent' FROM objects WHERE id = ka_id AND valid_to IS NULL)
         ORDER BY u.id LIMIT 1;
        FOR cn IN SELECT * FROM objects
                   WHERE type::text = 'constellation' AND valid_to IS NULL AND status::text <> 'Cancelled'
                     AND project_id = sp.project_id ORDER BY id LOOP
            IF jsonb_typeof(cn.doc->'subgroups') = 'array' AND jsonb_array_length(cn.doc->'subgroups') > 0 THEN
                FOR item IN SELECT value FROM jsonb_array_elements(cn.doc->'subgroups') LOOP
                    cu_max := cu_max + 1;
                    INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
                    VALUES ('CU-' || lpad(cu_max::text, 4, '0'), 'component_usage', '1', 'Draft', jsonb_strip_nulls(jsonb_build_object(
                                'id', 'CU-' || lpad(cu_max::text, 4, '0'), 'definition_ref', ka_id, 'parent_usage', seg_usage,
                                'quantity', COALESCE((item->>'planes')::int, 1) * COALESCE((item->>'per_plane')::int, 1),
                                'constellation_ref', cn.id, 'subgroup', COALESCE(item->>'name', 'подгруппа'),
                                'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov)),
                            ts, 'V038', 'migration:V038', sp.project_id);
                END LOOP;
            ELSIF (cn.doc->'walker') IS NOT NULL THEN
                cu_max := cu_max + 1;
                INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
                VALUES ('CU-' || lpad(cu_max::text, 4, '0'), 'component_usage', '1', 'Draft', jsonb_strip_nulls(jsonb_build_object(
                            'id', 'CU-' || lpad(cu_max::text, 4, '0'), 'definition_ref', ka_id, 'parent_usage', seg_usage,
                            'quantity', COALESCE((cn.doc->'walker'->>'total')::int, 1),
                            'constellation_ref', cn.id, 'subgroup', 'walker',
                            'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov)),
                        ts, 'V038', 'migration:V038', sp.project_id);
            END IF;
        END LOOP;

        -- вхождение КА в проект без привязки к построению — есть у усыновлённого узла, иначе заводится
        fallback := NULL;
        SELECT u.id INTO fallback FROM objects u
         WHERE u.valid_to IS NULL AND u.type::text = 'component_usage' AND u.status::text <> 'Cancelled'
           AND u.doc->>'definition_ref' = ka_id AND (u.doc->'constellation_ref') IS NULL
         ORDER BY u.id LIMIT 1;
        IF fallback IS NULL THEN
            cu_max := cu_max + 1;
            fallback := 'CU-' || lpad(cu_max::text, 4, '0');
            INSERT INTO objects(id, type, version, status, doc, valid_from, change_ref, created_by, project_id)
            VALUES (fallback, 'component_usage', '1', 'Draft', jsonb_strip_nulls(jsonb_build_object(
                        'id', fallback, 'definition_ref', ka_id, 'parent_usage', seg_usage, 'quantity', 1,
                        'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1'), 'provenance', prov)),
                    ts, 'V038', 'migration:V038', sp.project_id);
        END IF;

        -- 6. текущие сценарии: ссылка на вхождение построения сценария, версии входов — с новым ключом
        FOR sc IN SELECT * FROM objects
                   WHERE type::text = 'scenario' AND valid_to IS NULL AND project_id = sp.project_id
                     AND doc->>'spacecraft_ref' = sp.id LOOP
            cu_id := NULL;
            SELECT u.id INTO cu_id FROM objects u
             WHERE u.valid_to IS NULL AND u.type::text = 'component_usage' AND u.doc->>'definition_ref' = ka_id
               AND u.doc->>'constellation_ref' = sc.doc->>'constellation_ref'
             ORDER BY u.id LIMIT 1;
            IF cu_id IS NULL THEN cu_id := fallback; END IF;
            -- новая версия в том же статусе по процедуре изменения (TZ-COM-003,
            -- ADR-031): базированный сценарий на месте не правится — старая
            -- версия закрывается с основанием, новая наследует статус
            newver := pg_temp.v038_bump(sc.version);
            UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, 'V038') WHERE pk = sc.pk;
            INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
            VALUES (sc.id, 'scenario', newver, sc.status,
                    jsonb_set(
                        jsonb_set((sc.doc - 'spacecraft_ref') || jsonb_build_object('carrier_ref', cu_id),
                                  '{input_versions}',
                                  (COALESCE(sc.doc->'input_versions', '{}'::jsonb) - sp.id) || jsonb_build_object(cu_id, '1')),
                        '{lifecycle,version}', to_jsonb(newver)),
                    ts, sc.pk, 'V038', 'migration:V038', sp.project_id);
        END LOOP;

        -- 7. сам объект spacecraft — в Cancelled новой версией, с адресом, куда растворён
        newver := pg_temp.v038_bump(sp.version);
        UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, 'V038') WHERE pk = sp.pk;
        INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
        VALUES (sp.id, 'spacecraft', newver, 'Cancelled',
                jsonb_set(jsonb_set(sp.doc, '{lifecycle,status}', '"Cancelled"'), '{lifecycle,version}', to_jsonb(newver))
                    || jsonb_build_object('dissolved_into', ka_id),
                ts, sp.pk, 'V038', 'migration:V038', sp.project_id);
    END LOOP;

    -- сценарий без живой модели аппарата чинить нечем — громкий отказ со списком
    SELECT string_agg(id || ' (' || project_id || ', spacecraft_ref=' || COALESCE(doc->>'spacecraft_ref', '—') || ')', '; ' ORDER BY id)
      INTO bad
      FROM objects
     WHERE type::text = 'scenario' AND valid_to IS NULL AND (doc->'carrier_ref') IS NULL;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'V038: сценарии без вхождения КА — модели аппарата по ссылке нет: %. Заведите узел КА и вхождение, укажите carrier_ref, затем повторите миграцию', bad;
    END IF;
END $$;

ALTER TABLE objects ADD CONSTRAINT scenario_refs_typed CHECK (
    type::text <> 'scenario'
    OR valid_to IS NOT NULL
    OR (COALESCE(doc->>'constellation_ref',    '') ~ '^CN-[0-9]{4}$'
    AND COALESCE(doc->>'carrier_ref',          '') ~ '^CU-[0-9]{4}$'
    AND COALESCE(doc->>'demand_map_ref',       '') ~ '^DM-[0-9]{4}$'
    AND COALESCE(doc->>'ground_stations_ref',  '') ~ '^GS-[0-9]{4}$'
    AND COALESCE(doc->>'protocol_adapter_ref', '') ~ '^PA-[0-9]{4}$')
);

DROP FUNCTION pg_temp.v038_param(text, jsonb, text);
DROP FUNCTION pg_temp.v038_bump(text);
