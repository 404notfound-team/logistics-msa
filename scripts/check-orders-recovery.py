#!/usr/bin/env python3
"""전용 Compose에서 실제 서비스의 지연 커밋·Orders 재시작·멱등 복구를 검증한다."""
import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import runpy
import subprocess
import time
from uuid import uuid4
from urllib.parse import urlsplit

FLOW = runpy.run_path(str(Path(__file__).with_name("check-orders-flow.py")))
DEV = FLOW["DEV"]
request = FLOW["request"]
expect = FLOW["expect"]
RELATIONS = {"delivery": "delivery.p_deliveries", "hub": "hub.p_stock_restorations"}
DB_SHELL = '''
case "$1" in
 delivery) export PGPASSWORD="$DELIVERY_DB_PASSWORD" ;;
 hub) export PGPASSWORD="$HUB_DB_PASSWORD" ;;
 *) exit 2 ;;
esac
export PGAPPNAME="$2"
service="$1"
shift 2
exec psql -X -qAt -h 127.0.0.1 -U "${service}_app" -d "${service}_db" -v ON_ERROR_STOP=1 "$@"
'''


def dedicated_project(base):
    if not os.environ.get("COMPOSE_PROJECT_NAME", "").startswith("logistics-orders-recovery-"):
        raise RuntimeError("장애 검증은 COMPOSE_PROJECT_NAME=logistics-orders-recovery-이름인 전용 환경에서만 실행합니다.")
    gateway = urlsplit(base)
    port = int(DEV["compose"]("port", "gateway", "8080").rsplit(":", 1)[1])
    if (gateway.scheme != "http" or gateway.hostname not in ("127.0.0.1", "localhost")
            or (gateway.port or 80) != port or gateway.username or gateway.password
            or gateway.path or gateway.query or gateway.fragment):
        raise RuntimeError("Gateway 주소는 같은 전용 Compose 프로젝트의 로컬 공개 포트여야 합니다.")


def db_command(service, label, query):
    if service not in RELATIONS:
        raise ValueError("허용하지 않은 DB")
    return ["docker", "compose", "exec", "-T", "postgres", "sh", "-eu", "-c", DB_SHELL,
            "sh", service, label, "-c", query]


def sql(service, query):
    return subprocess.check_output(db_command(service, "orders-recovery-control", query),
                                   cwd=DEV["ROOT"], text=True, timeout=10).strip()


