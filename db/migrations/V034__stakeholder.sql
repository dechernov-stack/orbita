-- Ф-13: стейкхолдер — СУЩНОСТЬ ПРОЕКТА, а не только профиль на полке.
-- Владелец: нужды неявно связаны со стейкхолдерами, а их в системе нет как
-- объектов; круг шире потребителей — регуляторы, операторы, учреждаемые
-- организации. Профиль (SH, полка А2) остаётся шаблоном класса миссии;
-- стейкхолдер (SK) — факт этого проекта.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'stakeholder';
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
    CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD|NR|MC|SH|TR|LF|DT|ST|CU|VW|TS|UR|GL|GM|PF|SK)-[0-9]{4}$');
