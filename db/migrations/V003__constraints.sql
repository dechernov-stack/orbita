-- CR-001: оператор сравнения, свёртка бюджетов, явная декомпозиция требований.
-- Основание: ADR-017. Дефект: «не более 500 г» и «не менее 500 г» были неразличимы.

-- Новый вид связи: декомпозиция требования на составляющие.
-- Отличается от 'trace' (уточнение нужды/сервиса): по 'derive' считается свёртка бюджетов.
ALTER TYPE link_kind ADD VALUE IF NOT EXISTS 'derive';

-- Частичное распределение: требование покрывается элементом не целиком.
ALTER TABLE links ADD COLUMN IF NOT EXISTS allocation_kind text
  CHECK (allocation_kind IS NULL OR allocation_kind IN ('full','partial'));
ALTER TABLE links ADD COLUMN IF NOT EXISTS rationale text;

-- Требование обязано нести оператор, если у него есть показатель.
-- Проверка на уровне БД: страховка от обхода валидации схемы.
ALTER TABLE objects ADD CONSTRAINT mop_has_operator CHECK (
    type <> 'requirement'
    OR doc->'mop' IS NULL
    OR doc->'mop'->>'operator' IN ('eq','le','ge','lt','gt','range','tolerance')
);

-- Диапазон и допуск требуют второй величины.
ALTER TABLE objects ADD CONSTRAINT mop_range_complete CHECK (
    type <> 'requirement'
    OR doc->'mop' IS NULL
    OR doc->'mop'->>'operator' <> 'range'
    OR doc->'mop'->'upper' IS NOT NULL
);
ALTER TABLE objects ADD CONSTRAINT mop_tolerance_complete CHECK (
    type <> 'requirement'
    OR doc->'mop' IS NULL
    OR doc->'mop'->>'operator' <> 'tolerance'
    OR doc->'mop'->'tolerance' IS NOT NULL
);
