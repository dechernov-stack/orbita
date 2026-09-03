-- V045: слои Arcadia (ADR-052). Виды объектов capability (OC-NNNN —
-- операционная способность слоя OA: что система умеет для стейкхолдера) и
-- logical_component (LC-NNNN — группировка функций слоя LA, развёрнутая на
-- узлы дерева состава). Второго дерева носителей не заводится (ADR-044):
-- PA остаётся деревом состава и интерфейсами. Данных не меняет.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'capability';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'logical_component';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW|RC|QD|FN|ME|AR|SM|FC|OC|LC)-[0-9]{4}$');
