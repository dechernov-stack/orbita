-- Схема хранилища ИС. Реализация ADR-011.
-- Требования: TZ-COM-003 (статусы/базирование), TZ-COM-005 (происхождение),
-- TZ-COM-006 (воспроизводимость), TZ-MOD-005 (граф зависимостей и stale), TZ-MOD-007 (ID).

CREATE TYPE object_type   AS ENUM ('need','service','requirement','component','scenario');
CREATE TYPE lifecycle     AS ENUM ('Draft','Preliminary','Approved','Baseline','Cancelled');
CREATE TYPE link_kind     AS ENUM ('trace','allocation','verification');
CREATE TYPE prov_source   AS ENUM ('manual','computed','ai_proposed','imported');

-- ОБЪЕКТЫ. Версионность интервалами: Baseline не изменяется, создаётся новая версия.
CREATE TABLE objects (
    pk          bigserial PRIMARY KEY,
    id          text        NOT NULL,          -- ND-0001, SV-0001, RQ-0001, CM-0001, SC-0001
    type        object_type NOT NULL,
    version     text        NOT NULL,
    status      lifecycle   NOT NULL DEFAULT 'Draft',
    doc         jsonb       NOT NULL,          -- валидируется схемой на границе API (TZ-MOD-002)
    valid_from  timestamptz NOT NULL DEFAULT now(),
    valid_to    timestamptz,                   -- NULL = текущая версия
    supersedes  bigint      REFERENCES objects(pk),
    change_ref  text,                          -- основание изменения Baseline-объекта
    created_by  text        NOT NULL,
    CONSTRAINT  id_pattern CHECK (id ~ '^(ND|SV|RQ|CM|SC)-[0-9]{4}$'),
    CONSTRAINT  interval_sane CHECK (valid_to IS NULL OR valid_to > valid_from)
);
-- Ровно одна текущая версия объекта (TZ-MOD-007: ID стабилен, не переиспользуется)
CREATE UNIQUE INDEX objects_current ON objects(id) WHERE valid_to IS NULL;
CREATE INDEX objects_type_status ON objects(type, status) WHERE valid_to IS NULL;
CREATE INDEX objects_doc ON objects USING gin(doc);

-- СВЯЗИ. Направление хранится ОДИН раз (ADR-011); обратный обход — рекурсивным CTE.
CREATE TABLE links (
    from_id text      NOT NULL,
    to_id   text      NOT NULL,
    kind    link_kind NOT NULL DEFAULT 'trace',
    PRIMARY KEY (from_id, to_id, kind),
    CONSTRAINT no_self_link CHECK (from_id <> to_id)
);
CREATE INDEX links_to ON links(to_id, kind);

-- ПАРАМЕТРЫ. Величина без единицы или происхождения недопустима (TZ-MOD-004).
CREATE TABLE params (
    object_id   text   NOT NULL,
    name        text   NOT NULL,
    value       double precision,
    unit        text   NOT NULL,
    provenance  jsonb  NOT NULL,
    formula     text,                          -- выражение через другие параметры
    is_tpm      boolean NOT NULL DEFAULT false,
    tpm         jsonb,                         -- цель, требуемый резерв, тренд
    PRIMARY KEY (object_id, name),
    CONSTRAINT unit_not_blank CHECK (length(trim(unit)) > 0),
    CONSTRAINT prov_has_source CHECK (provenance ? 'source'),
    -- значение либо задано, либо вычисляется формулой
    CONSTRAINT value_or_formula CHECK (value IS NOT NULL OR formula IS NOT NULL),
    -- предложение ИИ до акцепта не влияет на расчёты (TZ-AI-004)
    CONSTRAINT ai_needs_accept CHECK (
        provenance->>'source' <> 'ai_proposed'
        OR provenance->'ai' ? 'accepted'
    )
);

-- ЗАВИСИМОСТИ ПАРАМЕТРОВ (граф пересчёта, TZ-MOD-005)
CREATE TABLE param_deps (
    object_id     text NOT NULL,
    name          text NOT NULL,
    dep_object_id text NOT NULL,
    dep_name      text NOT NULL,
    PRIMARY KEY (object_id, name, dep_object_id, dep_name),
    FOREIGN KEY (object_id, name) REFERENCES params(object_id, name) ON DELETE CASCADE
);

-- РЕЗУЛЬТАТЫ МОДЕЛИРОВАНИЯ. Привязаны к версиям входов (TZ-COM-006).
CREATE TABLE results (
    pk             bigserial PRIMARY KEY,
    scenario_id    text        NOT NULL,
    kind           text        NOT NULL,       -- visibility | flow-result | kpi-vector
    payload        jsonb       NOT NULL,
    input_versions jsonb       NOT NULL,       -- версии всех входных наборов
    module_version text        NOT NULL,
    rng_seed       bigint      NOT NULL,       -- без зерна результат невоспроизводим
    stale          boolean     NOT NULL DEFAULT false,
    computed_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT scenario_pattern CHECK (scenario_id ~ '^SC-[0-9]{4}$')
);
CREATE INDEX results_scenario ON results(scenario_id, kind) WHERE NOT stale;

-- Запрет прямого изменения Baseline-объекта (TZ-COM-003).
-- Изменение допустимо только закрытием интервала при создании новой версии.
CREATE OR REPLACE FUNCTION forbid_baseline_update() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'Baseline' AND OLD.valid_to IS NULL THEN
        IF NEW.valid_to IS NULL OR NEW.doc IS DISTINCT FROM OLD.doc THEN
            RAISE EXCEPTION
              'TZ-COM-003: объект % в статусе Baseline не изменяется напрямую; требуется процедура изменения', OLD.id;
        END IF;
        IF NEW.change_ref IS NULL THEN
            RAISE EXCEPTION
              'TZ-COM-003: закрытие версии Baseline-объекта % требует указания основания (change_ref)', OLD.id;
        END IF;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER objects_baseline_guard
    BEFORE UPDATE ON objects FOR EACH ROW EXECUTE FUNCTION forbid_baseline_update();
