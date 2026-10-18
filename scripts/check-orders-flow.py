#!/usr/bin/env python3
"""실제 Users·Gateway·Company·Hub·Delivery를 거쳐 주문 생성·취소·삭제를 검증한다."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import runpy
import time
import urllib.error
import urllib.request
from uuid import uuid4

# 기존 개발 계정 로그인·Compose·샘플 ID 규약을 재사용한다.
DEV = runpy.run_path(str(Path(__file__).with_name("check-dev-data.py")))


def request(base, method, path, bearer=None, body=None, key=None):
    headers = {"Accept": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    if key:
        headers["Idempotency-Key"] = key
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    call = urllib.request.Request(base + path, method=method, headers=headers, data=data)
    try:
        response = urllib.request.urlopen(call, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def expect(result, status):
    actual, body = result
    if actual != status:
        code = body.get("code") if isinstance(body, dict) else None
        raise AssertionError(f"HTTP {actual}, expected {status}, code={code}")
    return body["data"] if isinstance(body, dict) and "data" in body else body


def require_delivery_apis():
    # 유효한 주문을 만들기 전에 인증·라우팅·입력 검증의 존재를 확인한다.
    # 비밀번호·서비스 토큰은 컨테이너 환경에서만 사용하고 출력하지 않는다.
    missing = str(uuid4())
    probes = [("GET", f"/internal/deliveries/by-order/{missing}", 404),
              ("POST", "/internal/deliveries", 400),
              ("POST", f"/internal/deliveries/{missing}/cancel", 404),
              ("DELETE", f"/internal/deliveries/{missing}", 404)]
    script = '''
exec curl --silent --show-error --max-time 10 --request "$1" \
  --header "X-Service-Token: $ORDERS_SERVICE_TOKEN" \
  --header "X-Actor-Username: devmaster" --header 'Content-Type: application/json' \
  --data '{}' --write-out '\n%{http_code}' "http://127.0.0.1:8080$2"
'''
    for method, path, status in probes:
        output = DEV["compose"]("exec", "-T", "delivery", "sh", "-eu", "-c", script, "sh", method, path)
        raw, actual = output.rsplit("\n", 1)
        body = json.loads(raw)
        code = body.get("code")
        if int(actual) != status or (status == 404 and code != "DELIVERY_NOT_FOUND"):
            raise RuntimeError(f"Delivery 내부 API 미준비: {method} {path}, HTTP {actual}, code={code}. 주문은 생성하지 않았습니다.")


def terminal(base, path, bearer, initial):
    result = initial
    deadline = time.monotonic() + 30
    while result[0] == 202 and time.monotonic() < deadline:
        time.sleep(0.2)
        order = expect(request(base, "GET", path, bearer), 200)
        if order["status"] == "CANCELLED":
            return order
        result = request(base, "POST", path + "/cancel", bearer)
    order = expect(result, 200)
    assert order["status"] == "CANCELLED", order["status"]
    return order


def confirmed(base, path, bearer):
    deadline = time.monotonic() + 30
    while True:
        order = expect(request(base, "GET", path, bearer), 200)
        if order["status"] == "CONFIRMED":
            return order
        if order["status"] not in ("PROCESSING", "RECOVERY_REQUIRED"):
            raise AssertionError(f"주문 확정 실패: {order['status']}")
        if time.monotonic() >= deadline:
            raise TimeoutError("30초 안에 주문이 확정되지 않았습니다.")
        time.sleep(0.2)


def sample_order():
    sample = DEV["sample_id"]
    product = sample(1, 1)
    stock_path = f"/api/hubs/00000000-0000-0000-0000-000000000001/products/{product}/stocks"
    body = {"receiver_company_id": sample(0, 3), "supplier_company_id": sample(0, 1),
            "product_id": product, "quantity": 3, "delivery_address": "[연동검증] 가상 배송지",
            "recipient_name": "연동검증 수령인", "request_notes": "실제 서비스 연동 검증"}
    return stock_path, body


def check(base):
    require_delivery_apis()
    master = DEV["token"](base, "devmaster", "MASTER")
    owner = DEV["token"](base, "devseed", "USER")
    other = DEV["token"](base, "devseed2", "USER")
    stock_path, body = sample_order()
    before = expect(request(base, "GET", stock_path, master), 200)["quantity"]
    assert before >= body["quantity"], "첫 개발 상품의 재고가 부족합니다."
    key = str(uuid4())
    order_id = None
    try:
        expect(request(base, "POST", "/api/orders", body=body, key=key), 401)
        with ThreadPoolExecutor(max_workers=8) as executor:
            calls = list(executor.map(lambda _: request(base, "POST", "/api/orders", owner, body, key), range(8)))
        ids = set()
        for status, response in calls:
            assert status in (200, 201, 202), (status, response.get("code"))
            ids.add(response["data"]["order_id"])
        assert len(ids) == 1
        order_id = ids.pop()
        path = "/api/orders/" + order_id
        assert sum(status == 201 for status, _ in calls) <= 1
        order = confirmed(base, path, owner)
        assert order["request_notes"] == body["request_notes"]
        delivery_id = order["delivery_id"]
        delivery_path = "/api/deliveries/" + delivery_id
        shipping = expect(request(base, "GET", delivery_path, master), 200)
        assert shipping["order_id"] == order_id and shipping["status"] == "HUB_WAITING"
        assert expect(request(base, "GET", stock_path, master), 200)["quantity"] == before - 3
        expect(request(base, "GET", path, other), 403)
        expect(request(base, "POST", path + "/cancel", other), 403)
        expect(request(base, "DELETE", path, owner), 403)
        assert expect(request(base, "POST", "/api/orders", owner, body, key), 200)["order_id"] == order_id
        terminal(base, path, master, request(base, "POST", path + "/cancel", master))
        expect(request(base, "POST", path + "/cancel", owner), 200)
        assert expect(request(base, "GET", stock_path, master), 200)["quantity"] == before
        assert expect(request(base, "GET", delivery_path, master), 200)["status"] == "CANCELLED"
        order_row = next(row for row in DEV["rows"]("orders", "orders.p_orders") if row["id"] == order_id)
        assert order_row["created_by"] == "devseed" and order_row["updated_by"] == "devmaster"
        receipts = [row for row in DEV["rows"]("hub", "hub.p_stock_restorations") if row["order_id"] == order_id]
        assert len(receipts) == 1 and receipts[0]["created_by"] == "devmaster"
        expect(request(base, "DELETE", path, master), 204)
        expect(request(base, "DELETE", path, master), 204)
        expect(request(base, "GET", path, master), 404)
        expect(request(base, "GET", delivery_path, master), 404)
        routes = [row for row in DEV["rows"]("delivery", "delivery.p_delivery_routes") if row["delivery_id"] == delivery_id]
        assert routes and all(row["deleted_at"] is not None for row in routes)
        expect(request(base, "POST", "/api/orders", owner, body, key), 409)
        assert expect(request(base, "GET", stock_path, master), 200)["quantity"] == before
        print("PASS 실제 로그인·Gateway·8개 동시 요청·멱등·배송 생성·소유권·취소·재고 복원·감사·배송 경로 삭제")
    except Exception:
        # 차감 결과 불명 주문을 새 키로 다시 만들거나 재고를 추정 보정하지 않는다.
        if order_id is None:
            try:
                matching = [row for row in DEV["rows"]("orders", "orders.p_orders")
                            if row["idempotency_key"] == key and row["created_by"] == "devseed"]
                if len(matching) == 1:
                    order_id = matching[0]["id"]
            except Exception:
                print("주문 ID 조회 실패: 아래 멱등 키로 기존 주문을 확인하세요.")
        print(f"FAIL orders-flow: order_id={order_id}, idempotency_key={key}. 같은 키의 주문·복구 상태를 먼저 확인하세요.")
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    check(args.gateway_url.rstrip("/"))
