-- База знаний (шип 4 §2, РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ §3): текстовый индекс в той
-- же базе — блоки канонов документов, термины словаря, пункты нормативов,
-- факты-цитаты. Ищется ГИБРИДНО: полнотекст (tsvector, русская конфигурация) +
-- вектор (pgvector, колонка добавляется ядром при старте, когда расширение
-- есть — образ pgvector/pgvector:pg16). Структура системы и связи здесь не
-- индексируются: они ищутся точно по реестру, вектор их только размоет.
--
-- Схема — ядра (ТЗ-BACKEND §2.2): соседи ходят через порт TextIndex.
CREATE TABLE IF NOT EXISTS orbita_kernel.index_block (
    key         TEXT PRIMARY KEY,
    kind        TEXT        NOT NULL,   -- canon_block · term · clause · fact
    area        TEXT        NOT NULL,   -- library · project:<код>
    ref         TEXT        NOT NULL,   -- код записи, откуда блок (материал, термин, норматив, факт)
    title       TEXT        NOT NULL DEFAULT '',
    text        TEXT        NOT NULL DEFAULT '',
    filters     JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- role · rank · scene · class · mark
    fingerprint TEXT        NOT NULL,
    tsv         TSVECTOR    GENERATED ALWAYS AS (to_tsvector('russian', coalesce(title, '') || ' ' || coalesce(text, ''))) STORED,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS index_block_tsv ON orbita_kernel.index_block USING GIN (tsv);
CREATE INDEX IF NOT EXISTS index_block_area_kind ON orbita_kernel.index_block (area, kind);
CREATE INDEX IF NOT EXISTS index_block_filters ON orbita_kernel.index_block USING GIN (filters);
