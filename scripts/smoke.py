#!/usr/bin/env python3
"""실행 중인 로컬 Compose의 등록·라우팅·OpenAPI·DB 격리·분산 추적을 확인한다."""
import argparse
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

SERVICES = {"users", "company", "hub", "orders", "delivery"}
API_DOCS = {
    "users": ("사용자 API", ["/api/users"]),
    "company": ("업체·상품 API", ["/api/companies", "/api/products"]),
    "hub": ("허브·재고 API", ["/api/hubs", "/api/hub-routes", "/api/stocks"]),
    "orders": ("주문 API", ["/api/orders"]),
    "delivery": ("배송 API", ["/api/deliveries"]),
}
ROOT = Path(__file__).resolve().parents[1]


def get(url, headers=None):
    request = urllib.request.Request(url, headers={"Accept": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.load(response)


def eventually(check, label, timeout=180):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            check()
            print(f"PASS {label}", flush=True)
            return
        except (AssertionError, urllib.error.URLError, TimeoutError) as error:
            last_error = error
            time.sleep(3)
    raise RuntimeError(f"{label}: {last_error}")


def check_registry(url):
    applications = get(f"{url}/eureka/apps")["applications"].get("application", [])
    if isinstance(applications, dict):
        applications = [applications]
    registered = set()
    for application in applications:
        instances = application["instance"]
        if isinstance(instances, dict):
            instances = [instances]
        if any(instance["status"] == "UP" for instance in instances):
            registered.add(application["name"].lower())
    assert SERVICES | {"gateway"} <= registered, registered


def check_routes(url, trace_id):
    for service in sorted(SERVICES):
        assert get(f"{url}/__infra/{service}") == {"service": service, "status": "UP"}
    trace = get(f"{url}/__infra/trace", {"traceparent": f"00-{trace_id}-{secrets.token_hex(8)}-01"})
    assert trace == {"service": "orders", "downstream": {"service": "hub", "status": "UP"}}
    try:
        get(f"{url}/internal/infra/ping")
    except urllib.error.HTTPError as error:
        assert error.code == 404, error.code
    else:
        raise AssertionError("Gateway가 내부 경로를 외부에 노출합니다")


def check_database():
    # 비밀번호는 컨테이너 환경 안에서만 읽으며 출력하거나 인자로 전달하지 않는다.
    script = r'''
for service in users company hub orders delivery; do
    case "$service" in
        users) export PGPASSWORD="$USERS_DB_PASSWORD" ;;
        company) export PGPASSWORD="$COMPANY_DB_PASSWORD" ;;
        hub) export PGPASSWORD="$HUB_DB_PASSWORD" ;;
        orders) export PGPASSWORD="$ORDERS_DB_PASSWORD" ;;
        delivery) export PGPASSWORD="$DELIVERY_DB_PASSWORD" ;;
    esac
    psql -h 127.0.0.1 -U "${service}_app" -d "${service}_db" -v ON_ERROR_STOP=1 -Atc 'SELECT 1' >/dev/null
    psql -h 127.0.0.1 -U "${service}_app" -d "${service}_db" -v ON_ERROR_STOP=1 \
        --set=service="$service" <<'SQL' >/dev/null
BEGIN;
SELECT format('CREATE TABLE %I.__infra_ddl_probe (id integer)', :'service') \gexec
SELECT format('ALTER TABLE %I.__infra_ddl_probe ADD COLUMN payload text', :'service') \gexec
SELECT format('DROP TABLE %I.__infra_ddl_probe', :'service') \gexec
ROLLBACK;
SQL
    for target in users company hub orders delivery; do
        if [ "$service" != "$target" ] && psql -h 127.0.0.1 -U "${service}_app" -d "${target}_db" -c 'SELECT 1' >/dev/null 2>&1; then
            echo "FAIL cross-database access: $service -> $target" >&2
            exit 1
        fi
    done
    if psql -h 127.0.0.1 -U "${service}_app" -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
        echo "FAIL administrative database access: $service" >&2
        exit 1
    fi
done
export PGPASSWORD="$POSTGRES_PASSWORD"
for service in users company hub orders delivery; do
    result=$(psql -h 127.0.0.1 -U postgres -d "${service}_db" -Atc "SELECT has_schema_privilege('${service}_app', '$service', 'USAGE') AND has_schema_privilege('${service}_app', '$service', 'CREATE') AND NOT has_schema_privilege('${service}_app', 'public', 'CREATE') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname = '${service}_app'")
    test "$result" = t
done
'''
    subprocess.run(["docker", "compose", "exec", "-T", "postgres", "bash", "-eu", "-c", script],
                   cwd=ROOT, check=True)
    print("PASS DB 5개 접속·자기 스키마 DDL·20개 교차 접속 거부·관리 DB 거부·스키마 권한", flush=True)


def check_openapi(url):
    with urllib.request.urlopen(f"{url}/swagger-ui.html", timeout=8) as response:
        assert response.status == 200
        assert "Swagger UI" in response.read().decode("utf-8")
    config = get(f"{url}/v3/api-docs/swagger-config")
    urls = {entry["name"]: entry["url"] for entry in config["urls"]}
    assert urls == {service: f"/v3/api-docs/{service}" for service in SERVICES}, urls
    for service, (title, prefixes) in API_DOCS.items():
        document = get(f"{url}{urls[service]}")
        assert document["openapi"].startswith("3."), document
        assert document["info"]["title"] == title, document["info"]
        assert [server["url"] for server in document["servers"]] == ["/"], document["servers"]
        for path in document.get("paths", {}):
            assert any(path == prefix or path.startswith(prefix + "/") for prefix in prefixes), (service, path)
    for path in ["/v3/api-docs/eureka", "/v3/api-docs/users/internal/infra/ping"]:
        try:
            get(f"{url}{path}")
        except urllib.error.HTTPError as error:
            assert error.code == 404, (path, error.code)
        else:
            raise AssertionError(f"문서 라우트가 허용하지 않은 경로를 노출합니다: {path}")


def check_trace(url, trace_id):
    trace = get(f"{url}/api/v2/trace/{trace_id}")
    services = {span.get("localEndpoint", {}).get("serviceName") for span in trace}
    assert {"gateway", "orders", "hub"} <= services, services
    assert {span["traceId"] for span in trace} == {trace_id}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-url", default="http://127.0.0.1:8080")
    parser.add_argument("--eureka-url", default="http://127.0.0.1:8761")
    parser.add_argument("--zipkin-url", default="http://127.0.0.1:9411")
    args = parser.parse_args()
    trace_id = secrets.token_hex(16)
    eventually(lambda: check_registry(args.eureka_url), "Eureka 앱 6개 UP 등록")
    eventually(lambda: check_routes(args.gateway_url, trace_id), "Gateway 라우팅·Feign 호출·내부 경로 차단")
    eventually(lambda: check_openapi(args.gateway_url), "Swagger UI·서비스별 OpenAPI·공개 경로 필터·Gateway 기준 주소")
    check_database()
    eventually(lambda: check_trace(args.zipkin_url, trace_id), "Zipkin 동일 trace의 Gateway → orders → hub", timeout=60)


if __name__ == "__main__":
    main()
