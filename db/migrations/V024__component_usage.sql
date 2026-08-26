-- В2.1: определение и вхождение. Существующие узлы становятся
-- определениями; КАЖДАЯ текущая связь parent порождает вхождение ×1
-- (ловушка 3: только ×1, без фантазии; требования остаются на
-- определениях). Композиция вхождений связывается по тем же parent-цепям.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'component_usage';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU)-[0-9]{4}$');
ALTER TYPE link_kind ADD VALUE IF NOT EXISTS 'evolves';
ALTER TYPE link_kind ADD VALUE IF NOT EXISTS 'uses';
ALTER TYPE link_kind ADD VALUE IF NOT EXISTS 'hosted_on';
ALTER TYPE link_kind ADD VALUE IF NOT EXISTS 'wbs';
