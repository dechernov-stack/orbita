-- Анкеты характеристик носителей (Ф-06): библиотека сама запрашивает данные —
-- перечень полей роли живёт на полке, а не в коде формы.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'property_form';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF)-[0-9]{4}$');
