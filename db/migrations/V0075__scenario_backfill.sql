-- Перенос данных перед констрейнтами V008 (снятие ограничения 4 из docs/STATUS.md).
--
-- V008 объявляет: сценарий обязан нести пять типизированных ссылок и версии
-- всех входов. На базе, где сценарии уже есть, констрейнт падал без переноса.
-- Имя V0075 выбрано из-за строковой сортировки мигратора: файл исполняется
-- до V008 на отстающих базах и после V006 на свежих (там он no-op).
--
-- Правила переноса:
--  1. Отсутствующие input_versions ДОПОЛНЯЮТСЯ текущими версиями объектов,
--     на которые сценарий ссылается, — то же правило, каким сервер фиксирует
--     версии при создании (Шаг 17). Явно записанные версии не перетираются.
--     Дополняются все строки, включая исторические версии: V008 проверяет
--     всю таблицу.
--  2. Сценарий без пяти ссылок автоматикой не чинится: изобретать ссылки —
--     значит объявить невоспроизводимый результат воспроизводимым. Такие
--     сценарии перечисляются ПОИМЁННО с инструкцией, и миграция
--     останавливается — громкая ошибка со списком вместо загадочного
--     нарушения констрейнта.

DO $$
DECLARE
    bad text;
BEGIN
    -- шаг 1: дополнить input_versions по каждой ссылке сценария
    UPDATE objects s
       SET doc = jsonb_set(
               s.doc,
               '{input_versions}',
               COALESCE(s.doc->'input_versions', '{}'::jsonb) || (
                   SELECT COALESCE(jsonb_object_agg(r.ref, r.version), '{}'::jsonb)
                     FROM (
                          SELECT refs.ref,
                                 (SELECT o.doc->'lifecycle'->>'version'
                                    FROM objects o
                                   WHERE o.id = refs.ref AND o.valid_to IS NULL) AS version
                            FROM (VALUES
                                     (s.doc->>'constellation_ref'),
                                     (s.doc->>'spacecraft_ref'),
                                     (s.doc->>'demand_map_ref'),
                                     (s.doc->>'ground_stations_ref'),
                                     (s.doc->>'protocol_adapter_ref')
                                 ) AS refs(ref)
                           WHERE refs.ref IS NOT NULL
                             AND NOT (COALESCE(s.doc->'input_versions', '{}'::jsonb) ? refs.ref)
                          ) r
                    WHERE r.version IS NOT NULL
               )
           )
     WHERE s.type::text = 'scenario';

    -- шаг 2: непереносимое называется поимённо — сценарий без пяти ссылок
    -- либо со ссылкой на вход, которого в базе нет (версию зафиксировать
    -- не с чего — воспроизводимость не обеспечить)
    SELECT string_agg(DISTINCT s.id, ', ' ORDER BY s.id) INTO bad
      FROM objects s
     WHERE s.type::text = 'scenario'
       AND (NOT (COALESCE(s.doc->>'constellation_ref',    '') ~ '^CN-[0-9]{4}$'
             AND COALESCE(s.doc->>'spacecraft_ref',       '') ~ '^SP-[0-9]{4}$'
             AND COALESCE(s.doc->>'demand_map_ref',       '') ~ '^DM-[0-9]{4}$'
             AND COALESCE(s.doc->>'ground_stations_ref',  '') ~ '^GS-[0-9]{4}$'
             AND COALESCE(s.doc->>'protocol_adapter_ref', '') ~ '^PA-[0-9]{4}$')
            OR NOT (s.doc->'input_versions' ?& ARRAY[
                     s.doc->>'constellation_ref', s.doc->>'spacecraft_ref',
                     s.doc->>'demand_map_ref', s.doc->>'ground_stations_ref',
                     s.doc->>'protocol_adapter_ref']));

    IF bad IS NOT NULL THEN
        RAISE EXCEPTION USING MESSAGE =
            'V0075: сценарии без пяти типизированных ссылок: ' || bad ||
            '. Автоматика ссылки не изобретает: дополните ссылки этих сценариев ' ||
            'либо удалите сценарии, затем повторите обновление (V008 требует ' ||
            'воспроизводимости — ссылки вместе с версиями входов).';
    END IF;
END $$;
