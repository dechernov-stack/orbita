-- CR-005: входы моделирования становятся хранимыми объектами.
-- Основание: ADR-021. Дефект: сценарий ссылался на конфигурацию группировки,
-- модель аппарата, карту спроса, набор станций и адаптер протокола, но ни один
-- из них модель хранить не умела. Следствие: input_versions ссылался на версии
-- несуществующих объектов, и воспроизводимость (TZ-COM-006) не обеспечивалась.
--
-- COALESCE во всех сравнениях с полями документа, подзапросов нет,
-- сравнение типа через type::text.

ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'constellation';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'spacecraft';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'demand_map';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'terminal_profile';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'ground_stations';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'protocol_adapter';

ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA)-[0-9]{4}$');

-- Сценарий обязан ссылаться на объекты, а не на произвольные строки.
ALTER TABLE objects ADD CONSTRAINT scenario_refs_typed CHECK (
    type::text <> 'scenario'
    OR (COALESCE(doc->>'constellation_ref',    '') ~ '^CN-[0-9]{4}$'
    AND COALESCE(doc->>'spacecraft_ref',       '') ~ '^SP-[0-9]{4}$'
    AND COALESCE(doc->>'demand_map_ref',       '') ~ '^DM-[0-9]{4}$'
    AND COALESCE(doc->>'ground_stations_ref',  '') ~ '^GS-[0-9]{4}$'
    AND COALESCE(doc->>'protocol_adapter_ref', '') ~ '^PA-[0-9]{4}$')
);

-- Версии всех входов сценария фиксируются: без этого результат невоспроизводим.
ALTER TABLE objects ADD CONSTRAINT scenario_input_versions CHECK (
    type::text <> 'scenario'
    OR (doc->'input_versions' IS NOT NULL
        AND jsonb_path_exists(doc, '$.input_versions.*'))
);

-- ССО требует LTAN (TZ-BAL-003): без него прецессия не определена.
ALTER TABLE objects ADD CONSTRAINT constellation_sso_ltan CHECK (
    type::text <> 'constellation'
    OR COALESCE((doc->'walker'->>'sso')::boolean, false) IS NOT TRUE
    OR doc->'walker'->'ltan_h' IS NOT NULL
);
