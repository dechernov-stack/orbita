-- Служба ИИ (П5 задания «прогон до KDP B»): профиль ограничений объектом
-- и журнал вызовов. Журнал отвечает на вопрос «сколько и почём»: задание,
-- профиль@версия, транспорт, модель, промпт, ответ, стоимость, что
-- отфильтровано и что акцептовано. Вызов, о котором нечего рассказать,
-- неотличим от невыполненного.

ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'ai_profile';

ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB|AP)-[0-9]{4}$');

CREATE TABLE IF NOT EXISTS ai_calls (
  pk             bigserial PRIMARY KEY,
  at             timestamptz NOT NULL DEFAULT now(),
  project_id     text NOT NULL,
  kind           text NOT NULL,               -- вид пакета (prompt-package-kinds)
  profile_id     text,                        -- профиль службы и его версия
  profile_version text,
  transport      text NOT NULL,               -- direct | package
  model          text,                        -- фактическая модель провайдера
  prompt         text NOT NULL,               -- собранный службой промпт
  response       text,                        -- ответ как пришёл (или null при отказе)
  failure        text,                        -- причина отказа транспорта
  tokens_in      integer,
  tokens_out     integer,
  cost_usd       numeric(12, 6),
  proposed       integer NOT NULL DEFAULT 0,  -- разобрано предложений
  filtered       integer NOT NULL DEFAULT 0,  -- снято фильтром до показа
  no_source      integer NOT NULL DEFAULT 0,  -- снято правилом основания
  accepted       integer NOT NULL DEFAULT 0,  -- акцептовано инженером
  accepted_by    text,
  created_by     text NOT NULL
);

CREATE INDEX IF NOT EXISTS ai_calls_project ON ai_calls (project_id, at DESC);
