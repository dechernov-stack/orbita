-- Распределение интерфейсного требования — условие БАЗИРОВАНИЯ, а не записи
-- (CR-003 п. 7 в редакции CR-002 «ловушка 5»).
--
-- Прежнее ограничение V005 запрещало ЗАПИСЬ интерфейсного требования, чьё
-- распределение не указывает на интерфейс. На шаге «требования из сервисов»
-- дерева изделия ещё нет — оно заводится следующим шагом, — и распределять
-- служба не на что: запрет останавливал работу ровно там, где проект уже
-- признал такой запрет ловушкой для полноты плана верификации.
--
-- Полнота теперь спрашивается при переходе в Baseline: там же, где её
-- спрашивает Baselining.canBaseline, и той же причиной. Черновик и
-- Preliminary/Approved допускаются неполными.
ALTER TABLE objects DROP CONSTRAINT IF EXISTS interface_req_allocation;

ALTER TABLE objects ADD CONSTRAINT interface_req_allocation CHECK (
    status <> 'Baseline'
    OR type <> 'requirement'
    OR COALESCE(doc->>'category', '') <> 'interface'
    OR jsonb_path_exists(doc->'allocated_to', '$[*].interface')
);
