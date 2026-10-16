#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
# Hub가 실제 적재한 거리·분을 한 번 읽는다. 좌표나 거리 계산식을 복제하지 않는다.
routes="$(PGPASSWORD="$HUB_DB_PASSWORD" psql -X --host="${PGHOST:-postgres}" --username=hub_app --dbname=hub_db \
    --set=ON_ERROR_STOP=1 --tuples-only --no-align --command="SELECT COALESCE(json_agg(r), '[]'::json) FROM (
        SELECT departure_hub_id, arrival_hub_id, distance_km, duration_minutes
        FROM hub.p_hub_routes WHERE deleted_at IS NULL
    ) r")"

# JSON은 psql의 SQL 리터럴 인용을 거친다. TEMP 권한·추가 스키마·중간 테이블이 필요 없다.
PGPASSWORD="$DELIVERY_DB_PASSWORD" psql -X --host="${PGHOST:-postgres}" \
    --username=delivery_app --dbname=delivery_db --set=ON_ERROR_STOP=1 --set=hub_routes="$routes" \
    --file="$root/delivery/src/main/resources/db/seed/local_p_delivery_routes.sql"
