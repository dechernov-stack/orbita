-- Разовый скрипт стенда (21.09): документ проекта переходит на ИМЕНА ИСТИНЫ.
--
-- Владелец увидел в §2 FAD прочерк вместо руководителя. Причина та же, что и
-- в §3: печать ищет поле по имени, а документ проекта несёт `lead` и `phase`,
-- тогда как у вида «проект» в истине схем поля зовутся `manager` и
-- `phase_current`. Код переучен коммитом «печать: руководитель и фаза проекта
-- — именами истины»; здесь переезжают документы, заведённые ДО него.
--
-- Что делается: каждой текущей записи вида «проект» добавляется `manager`
-- (значение прежнего `lead`) и `phase_current` (значение прежнего `phase`),
-- старые ключи снимаются. Правка идёт НОВОЙ ВЕРСИЕЙ: прежняя закрывается
-- `valid_to`, ссылки на запись правку переживают, история видна.
-- Идемпотентно: документ без `lead`/`phase` не трогается.
DO $$
DECLARE
    r      RECORD;
    новый  jsonb;
    счёт   int := 0;
BEGIN
    FOR r IN
        SELECT * FROM orbita_kernel.entity
        WHERE kind = 'project' AND valid_to IS NULL
          AND (doc ? 'lead' OR doc ? 'phase')
    LOOP
        новый := r.doc;
        IF новый ? 'lead' THEN
            новый := (новый - 'lead') || jsonb_build_object('manager', новый->'lead');
        END IF;
        IF новый ? 'phase' THEN
            новый := (новый - 'phase') || jsonb_build_object('phase_current', новый->'phase');
        END IF;
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = r.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc,
             prov_channel, prov_author, prov_source)
        VALUES
            (r.id, r.code, r.kind, r.area, r.born_in, r.status, r.version + 1, новый,
             'manual', 'переезд имён 21.09', 'ops/oneoff/2026-09-21-project-manager-phase.sql');
        счёт := счёт + 1;
    END LOOP;
    RAISE NOTICE 'проектов переведено на имена истины: %', счёт;
END $$;
