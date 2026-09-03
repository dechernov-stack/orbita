-- V043: структуры внешней модели (ADR-048, шип 3). Виды объектов
-- model_element (ME-NNNN — узел-ссылка на элемент Capella, идентичность по
-- UUID) и arch_link (AR-NNNN — связь требования с элементом, вид +
-- обоснование). Данных миграция не меняет; в модель Capella система не
-- пишет — только читает адаптером за флагом.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'model_element';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'arch_link';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW|RC|QD|FN|ME|AR)-[0-9]{4}$');
