-- Разовый скрипт стенда (шип 1, §0): каталог процессов перезалит ЗАПИСЯМИ
-- (SE-01…SE-17, одна на процесс); прежняя запись PRC-9001 — весь каталог
-- одной записью со списками — снимается с учёта новой версией cancelled.
-- Дамп затронутой строки снят ДО запуска: /root/backups/2026-09-22-PRC-9001.tsv.
-- Идемпотентно: снятое второй раз не трогается.
DO $$
DECLARE
    т RECORD;
BEGIN
    SELECT * INTO т FROM orbita_kernel.entity
     WHERE kind = 'process_catalog' AND code = 'PRC-9001' AND area = 'library' AND valid_to IS NULL;
    IF т.pk IS NULL THEN
        RAISE NOTICE 'PRC-9001 на полке нет — снимать нечего';
    ELSIF т.status = 'cancelled' THEN
        RAISE NOTICE 'PRC-9001 уже снята с учёта';
    ELSE
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = т.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc, prov_channel, prov_author, prov_source)
        VALUES
            (т.id, т.code, т.kind, т.area, т.born_in, 'cancelled', т.version + 1, т.doc,
             'manual', 'каталог процессов перезалит записями (шип 1, §0)',
             'ops/oneoff/2026-09-22-закрыть-PRC-9001.sql');
        RAISE NOTICE 'PRC-9001 снята с учёта: каталог живёт записями SE-01…SE-17';
    END IF;
END $$;
