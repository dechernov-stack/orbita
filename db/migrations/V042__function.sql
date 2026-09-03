-- V042: функция как слой (ADR-047, шип 1b). Новый вид объекта function
-- (FN-NNNN): что система делает — из нужд, сервисов и ConOps; распределяется
-- на узлы состава и интерфейсы. Данных миграция не меняет.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'function';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW|RC|QD|FN)-[0-9]{4}$');
