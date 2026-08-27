#!/bin/sh
# О-18: ночная копия живого стенда Орбиты вне машины стенда.
# БД postgres (pg_dump -Fc из контейнера) + том файлов исходных документов
# (появился кругом 2 стартового потока). Локальная ротация 14 копий;
# офсайт — scp на 216 (/root/backups/orbita-stand), откуда backup_prod
# оркестратора уносит на 72.56.107.117 (существующая цепочка).
# Восстановление: tools/restore_stand.sh <дамп>.
set -eu
# cron зовёт скрипт с куцым PATH — docker там не находится, и ночная копия
# выходила нулевой (замечено 2026-08-27: дамп 03:00 — 0 байт)
PATH="/usr/local/bin:/opt/homebrew/bin:$PATH"
STAMP=$(date +%Y%m%d-%H%M%S)
LOCAL_DIR="$HOME/Backups/orbita-stand"
REMOTE="root@216.57.108.107:/root/backups/orbita-stand"
mkdir -p "$LOCAL_DIR"

DB_DUMP="$LOCAL_DIR/orbita-db-$STAMP.dump"
docker exec orbita-db-1 pg_dump -U orbita -d orbita -Fc > "$DB_DUMP"

FILES_TAR=""
if docker volume inspect orbita-files >/dev/null 2>&1; then
    FILES_TAR="$LOCAL_DIR/orbita-files-$STAMP.tar.gz"
    docker run --rm -v orbita-files:/files alpine tar czf - -C /files . > "$FILES_TAR"
fi

# офсайт: недоступность 216 — жалоба в лог, копия остаётся локально.
# Ключ — явно: в cron нет ssh-агента, авторизованный на 216 ключ —
# kabanchik_assist (проверено 2026-08-27)
SSH_KEY="$HOME/.ssh/kabanchik_assist"
ssh -i "$SSH_KEY" -o IdentitiesOnly=yes -o ConnectTimeout=10 \
    root@216.57.108.107 'mkdir -p /root/backups/orbita-stand' \
    && scp -q -i "$SSH_KEY" -o IdentitiesOnly=yes "$DB_DUMP" ${FILES_TAR:+"$FILES_TAR"} "$REMOTE/" \
    || echo "offsite failed: копия только локальная" >&2

# ротация: 14 свежих локально
ls -t "$LOCAL_DIR"/orbita-db-*.dump 2>/dev/null | tail -n +15 | xargs rm -f 2>/dev/null || true
ls -t "$LOCAL_DIR"/orbita-files-*.tar.gz 2>/dev/null | tail -n +15 | xargs rm -f 2>/dev/null || true
echo "backup ok: $(ls -lh "$DB_DUMP" | awk '{print $5}') $DB_DUMP${FILES_TAR:+ + files}"
