-- Ядро v2: своя схема БД у модуля (ТЗ-BACKEND §2.2).
--
-- Старые таблицы v1 живут рядом и не трогаются: strangler — новое рядом со
-- старым, пока волны не заменят его. Внешние ключи наружу идут только на
-- kernel.entity.id, поэтому чужие схемы ничего не знают о структуре друг друга.
CREATE SCHEMA IF NOT EXISTS orbita_kernel;

-- Сущность: одна строка на ВЕРСИЮ, текущая помечена valid_to IS NULL.
-- История битемпоральна (МОДЕЛЬ-ДАННЫХ §1): правка не затирает прошлое.
CREATE TABLE IF NOT EXISTS orbita_kernel.entity (
    pk          BIGSERIAL PRIMARY KEY,
    id          TEXT        NOT NULL,
    code        TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    area        TEXT        NOT NULL,
    born_in     TEXT,
    status      TEXT        NOT NULL,
    version     INT         NOT NULL,
    doc         JSONB       NOT NULL,
    prov_channel TEXT       NOT NULL,
    prov_author  TEXT       NOT NULL,
    prov_source  TEXT,
    prov_anchor  TEXT,
    prov_fingerprint TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to    TIMESTAMPTZ
);

-- Ровно одна текущая версия на id: правило держит база, не код.
CREATE UNIQUE INDEX IF NOT EXISTS entity_current
    ON orbita_kernel.entity (id) WHERE valid_to IS NULL;

-- Код уникален в своей области: в проекте свой RQ-0001, в библиотеке свой.
CREATE UNIQUE INDEX IF NOT EXISTS entity_code_in_area
    ON orbita_kernel.entity (area, code) WHERE valid_to IS NULL;

CREATE INDEX IF NOT EXISTS entity_by_kind ON orbita_kernel.entity (area, kind) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS entity_by_scene ON orbita_kernel.entity (born_in) WHERE valid_to IS NULL;

-- Связи реестром: тип известен заранее, обоснование хранится рядом со связью.
CREATE TABLE IF NOT EXISTS orbita_kernel.link (
    pk          BIGSERIAL PRIMARY KEY,
    id          TEXT        NOT NULL,
    link_type   TEXT        NOT NULL,
    from_id     TEXT        NOT NULL,
    to_id       TEXT        NOT NULL,
    rationale   TEXT,
    prov_channel TEXT       NOT NULL,
    prov_author  TEXT       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to    TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS link_current ON orbita_kernel.link (id) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS link_from ON orbita_kernel.link (from_id, link_type) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS link_to ON orbita_kernel.link (to_id, link_type) WHERE valid_to IS NULL;
