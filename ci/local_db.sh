#!/usr/bin/env bash
# Локальная БД для тестов хранилища (STEP-1 §1.6: Testcontainers либо локальная БД).
# Поднимает кластер PostgreSQL 16 и создаёт роль/базу тестов. Идемпотентен.
# Тесты читают ORBITA_TEST_DB_URL / _USER / _PASSWORD; значения по умолчанию — ниже.
set -euo pipefail

PGVER="${ORBITA_PGVER:-16}"
CLUSTER="${ORBITA_PGCLUSTER:-main}"
ROLE=orbita
PASS=orbita
DB_TEST=orbita_test

status="$(pg_lsclusters --no-header 2>/dev/null | awk -v v="$PGVER" -v c="$CLUSTER" '$1==v && $2==c {print $4}')"
if [ -z "$status" ]; then
  echo "кластер PostgreSQL $PGVER/$CLUSTER не найден — установите postgresql-$PGVER" >&2
  exit 1
fi
if [ "$status" != "online" ]; then
  pg_ctlcluster "$PGVER" "$CLUSTER" start
fi

as_postgres() {
  if [ "$(id -un)" = "postgres" ]; then psql -qAt -c "$1"
  elif [ "$(id -u)" = "0" ]; then su postgres -c "psql -qAt -c \"$1\""
  else sudo -u postgres psql -qAt -c "$1"
  fi
}

# Роль с паролем для TCP-подключений тестов. SUPERUSER — только для локальной
# разработки: тестам нужно пересоздавать схему public.
if [ -z "$(as_postgres "SELECT 1 FROM pg_roles WHERE rolname='$ROLE'")" ]; then
  as_postgres "CREATE ROLE $ROLE LOGIN PASSWORD '$PASS' SUPERUSER"
fi
if [ -z "$(as_postgres "SELECT 1 FROM pg_database WHERE datname='$DB_TEST'")" ]; then
  as_postgres "CREATE DATABASE $DB_TEST OWNER $ROLE"
fi

PGPASSWORD="$PASS" psql -h 127.0.0.1 -U "$ROLE" -d "$DB_TEST" -qAt -c "SELECT 'db ready: ' || current_database()"
