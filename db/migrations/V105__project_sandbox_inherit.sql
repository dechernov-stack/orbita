-- Песочница: приведение к false — только на первой версии (ADR-072, замечание
-- владельца 28.09). V104 приводил отсутствующий sandbox к false на КАЖДОМ
-- insert перед проверкой неизменяемости: новая версия песочницы без поля
-- (правка из UI, пересборка документа общим store.update) либо падала по
-- неизменяемости, либо молча становилась рабочей. Правильно: нет поля —
-- значение НАСЛЕДУЕТСЯ от предыдущей версии; false ставится только при
-- создании (предыдущей версии нет). Триггер тот же (V104), меняется тело функции.
CREATE OR REPLACE FUNCTION orbita_kernel.guard_project_sandbox() RETURNS trigger AS $$
DECLARE prev text;
BEGIN
    IF NEW.kind <> 'project' THEN
        RETURN NEW;
    END IF;
    -- Значение предыдущей версии: UPDATE — та же строка; INSERT — последняя прежняя.
    IF TG_OP = 'UPDATE' THEN
        prev := OLD.doc->>'sandbox';
    ELSE
        SELECT doc->>'sandbox' INTO prev
          FROM orbita_kernel.entity
         WHERE area = NEW.area AND code = NEW.code AND kind = 'project'
         ORDER BY version DESC
         LIMIT 1;
    END IF;
    -- Поля нет: первая версия — false (fail-closed, NULL не проходит мимо
    -- проверки); последующая — наследует предыдущую (правка песочницы
    -- документом без sandbox не делает её рабочей и не падает).
    IF NEW.doc->>'sandbox' IS NULL THEN
        NEW.doc := jsonb_set(NEW.doc, '{sandbox}', COALESCE(prev, 'false')::jsonb);
    END IF;
    -- Неизменяемость: заданное значение обязано совпасть с предыдущей версией.
    IF prev IS NOT NULL AND prev IS DISTINCT FROM (NEW.doc->>'sandbox') THEN
        RAISE EXCEPTION 'orbita: project.sandbox неизменяем, area=% (% -> %)',
            NEW.area, prev, NEW.doc->>'sandbox';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
