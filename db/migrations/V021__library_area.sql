-- Область библиотеки (СТРУКТУРА-БИБЛИОТЕКИ §4): переиспользуемое между
-- проектами живёт в области LIB — не проект, а полка; ссылки проект →
-- библиотека законны.
ALTER TABLE objects DROP CONSTRAINT IF EXISTS objects_project_shape;
ALTER TABLE objects ADD CONSTRAINT objects_project_shape
  CHECK (project_id ~ '^PJ-[0-9]{4}$' OR project_id = 'LIB') NOT VALID;
ALTER TABLE objects VALIDATE CONSTRAINT objects_project_shape;
