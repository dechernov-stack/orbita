-- Шаг 17 (блок C): отсутствующая часть модели — ConOps, технологии, решения,
-- проект, выпуски документов. Модель уже ссылалась на эти виды в пустоту:
-- conops_ref валидации был строкой в никуда, реестр ворот жил без проекта.

ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'conops';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'technology';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'decision';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'project';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'document_issue';

ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI)-[0-9]{4}$');
