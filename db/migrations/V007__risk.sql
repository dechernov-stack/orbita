-- Реестр рисков (шаг 7). Основание: NPR 8000.4; Прил. 6 БП-PA, Прил. 7 БП-PPA.
-- Реестр — входной материал MCR и KDP; без него пакет передачи неполон.
--
-- Все сравнения с полями документа обёрнуты в COALESCE: CHECK считается
-- выполненным при результате NULL, поэтому отсутствующее поле проходит проверку.
-- Подзапросы в CHECK PostgreSQL запрещает — условия выражены путями jsonb_path.

ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'risk';

ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK)-[0-9]{4}$');

-- Оценки обязательны и лежат в шкале 1–5.
ALTER TABLE objects ADD CONSTRAINT risk_scale CHECK (
    type::text <> 'risk'
    OR (COALESCE((doc->>'probability')::int, 0) BETWEEN 1 AND 5
        AND COALESCE((doc->>'impact')::int, 0) BETWEEN 1 AND 5)
);

-- У риска есть владелец: без него запись не является управляемой.
ALTER TABLE objects ADD CONSTRAINT risk_owner CHECK (
    type::text <> 'risk' OR length(COALESCE(doc->>'owner', '')) > 0
);

-- Остаточная оценка не превышает исходную ни по одной из шкал.
ALTER TABLE objects ADD CONSTRAINT risk_residual CHECK (
    type::text <> 'risk'
    OR doc->'residual' IS NULL
    OR (COALESCE((doc->'residual'->>'probability')::int, 6) <= COALESCE((doc->>'probability')::int, 0)
        AND COALESCE((doc->'residual'->>'impact')::int, 6) <= COALESCE((doc->>'impact')::int, 0))
);
