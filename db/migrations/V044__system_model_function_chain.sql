-- V044: модели системы и функциональные цепочки (ADR-050). Виды объектов
-- system_model (SM-NNNN — запись модели: вопрос, входы из параметров узлов,
-- выходы с датой; линки и потоки частями по интерфейсам) и function_chain
-- (FC-NNNN — упорядоченный набор функций сценария). Данных не меняет.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'system_model';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'function_chain';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW|RC|QD|FN|ME|AR|SM|FC)-[0-9]{4}$');
