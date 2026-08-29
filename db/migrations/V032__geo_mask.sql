-- Географическая область карты спроса (Д2, ответ владельца): акцепт урожая
-- создаёт область-заготовку — имя и происхождение есть, геометрии может не
-- быть; ненарисованная граница становится разрывом готовности, не выдумкой.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'geo_mask';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM)-[0-9]{4}$');
