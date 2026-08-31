-- Словарь линта формулировок (NASA SEH App. C) — данными полки: список
-- неопределённых слов растёт от прогона к прогону, и вносить их инженер
-- обязан с экрана, а не ждать пересборки ядра.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'quality_dictionary';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW|RC|QD)-[0-9]{4}$');
