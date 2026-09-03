# ADR-049. StrictDoc — канал обмена и публикации требований (не хранилище)

**Статус:** принято (ответы владельца 03.09 «Очередь дальше» п. 3 = шип 5
ТЗ-CODE-НОЧЬ; РЕШЕНИЕ-STRICTDOC-КАНАЛ.md §1)
**Контекст:** источник истины Орбиты — битемпоральная БД (история,
статусы, изоляция проектов, акцепт ИИ). Заменять её файлами — потерять
половину системы. Но StrictDoc (Apache-2.0) даёт ReqIF, HTML, PDF, XLSX и
валидацию грамматики штатно — своего конвертера писать не нужно.

## Решение

1. **Контейнер `strictdoc`** (`ops/strictdoc`, `strictdoc==0.29.0`, профиль
   compose, флаг `ORBITA_STRICTDOC_URL`): раскладывает модель проекта в
   грамматику Орбиты `.sgra` и документ `.sdoc` (поле в поле: структура
   требования ADR-045 плюс показатель `MOP_NAME/MOP_OP/MOP_VALUE/MOP_UNIT`;
   нужды и сервисы — своими элементами; связи — RELATIONS с ролями Refines ·
   Derives · DependsOn · ConflictsWith · Traces), зовёт штатный `strictdoc
   export` (ReqIF/HTML/PDF/XLSX) и разбирает `.sdoc` самим StrictDoc
   (кандидаты с `foreign_attributes`). Идентификаторы: UID = код, MID =
   внутренний id; повторный экспорт неизменённого проекта совпадает
   побайтно (тест `tools/check_sdoc_roundtrip.py`).
2. **Маршруты ядра**: `GET /export/sdoc` (документ), `GET /export/sdoc/grammar`,
   `GET /export/sdoc/reqif` (ReqIF от StrictDoc), `POST /export/sdoc/baseline`
   — **снимок базирования** `.sdoc` в каталоге файлов проекта
   (`baselines/<проект>/<время>/`), диф двух базирований — `diff`/`git diff`;
   `POST /import/sdoc` — кандидаты в акцепт-контур, в модель сам не пишет.
   Без флага — 503 с причиной, не заглушка.
3. **Условие владельца (п. 8)**: собственный ReqIF-контур (`ReqifExport`,
   `ops/exchange`) живёт, пока StrictDoc-канал не пройдёт
   `check_reqif_roundtrip.py`-эквивалент на тех же данных
   (`check_sdoc_roundtrip.py`: детерминизм, полнота UID, `strictdoc export`,
   `reqif validate`, обратный разбор). После — свой конвертер сносится и
   включается сторож «REQ-IF в нашем коде — 0». До того — deprecated в ADR-023.

## Последствия

- Контейнер `strictdoc` на стенде не собран: демон Docker Desktop не
  достигал реестра (прокси); проверка канала выполнена локально пакетом
  strictdoc — первый прогон в контейнере отдельно.
- HTML-отчёт автономным файлом закрывается штатным `strictdoc export
  --formats=html` (ответ на «отдать комплект смежнику»).
