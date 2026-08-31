-- «Работа фазы»: задача фазы — операция регламента, развёрнутая в ведомый
-- процесс. Контент (зачем, шаги, подсказки) живёт данными полки, а не в
-- коде экрана; статусы и окна вычисляются и не хранятся.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'phase_task';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW)-[0-9]{4}$');
