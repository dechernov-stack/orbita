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

# pg_lsclusters есть только в Debian/Ubuntu. На macOS (и любой другой системе)
# скрипт не угадывает раскладку кластеров, а говорит, что сделать руками:
# непроверенная платформа получает инструкцию, а не молчаливо другой код.
if ! command -v pg_lsclusters >/dev/null 2>&1; then
  cat >&2 <<'EOF'
Этот скрипт управляет кластером через pg_lsclusters (Debian/Ubuntu).
На macOS поднимите БД тестов одним из двух способов и запускать скрипт не нужно:

  1) Docker Desktop (проще всего):
       docker run -d --name orbita-test-db -p 5432:5432 \
         -e POSTGRES_USER=orbita -e POSTGRES_PASSWORD=orbita \
         -e POSTGRES_DB=orbita_test postgres:16

  2) Homebrew:
       brew install postgresql@16 && brew services start postgresql@16
       psql -d postgres -c "CREATE ROLE orbita LOGIN PASSWORD 'orbita' SUPERUSER"
       createdb -O orbita orbita_test

Проверка: psql "postgresql://orbita:orbita@127.0.0.1:5432/orbita_test" -c 'SELECT 1'
EOF
  exit 1
fi

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
