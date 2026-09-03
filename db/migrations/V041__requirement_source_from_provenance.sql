-- V041: документ-основание требования из происхождения урожая (ответ
-- владельца 03.09, п. 5). У требований, принятых из разбора документа
-- (provenance.import.dataset = «SD-NNNN «имя»», item_ref = якоря блоков),
-- заполняется source{doc, anchor} первым якорем — там, где якорь есть.
-- Новой версией в том же статусе по процедуре изменения (TZ-COM-003):
-- старая закрывается с основанием, новая наследует статус. source остаётся
-- правимым. Требования без якоря или с уже записанным source не трогаются.
DO $$
DECLARE
    r      RECORD;
    sd     text;
    anchor text;
    newver text;
    ts     timestamptz := now();
BEGIN
    FOR r IN SELECT * FROM objects
              WHERE type::text = 'requirement' AND valid_to IS NULL AND status::text <> 'Cancelled'
                AND (doc->'source') IS NULL
                AND doc->'provenance'->'import'->>'dataset' ~ '^SD-[0-9]{4}'
                AND COALESCE(doc->'provenance'->'import'->>'item_ref', '') <> '' LOOP
        sd := substring(doc->'provenance'->'import'->>'dataset' from '^SD-[0-9]{4}') FROM (SELECT r.doc) t;
        anchor := trim(split_part(r.doc->'provenance'->'import'->>'item_ref', ',', 1));
        IF sd IS NULL OR anchor = '' THEN CONTINUE; END IF;
        newver := CASE WHEN r.version ~ '\.' THEN regexp_replace(r.version, '[0-9]+$', '') || ((regexp_replace(r.version, '^.*\.', ''))::int + 1)::text
                       ELSE (r.version::int + 1)::text END;
        UPDATE objects SET valid_to = ts, change_ref = COALESCE(change_ref, 'V041') WHERE pk = r.pk;
        INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
        VALUES (r.id, 'requirement', newver, r.status,
                jsonb_set(r.doc || jsonb_build_object('source', jsonb_build_object('doc', sd, 'anchor', anchor)),
                          '{lifecycle,version}', to_jsonb(newver)),
                ts, r.pk, 'V041', 'migration:V041', r.project_id);
    END LOOP;
END $$;
