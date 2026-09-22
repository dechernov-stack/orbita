-- Разовый скрипт стенда (З-27, план доработок 22.09): снять решение MCR,
-- записанное ПРОБОЙ Code 20.09, — проход обязан дойти до MCR сам.
--
-- Дамп затронутых строк снят ДО запуска: /opt/orbita/backups/2026-09-22-DEC-MCR-1.tsv.
-- Что делается, с историей: запись решения закрывается (valid_to), точка MCR
-- получает НОВУЮ версию без решения и даты решения, статус draft; фаза
-- проекта не трогается (план: «фазу проекта не трогать»). Идемпотентно.
DO $$
DECLARE
    т RECORD;
BEGIN
    UPDATE orbita_kernel.entity SET valid_to = now()
     WHERE id = 'decision-408166b9' AND area = 'project:PJ-ПМИ7' AND valid_to IS NULL;
    SELECT * INTO т FROM orbita_kernel.entity
     WHERE kind = 'gate' AND code = 'MCR' AND area = 'project:PJ-ПМИ7' AND valid_to IS NULL;
    IF т.status = 'passed' OR (т.doc ? 'decision') THEN
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = т.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc, prov_channel, prov_author, prov_source)
        VALUES
            (т.id, т.code, т.kind, т.area, т.born_in, 'draft', т.version + 1,
             т.doc - 'decision' - 'decided_at', 'manual', 'снятие DEC-MCR-1 (З-27)',
             'ops/oneoff/2026-09-22-снять-DEC-MCR-1.sql');
        RAISE NOTICE 'MCR возвращена в draft, решение снято';
    ELSE
        RAISE NOTICE 'снимать нечего: MCR уже без решения';
    END IF;
END $$;
