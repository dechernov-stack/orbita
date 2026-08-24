-- Санация: зрелость у видов с собственным циклом (TZ-COM-003).
--
-- До запрета (ReqService.maturityApplies) перевод зрелости был доступен всем
-- видам, и замечание обзора можно было «базировать» массовым переводом.
-- Колоночный Baseline у такого вида — мусор: зрелость к нему не применяется,
-- а защита базированного (Editing, ObjectStore, триггер baseline_guard)
-- запирала закрытие замечания немым отказом. Статус возвращается к Draft —
-- собственный цикл замечания живёт в doc.status и не затрагивается.
--
-- Перечень видов повторяет критерий maturityApplies: схема без lifecycle И
-- вид не встречается в планках ворот (gates.json). На сегодня это только
-- review_item; появится новый такой вид — санация ему не потребуется,
-- потому что перевод зрелости уже запрещён.

ALTER TABLE objects DISABLE TRIGGER objects_baseline_guard;

UPDATE objects
   SET status = 'Draft'
 WHERE type = 'review_item'
   AND status IN ('Preliminary', 'Approved', 'Baseline');

ALTER TABLE objects ENABLE TRIGGER objects_baseline_guard;
