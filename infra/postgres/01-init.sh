#!/usr/bin/env bash
set -euo pipefail

# 개발용 앱 계정은 자기 스키마에서 테이블을 생성하고 소유한 테이블을 변경한다.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
SQL

for service in users company hub orders delivery; do
    case "$service" in
        users) password="$USERS_DB_PASSWORD" ;;
        company) password="$COMPANY_DB_PASSWORD" ;;
        hub) password="$HUB_DB_PASSWORD" ;;
        orders) password="$ORDERS_DB_PASSWORD" ;;
        delivery) password="$DELIVERY_DB_PASSWORD" ;;
    esac
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        --set=service="$service" --set=role="${service}_app" \
        --set=db="${service}_db" --set=password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'role', :'password') \gexec
SELECT format('CREATE DATABASE %I', :'db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'role') \gexec
SQL

    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${service}_db" \
        --set=service="$service" --set=role="${service}_app" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('CREATE SCHEMA %I', :'service') \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', :'service', :'role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'service', :'role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I', :'service', :'role') \gexec
SQL
done


