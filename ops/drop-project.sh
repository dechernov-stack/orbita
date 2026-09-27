#!/usr/bin/env bash
# Удалить ВРЕМЕННЫЙ проект стенда целиком — после сквозного прогона
# (задание 27.09: «чистый временный проект … после — проект удалить»).
#
# Маршрута удаления проекта в продукте нет и не будет: проект — это история
# решений, её не стирают. Временный проект прогона — не проект, а замер; его
# убирают здесь, одной транзакцией, и только его: код обязан начинаться с
# PJ-TMP- (прогон заводит его так сам), иначе — отказ.
#
# Что уходит: все версии записей области проекта (orbita_kernel.entity),
# связи, у которых хоть один конец — запись этой области (orbita_kernel.link),
# блоки индекса знаний области (orbita_kernel.index_block) и роли учёток в
# проекте (public.project_roles). Полки и другие проекты не трогаются.
#
#   ops/drop-project.sh PJ-TMP-2F-0927            # что будет удалено (ничего не удаляет)
#   ops/drop-project.sh PJ-TMP-2F-0927 --yes      # удалить
#   DB_CONTAINER=orbita-db-1 ops/drop-project.sh …  # контейнер базы (по умолчанию orbita-db-1)
set -euo pipefail

CODE="${1:-}"
CONFIRM="${2:-}"
CONTAINER="${DB_CONTAINER:-orbita-db-1}"

if [[ ! "$CODE" =~ ^PJ-TMP-[A-Za-z0-9-]+$ ]]; then
  echo "удаляется только временный проект прогона: код PJ-TMP-… (латиница, цифры, дефис); пришло «${CODE}»" >&2
  exit 2
fi
AREA="project:$CODE"

psql_() { docker exec -i "$CONTAINER" psql -U orbita -d orbita -v ON_ERROR_STOP=1 -At "$@" 2>/dev/null; }

COUNTS=$(psql_ -v area="$AREA" -v code="$CODE" <<'SQL'
SELECT 'записей (все версии): ' || count(*) FROM orbita_kernel.entity WHERE area = :'area';
SELECT 'связей: ' || count(*) FROM orbita_kernel.link l
 WHERE l.from_id IN (SELECT id FROM orbita_kernel.entity WHERE area = :'area')
    OR l.to_id   IN (SELECT id FROM orbita_kernel.entity WHERE area = :'area');
SELECT 'блоков индекса: ' || count(*) FROM orbita_kernel.index_block WHERE area = :'area';
SELECT 'ролей учёток: ' || count(*) FROM public.project_roles WHERE project_id = :'code';
SQL
)
echo "$CODE ($CONTAINER):"
echo "$COUNTS" | sed 's/^/  /'

if [[ "$CONFIRM" != "--yes" ]]; then
  echo "ничего не удалено; удалить — тот же вызов с --yes"
  exit 0
fi

psql_ -v area="$AREA" -v code="$CODE" <<'SQL'
BEGIN;
DELETE FROM orbita_kernel.link l
 WHERE l.from_id IN (SELECT id FROM orbita_kernel.entity WHERE area = :'area')
    OR l.to_id   IN (SELECT id FROM orbita_kernel.entity WHERE area = :'area');
DELETE FROM orbita_kernel.index_block WHERE area = :'area';
DELETE FROM public.project_roles WHERE project_id = :'code';
DELETE FROM orbita_kernel.entity WHERE area = :'area';
COMMIT;
SQL
LEFT=$(psql_ -v area="$AREA" <<<"SELECT count(*) FROM orbita_kernel.entity WHERE area = :'area';")
echo "удалено; записей области осталось: ${LEFT:-?}"
