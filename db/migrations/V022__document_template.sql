-- Нитка Б.1: шаблон документа переезжает из enum кода в библиотечную область.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'document_template';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT)-[0-9]{4}$');
