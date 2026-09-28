-- Песочница проекта (ADR-072): служебная личность (ORBITA_SERVICE_KEY, ADR-071)
-- не проходит ворота и не удаляет РАБОЧИЙ проект — только человек. Признак —
-- поле sandbox сущности проекта (истина СХЕМЫ-ПОЛЕЙ-V2.yaml). Права ключа не
-- сужаются; ограничение — в модели проекта и на сервере ворот.
--
-- v2-проект — версионируемая JSON-сущность в orbita_kernel.entity, реляционной
-- таблицы проектов нет. Поэтому «NOT NULL + неизменяемость» держит триггер на
-- этой таблице (бэкстоп на уровне БД, как forbid_baseline_update в V001):
-- сервер и так делает sandbox create-only, триггер — страховка, чтобы рабочий
-- проект нельзя было «перевести в песочницу» и пройти ворота.

-- Все существующие проекты — рабочие (sandbox=false). Бэкфилл идёт ДО триггера
-- и по всем версиям (INSERT-триггер сверяет новую версию с последней прежней).
UPDATE orbita_kernel.entity
   SET doc = jsonb_set(doc, '{sandbox}', 'false'::jsonb)
 WHERE kind = 'project' AND NOT (doc ? 'sandbox');

CREATE OR REPLACE FUNCTION orbita_kernel.guard_project_sandbox() RETURNS trigger AS $$
DECLARE prev text;
BEGIN
    IF NEW.kind <> 'project' THEN
        RETURN NEW;
    END IF;
    -- NOT NULL по построению: признак без значения — рабочий проект (false).
    -- Так NULL «не проходит мимо проверки» (ADR-072) — читается как рабочий, и
    -- служебной личности его ворота закрыты; а проверка гейта видит поле всегда.
    IF NEW.doc->>'sandbox' IS NULL THEN
        NEW.doc = jsonb_set(NEW.doc, '{sandbox}', 'false'::jsonb);
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- Правка дока на месте (у ядра это только закрытие версии — sandbox не
        -- меняется); смена sandbox запрещена.
        IF NEW.doc->>'sandbox' IS DISTINCT FROM OLD.doc->>'sandbox' THEN
            RAISE EXCEPTION 'orbita: project.sandbox неизменяем, area=% (% -> %)',
                NEW.area, OLD.doc->>'sandbox', NEW.doc->>'sandbox';
        END IF;
        RETURN NEW;
    END IF;
    -- INSERT новой версии: sandbox обязан совпасть с последней прежней версией.
    SELECT doc->>'sandbox' INTO prev
      FROM orbita_kernel.entity
     WHERE area = NEW.area AND code = NEW.code AND kind = 'project'
     ORDER BY version DESC
     LIMIT 1;
    IF prev IS NOT NULL AND prev IS DISTINCT FROM (NEW.doc->>'sandbox') THEN
        RAISE EXCEPTION 'orbita: project.sandbox неизменяем, area=% (% -> %)',
            NEW.area, prev, NEW.doc->>'sandbox';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER guard_project_sandbox
    BEFORE INSERT OR UPDATE ON orbita_kernel.entity
    FOR EACH ROW EXECUTE FUNCTION orbita_kernel.guard_project_sandbox();
