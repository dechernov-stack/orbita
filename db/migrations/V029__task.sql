-- МВП-П1 (процесс к точке): задание = адресованный разрыв. Лёгкий объект
-- без статусной модели — статус вычисляется закрытием разрыва.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'task';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS)-[0-9]{4}$');
