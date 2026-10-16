#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
usage() {
    printf '사용법: bash scripts/dev-data.sh hubs | companies [MANAGER_USER_ID] | stocks | orders | deliveries | clear-companies\n' >&2
    exit 2
}
[[ $# -ge 1 ]] || usage
manager_user_id='c0ffee39-0000-4000-8000-000000000099'
case "$1" in
    hubs)
        [[ $# -eq 1 ]] || usage
        service=hub
        sql="$root/hub/src/main/resources/db/seed/data.sql"
        ;;
    companies)
        [[ $# -le 2 ]] || usage
        manager_user_id="${2:-$manager_user_id}"
        service=company
        sql="$root/company/src/main/resources/db/seed/local-companies.sql"
        ;;
    stocks)
        [[ $# -eq 1 ]] || usage
        service=hub
        sql="$root/hub/src/main/resources/db/seed/local-stocks.sql"
        ;;
    orders)
        [[ $# -eq 1 ]] || usage
        service=orders
        sql="$root/orders/src/main/resources/db/seed/local-orders.sql"
        ;;
    deliveries)
        [[ $# -eq 1 ]] || usage
        service=delivery
        sql="$root/delivery/src/main/resources/db/seed/local_p_deliveries.sql"
        ;;
    clear-companies)
        [[ $# -eq 1 ]] || usage
        service=company
        sql="$root/infra/dev-data/clear-companies.sql"
        ;;
    *) usage ;;
esac
[[ -f "$sql" ]] || { printf '샘플 SQL이 없습니다: %s\n' "$sql" >&2; exit 2; }
cd "$root"
# 앱과 같은 SQL을 재사용한다. 관리자 입력은 psql의 SQL 리터럴 인용을 거친다.
{
    if [[ "$1" == companies ]]; then
        printf "%s\n" "SELECT set_config('logistics.dev_manager_user_id', :'manager_user_id', false);"
    fi
    if [[ "$1" == hubs ]]; then printf 'BEGIN;\n'; fi
    cat "$sql"
    if [[ "$1" == hubs ]]; then printf 'COMMIT;\n'; fi
} | docker compose exec -T postgres sh -eu -c '
    case "$1" in
        hub) export PGPASSWORD="$HUB_DB_PASSWORD" ;;
        company) export PGPASSWORD="$COMPANY_DB_PASSWORD" ;;
        orders) export PGPASSWORD="$ORDERS_DB_PASSWORD" ;;
        delivery) export PGPASSWORD="$DELIVERY_DB_PASSWORD" ;;
        *) exit 2 ;;
    esac
    exec psql -X --host 127.0.0.1 --username "${1}_app" --dbname "${1}_db" \
        --set=ON_ERROR_STOP=1 --set=manager_user_id="$2"
' sh "$service" "$manager_user_id"
