#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
    printf '사용법: bash scripts/dev-data.sh hubs | companies MANAGER_USER_ID | clear-companies\n' >&2
    exit 2
}

[[ $# -ge 1 ]] || usage
case "$1" in
    hubs)
        [[ $# -eq 1 ]] || usage
        service=hub
        apply=false
        manager_user_id=''
        sql="$root/hub/src/main/resources/db/seed/data.sql"
        if [[ ! -f "$sql" ]]; then
            printf '허브 기준 SQL이 없습니다: %s\n' "$sql" >&2
            exit 2
        fi
        ;;
    companies)
        [[ $# -eq 2 && -n "$2" ]] || usage
        service=company
        apply=true
        manager_user_id="$2"
        sql="$root/infra/dev-data/companies.sql"
        ;;
    clear-companies)
        [[ $# -eq 1 ]] || usage
        service=company
        apply=false
        manager_user_id=''
        sql="$root/infra/dev-data/companies.sql"
        ;;
    *) usage ;;
esac

# Compose가 각 작업 디렉터리의 .env를 읽는다. 비밀번호는 컨테이너 안에서만 참조한다.
cd "$root"
docker compose --project-directory "$root" -f "$root/compose.yml" exec -T postgres \
    sh -eu -c '
        case "$1" in
            hub) export PGPASSWORD="$HUB_DB_PASSWORD" ;;
            company) export PGPASSWORD="$COMPANY_DB_PASSWORD" ;;
            *) exit 2 ;;
        esac
        exec psql -X --host 127.0.0.1 --username "${1}_app" --dbname "${1}_db" \
            --single-transaction --set=ON_ERROR_STOP=1 \
            --set=apply="$2" --set=manager_user_id="$3"
    ' sh "$service" "$apply" "$manager_user_id" < "$sql"
