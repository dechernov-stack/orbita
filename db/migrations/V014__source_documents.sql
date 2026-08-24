-- Библиотека исходных документов (ADR-030): записка министерства, стандарт,
-- протокол — объект модели SD-NNNN с текстом и реквизитами. Из него берётся
-- вход службы ИИ, на него ссылается порождённая постановка.

ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'source_document';

ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP|SD)-[0-9]{4}$');
