#!/bin/sh
# О-18: восстановление стенда из копии НА ЧИСТОЙ БАЗЕ (прогон обязателен:
# непроверенная копия — не копия). Восстанавливает БД в контейнер postgres
# по имени (по умолчанию — одноразовый orbita-restore-db) и том файлов.
# Использование: tools/restore_stand.sh <db.dump> [files.tar.gz] [container]
set -eu
DUMP="$1"; FILES="${2:-}"; TARGET="${3:-orbita-restore-db}"
docker exec -i "$TARGET" pg_restore -U orbita -d orbita --clean --if-exists < "$DUMP"
if [ -n "$FILES" ]; then
    docker run --rm -i -v orbita-files-restore:/files alpine tar xzf - -C /files < "$FILES"
fi
echo "restore ok into $TARGET"
