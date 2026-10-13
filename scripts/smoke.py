#!/usr/bin/env python3
"""실행 중인 로컬 Compose의 등록·라우팅·DB 격리·분산 추적을 확인한다."""
import argparse
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

SERVICES = {"users", "company", "hub", "orders", "delivery"}
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
    result=$(psql -h 127.0.0.1 -U postgres -d "${service}_db" -Atc "SELECT has_schema_privilege('${service}_app', '$service', 'USAGE') AND NOT has_schema_privilege('${service}_app', '$service', 'CREATE') AND NOT has_schema_privilege('${service}_app', 'public', 'CREATE') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname = '${service}_app'")
    test "$result" = t
done
'''
    subprocess.run(["docker", "compose", "exec", "-T", "postgres", "bash", "-eu", "-c", script],
                   cwd=ROOT, check=True)
    print("PASS DB 5개 접속·20개 교차 접속 거부·관리 DB 거부·스키마 권한", flush=True)


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
    check_database()
    eventually(lambda: check_trace(args.zipkin_url, trace_id), "Zipkin 동일 trace의 Gateway → orders → hub", timeout=60)


if __name__ == "__main__":
    main()
