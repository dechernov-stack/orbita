-- Чек-лист обзора (NASA SEH App. C, поставка владельца): инспекция людей.
-- Исключение из закона «всё вычисляется»: однозначность формулировки
-- проверяется чтением вслух, и машине это не поручить. Отметка пункта
-- хранится в паспорте проекта (review_checks) — рядом с tailoring, тем же
-- законом «решение человека несёт автора и время».
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'review_checklist';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK|PW|RC)-[0-9]{4}$');