def eventually(probe, description, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        result = probe()
        if result:
            return result
        if time.monotonic() >= deadline:
            raise TimeoutError(description)
        time.sleep(0.2)


@contextmanager
def delayed_commit(service):
    """실제 INSERT를 지연한다. 가짜 응답 없이 호출자 타임아웃 뒤 실제 커밋을 허용한다."""
    relation = RELATIONS[service]
    label = "orders-recovery-" + uuid4().hex
    query = ("BEGIN; SET LOCAL lock_timeout = '5s'; SET LOCAL statement_timeout = '45s'; "
             f"LOCK TABLE {relation} IN SHARE MODE; SELECT pg_sleep(45); ROLLBACK;")
    process = subprocess.Popen(db_command(service, label, query), cwd=DEV["ROOT"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    try:
        def acquired():
            if process.poll() is not None:
                raise RuntimeError("DB 지연 주입을 시작하지 못했습니다.")
            return sql(service, "SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a "
                       f"ON a.pid=l.pid WHERE a.application_name='{label}' AND l.granted "
                       f"AND l.mode='ShareLock' AND l.relation='{relation}'::regclass);") == "t"
        eventually(acquired, "DB 지연 주입 준비 시간 초과", timeout=10)
        yield
    finally:
        try:
            # 이 실행이 만든 세션만 취소한다. ON_ERROR_STOP으로 연결이 닫히며 잠금은 롤백된다.
            sql(service, "SELECT pg_cancel_backend(pid) FROM pg_stat_activity "
                f"WHERE application_name='{label}' AND pid<>pg_backend_pid();")
        finally:
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.communicate(timeout=5)
        remaining = sql(service, "SELECT count(*) FROM pg_stat_activity "
                        f"WHERE application_name='{label}';")
        assert remaining == "0", "장애 주입 DB 세션이 남아 있습니다."


def assert_insert_waiting(service):
    relation = RELATIONS[service]
    count = sql(service, "SELECT count(*) FROM pg_locks WHERE NOT granted "
                f"AND mode='RowExclusiveLock' AND relation='{relation}'::regclass;")
    assert int(count) > 0, "실제 서비스 INSERT 대기를 확인하지 못했습니다."


def start_orders():
    DEV["compose"]("start", "orders")

    def healthy():
        try:
            return bool(DEV["compose"]("exec", "-T", "orders", "curl", "--fail", "--silent",
                                       "--max-time", "2", "http://127.0.0.1:8080/actuator/health"))
        except subprocess.CalledProcessError:
            return False
    eventually(healthy, "Orders 재시작 후 정상 상태 대기 시간 초과", timeout=90)


def rows_for(service, table, field, value):
    return [row for row in DEV["rows"](service, table) if row[field] == value]


def recovered(base, path, owner, status):
    def poll():
        code, body = request(base, "GET", path, owner)
        if code in (502, 503, 504):
            return None
        order = expect((code, body), 200)
        if order["status"] == status:
            return order
        assert order["status"] in ("PROCESSING", "CANCELLING", "RECOVERY_REQUIRED"), order["status"]
        return None
    return eventually(poll, "Orders 재시작 후 기존 주문 복구 시간 초과", timeout=60)


def check_case(base, owner, master, restoration):
    stock_path, body = FLOW["sample_order"]()
    before = expect(request(base, "GET", stock_path, master), 200)["quantity"]
    assert before >= body["quantity"], "검증용 재고가 부족합니다."
    key, order_id = str(uuid4()), None
    stopped = False
    service = "hub" if restoration else "delivery"
    try:
        if restoration:
            created = request(base, "POST", "/api/orders", owner, body, key)
            assert created[0] in (200, 201, 202), created[0]
            order_id = created[1]["data"]["order_id"]
            FLOW["confirmed"](base, "/api/orders/" + order_id, owner)
        with delayed_commit(service):
            if restoration:
                pending = request(base, "POST", f"/api/orders/{order_id}/cancel", master)
            else:
                pending = request(base, "POST", "/api/orders", owner, body, key)
            order_id = expect(pending, 202)["order_id"]
            assert_insert_waiting(service)
            # 호출이 타임아웃된 뒤 Orders를 중지한다. 외부 커밋 후 새 앱 인스턴스가 복구해야 한다.
            stopped = True
            DEV["compose"]("stop", "-t", "10", "orders")
        table = "hub.p_stock_restorations" if restoration else "delivery.p_deliveries"
        committed = eventually(lambda: rows_for(service, table, "order_id", order_id),
                               "타임아웃 뒤 실제 서비스 커밋 대기 시간 초과")
        assert len(committed) == 1
        if restoration:
            assert committed[0]["created_by"] == "devmaster"
        # 재시작 전에 이미 완료된 주문을 복구 성공으로 오인하지 않는다.
        saved = rows_for("orders", "orders.p_orders", "id", order_id)
        assert len(saved) == 1 and saved[0]["status"] in ("PROCESSING", "CANCELLING", "RECOVERY_REQUIRED")
        assert saved[0]["recovery_action"] == ("CANCEL" if restoration else "CREATE")
        start_orders()
        stopped = False
        path = "/api/orders/" + order_id
        order = recovered(base, path, owner, "CANCELLED" if restoration else "CONFIRMED")
        replay = expect(request(base, "POST", "/api/orders", owner, body, key), 200)
        assert replay["order_id"] == order_id and replay["status"] == order["status"]
        assert len(rows_for("orders", "orders.p_orders", "idempotency_key", key)) == 1
        deliveries = rows_for("delivery", "delivery.p_deliveries", "order_id", order_id)
        assert len(deliveries) == 1 and deliveries[0]["delivery_id"] == order["delivery_id"]
        expected = before if restoration else before - body["quantity"]
        assert expect(request(base, "GET", stock_path, master), 200)["quantity"] == expected
        saved = rows_for("orders", "orders.p_orders", "id", order_id)[0]
        assert saved["created_by"] == "devseed"
        assert saved["updated_by"] == ("devmaster" if restoration else "devseed")
        FLOW["terminal"](base, path, master, request(base, "POST", path + "/cancel", master))
        receipts = rows_for("hub", "hub.p_stock_restorations", "order_id", order_id)
        assert len(receipts) == 1 and receipts[0]["created_by"] == "devmaster"
        expect(request(base, "DELETE", path, master), 204)
        assert expect(request(base, "GET", stock_path, master), 200)["quantity"] == before
        print(f"PASS {service} 실제 타임아웃·지연 커밋·Orders 재시작·동일 주문 복구·재고 및 감사 보존")
    except Exception:
        print(f"FAIL orders-recovery: service={service}, order_id={order_id}, idempotency_key={key}. "
              "같은 주문의 상태를 확인하세요. 새 키로 재주문하거나 재고를 추정 보정하지 않습니다.")
        raise
    finally:
        if stopped:
            start_orders()


def check(base):
    dedicated_project(base)
    FLOW["require_delivery_apis"]()
    owner = DEV["token"](base, "devseed", "USER")
    master = DEV["token"](base, "devmaster", "MASTER")
    check_case(base, owner, master, restoration=False)
    check_case(base, owner, master, restoration=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-url", default="http://127.0.0.1:8080")
    check(parser.parse_args().gateway_url.rstrip("/"))
