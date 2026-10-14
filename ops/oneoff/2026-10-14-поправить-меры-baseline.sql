-- Разовый скрипт стенда (подготовка ПМИ-5, аудит мер RE-0001…12 от 14.10):
-- правки СЫРОГО поля measure у четырёх Baseline-требований PJ-ПМИ7 по решению
-- владельца (ответ «давай» 14.10). Нормализованный mop не тронут.
--   RE-0003: мера {100 %} противоречила тексту «P95 не более 180 мин»
--            → {"op":"<=","unit":"мин","value":180} (как у mop);
--   RE-0009: мусор «<= 0.0000010 млн $» (= 1 $) при тексте без числа → снята;
--   RE-0001: в тексте «100 % объектов», мера пуста → {"op":"=","unit":"%","value":100};
--   RE-0005: в тексте «99,9 %», мера пуста → {"op":">=","unit":"%","value":99.9}.
-- Дамп затронутых строк снят ДО запуска: /root/backups/2026-10-14-меры-baseline.tsv.
-- Идемпотентно: у each требования проверяется текущая мера перед сменой версии.
DO $$
DECLARE
    т RECORD;
    новый_doc jsonb;
BEGIN
    -- RE-0003: процентная мера → минуты
    SELECT * INTO т FROM orbita_kernel.entity
     WHERE kind = 'requirement' AND code = 'RE-0003' AND area = 'project:PJ-ПМИ7' AND valid_to IS NULL;
    IF т.pk IS NULL THEN RAISE NOTICE 'RE-0003 не найдено';
    ELSIF т.doc->'measure' = '{"unit":"%","value":100}'::jsonb THEN
        новый_doc := jsonb_set(т.doc, '{measure}', '{"op":"<=","unit":"мин","value":180}');
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = т.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc, prov_channel, prov_author, prov_source)
        VALUES
            (т.id, т.code, т.kind, т.area, т.born_in, т.status, т.version + 1, новый_doc,
             'manual', 'поправка меры Baseline: {100 %} → ≤ 180 мин (аудит 14.10, ответ владельца)',
             'ops/oneoff/2026-10-14-поправить-меры-baseline.sql');
        RAISE NOTICE 'RE-0003: мера исправлена';
    ELSE RAISE NOTICE 'RE-0003: мера не та, что в аудите — пропуск'; END IF;

    -- RE-0009: мусорная мера снята
    SELECT * INTO т FROM orbita_kernel.entity
     WHERE kind = 'requirement' AND code = 'RE-0009' AND area = 'project:PJ-ПМИ7' AND valid_to IS NULL;
    IF т.pk IS NULL THEN RAISE NOTICE 'RE-0009 не найдено';
    ELSIF т.doc ? 'measure' THEN
        новый_doc := т.doc - 'measure';
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = т.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc, prov_channel, prov_author, prov_source)
        VALUES
            (т.id, т.code, т.kind, т.area, т.born_in, т.status, т.version + 1, новый_doc,
             'manual', 'поправка меры Baseline: мусор ≤ 1e-6 млн $ снят (аудит 14.10, ответ владельца)',
             'ops/oneoff/2026-10-14-поправить-меры-baseline.sql');
        RAISE NOTICE 'RE-0009: мера снята';
    ELSE RAISE NOTICE 'RE-0009: меры уже нет — пропуск'; END IF;

    -- RE-0001: пустая мера → 100 %
    SELECT * INTO т FROM orbita_kernel.entity
     WHERE kind = 'requirement' AND code = 'RE-0001' AND area = 'project:PJ-ПМИ7' AND valid_to IS NULL;
    IF т.pk IS NULL THEN RAISE NOTICE 'RE-0001 не найдено';
    ELSIF NOT (т.doc ? 'measure') THEN
        новый_doc := jsonb_set(т.doc, '{measure}', '{"op":"=","unit":"%","value":100}');
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = т.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc, prov_channel, prov_author, prov_source)
        VALUES
            (т.id, т.code, т.kind, т.area, т.born_in, т.status, т.version + 1, новый_doc,
             'manual', 'поправка меры Baseline: добавлена {= 100 %} (аудит 14.10, ответ владельца)',
             'ops/oneoff/2026-10-14-поправить-меры-baseline.sql');
        RAISE NOTICE 'RE-0001: мера добавлена';
    ELSE RAISE NOTICE 'RE-0001: мера уже есть — пропуск'; END IF;

    -- RE-0005: пустая мера → ≥ 99,9 %
    SELECT * INTO т FROM orbita_kernel.entity
     WHERE kind = 'requirement' AND code = 'RE-0005' AND area = 'project:PJ-ПМИ7' AND valid_to IS NULL;
    IF т.pk IS NULL THEN RAISE NOTICE 'RE-0005 не найдено';
    ELSIF NOT (т.doc ? 'measure') THEN
        новый_doc := jsonb_set(т.doc, '{measure}', '{"op":">=","unit":"%","value":99.9}');
        UPDATE orbita_kernel.entity SET valid_to = now() WHERE pk = т.pk;
        INSERT INTO orbita_kernel.entity
            (id, code, kind, area, born_in, status, version, doc, prov_channel, prov_author, prov_source)
        VALUES
            (т.id, т.code, т.kind, т.area, т.born_in, т.status, т.version + 1, новый_doc,
             'manual', 'поправка меры Baseline: добавлена {≥ 99,9 %} (аудит 14.10, ответ владельца)',
             'ops/oneoff/2026-10-14-поправить-меры-baseline.sql');
        RAISE NOTICE 'RE-0005: мера добавлена';
    ELSE RAISE NOTICE 'RE-0005: мера уже есть — пропуск'; END IF;
END $$;
