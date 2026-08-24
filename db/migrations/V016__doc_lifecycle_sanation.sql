-- Санация: мусорный doc.lifecycle у видов, чьи схемы его не несут.
--
-- ObjectStore.transition до починки писал блок lifecycle в doc ВСЕМ видам.
-- У вида, схема которого lifecycle не несёт (additionalProperties: false),
-- этот блок делал объект нередактируемым: следующая правка валидировалась
-- схемой и падала «property 'lifecycle' is not defined» (находка второго
-- захода: ответ замечания обзора не сохранялся; та же мина стояла под
-- каждым риском). Источник починен — transition пишет lifecycle только там,
-- где блок уже есть; здесь вычищается уже разложенный мусор.
--
-- Перечень — виды ядра без lifecycle в схеме: review_item, risk, scenario,
-- evidence, validation. Чистятся только ТЕКУЩИЕ строки: история неприкосновенна.

UPDATE objects
   SET doc = doc - 'lifecycle'
 WHERE valid_to IS NULL
   AND type IN ('review_item', 'risk', 'scenario', 'evidence', 'validation')
   AND doc ? 'lifecycle';
