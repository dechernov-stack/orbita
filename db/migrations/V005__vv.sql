-- CR-003: события верификации, свидетельства, валидация, интерфейсы.
-- Основание: ADR-019, NASA SP-2016-6105 Rev 2 (разд. 2.4, 5.3, 5.4; прил. D, E).
--
-- Номер V005: V004 занят исправлением NULL-семантики ограничений V003.
--
-- ВАЖНО о CHECK и NULL. Ограничение считается выполненным, если выражение даёт
-- TRUE ИЛИ NULL. Поэтому сравнение с отсутствующим полем JSON (NULL) пропускает
-- запись — ровно тот случай, ради которого ограничение и пишется.
-- Все сравнения с полями документа здесь обёрнуты в COALESCE.

-- Свидетельства — самостоятельные объекты, а не строковая ссылка.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'evidence';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'validation';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'interface';

-- Расширяем шаблон идентификатора: EV-, VA-, IF-
ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF)-[0-9]{4}$');

-- Вид декомпозиции: распределение бюджета либо производное требование.
-- Свёртка бюджета применяется ТОЛЬКО к allocated (NASA: logical decomposition
-- порождает derived requirements, которые в бюджет родителя не входят).
ALTER TABLE links ADD COLUMN IF NOT EXISTS derivation_kind text
  CHECK (derivation_kind IS NULL OR derivation_kind IN ('allocated','derived'));

-- Связь derive обязана нести вид декомпозиции.
ALTER TABLE links ADD CONSTRAINT derive_has_kind CHECK (
    kind <> 'derive' OR derivation_kind IS NOT NULL
);

-- Интерфейсное требование распределяется на интерфейс, а не на элемент.
ALTER TABLE objects ADD CONSTRAINT interface_req_allocation CHECK (
    type <> 'requirement'
    OR COALESCE(doc->>'category', '') <> 'interface'
    OR doc->'allocated_to' IS NULL
    OR EXISTS (SELECT 1 FROM jsonb_array_elements(doc->'allocated_to') a
               WHERE a ? 'interface')
);

-- Предварительное событие верификации не может быть закрывающим.
ALTER TABLE objects ADD CONSTRAINT preliminary_not_closing CHECK (
    type <> 'requirement'
    OR doc->'verification_events' IS NULL
    OR NOT EXISTS (SELECT 1 FROM jsonb_array_elements(doc->'verification_events') e
                   WHERE e->>'kind' = 'preliminary' AND (e->>'closes')::boolean IS TRUE)
);

-- Валидация привязывается к ожиданию стейкхолдера, не к требованию.
-- COALESCE обязателен: без него объект валидации БЕЗ поля target проходит проверку.
ALTER TABLE objects ADD CONSTRAINT validation_target CHECK (
    type <> 'validation'
    OR COALESCE(doc->>'target', '') ~ '^(ND|SV)-[0-9]{4}$'
);
