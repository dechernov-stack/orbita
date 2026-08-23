-- ADR-022: проект — контейнер объектов и связей. project_id обязателен;
-- всё, что лежало в базе до контейнеров, переносится в проект
-- «Орбита-IoT (демо)» (PJ-0001). Ограничение вводится двухфазно
-- (NOT VALID → VALIDATE), чтобы не держать долгих блокировок на занятой базе.

ALTER TABLE objects ADD COLUMN IF NOT EXISTS project_id text;
ALTER TABLE links   ADD COLUMN IF NOT EXISTS project_id text;

-- Перенос: если в базе есть объекты без проекта — им нужен контейнер.
DO $$
DECLARE
  orphan_objects bigint;
BEGIN
  SELECT count(*) INTO orphan_objects FROM objects WHERE project_id IS NULL;
  IF orphan_objects > 0 THEN
    -- Контейнер переноса. Если PJ-0001 уже есть (демо шага 17) — используется он.
    IF NOT EXISTS (SELECT 1 FROM objects WHERE id = 'PJ-0001' AND valid_to IS NULL) THEN
      INSERT INTO objects (id, type, version, status, doc, created_by, project_id)
      VALUES ('PJ-0001', 'project', '1', 'Draft',
              jsonb_build_object(
                'id', 'PJ-0001',
                'name', 'Орбита-IoT (демо)',
                'phase', 'phase_a',
                'milestones', jsonb_build_array(
                  jsonb_build_object('gate', 'SRR'),
                  jsonb_build_object('gate', 'SDR')),
                'lifecycle', jsonb_build_object('status', 'Draft', 'version', '1')),
              'migration:V010', 'PJ-0001');
    END IF;
    UPDATE objects SET project_id = 'PJ-0001' WHERE project_id IS NULL;
    UPDATE links   SET project_id = 'PJ-0001' WHERE project_id IS NULL;
  END IF;
END $$;

-- Объект проекта принадлежит сам себе; чужих строк без проекта не осталось.
ALTER TABLE objects ADD CONSTRAINT objects_project_present
  CHECK (project_id IS NOT NULL) NOT VALID;
ALTER TABLE objects VALIDATE CONSTRAINT objects_project_present;

ALTER TABLE links ADD CONSTRAINT links_project_present
  CHECK (project_id IS NOT NULL) NOT VALID;
ALTER TABLE links VALIDATE CONSTRAINT links_project_present;

-- Идентификатор проекта — всегда PJ-NNNN.
ALTER TABLE objects ADD CONSTRAINT objects_project_shape
  CHECK (project_id ~ '^PJ-[0-9]{4}$') NOT VALID;
ALTER TABLE objects VALIDATE CONSTRAINT objects_project_shape;

ALTER TABLE links ADD CONSTRAINT links_project_shape
  CHECK (project_id ~ '^PJ-[0-9]{4}$') NOT VALID;
ALTER TABLE links VALIDATE CONSTRAINT links_project_shape;

-- Объект вида project принадлежит сам себе (ADR-022: проектов вне проекта нет).
ALTER TABLE objects ADD CONSTRAINT project_owns_itself
  CHECK (type <> 'project' OR project_id = id) NOT VALID;
ALTER TABLE objects VALIDATE CONSTRAINT project_owns_itself;

-- Выборки всегда идут в проекте.
CREATE INDEX IF NOT EXISTS objects_project ON objects(project_id) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS links_project ON links(project_id);
