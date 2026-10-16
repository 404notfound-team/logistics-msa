#!/usr/bin/env python3
"""새 로컬 Compose의 연결된 샘플·조회 분기를 확인하거나 개발용 JWT를 출력한다."""
import argparse
import base64
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def compose(*args):
    return subprocess.check_output(["docker", "compose", *args], cwd=ROOT, text=True).strip()


def token(base, username, role):
    # 실제 Users 로그인으로 발급한다. 비밀번호·토큰은 메모리에서만 사용한다.
    password = compose("exec", "-T", "users", "printenv", "DEV_DATA_PASSWORD")
    if not password:
        raise SystemExit("DEV_DATA_PASSWORD가 없습니다. init-env.sh 실행 뒤 Users를 다시 시작하세요.")
    result = request(base, "/api/users/signin", body={"username": username, "password": password})
    bearer = result["access_token"]
    payload = bearer.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    assert claims["sub"] == username and claims["role"] == role, "개발 계정의 현재 역할이 요청과 다릅니다"
    return bearer


def request(base, path, bearer=None, expected=200, body=None):
    headers = {"Accept": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers, data=data), timeout=10) as response:
            status, body = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, body = error.code, json.load(error)
    assert status == expected, (path, status, expected)
    return body.get("data", body)


def rows(service, table):
    sql = "SELECT COALESCE(json_agg(t), '[]'::json) FROM (SELECT * FROM " + table + ") t"
    script = '''
case "$1" in
 company) export PGPASSWORD="$COMPANY_DB_PASSWORD" ;;
 hub) export PGPASSWORD="$HUB_DB_PASSWORD" ;;
 orders) export PGPASSWORD="$ORDERS_DB_PASSWORD" ;;
 delivery) export PGPASSWORD="$DELIVERY_DB_PASSWORD" ;;
 *) exit 2 ;;
esac
exec psql -X -h 127.0.0.1 -U "${1}_app" -d "${1}_db" -v ON_ERROR_STOP=1 -Atc "$2"
'''
    return json.loads(compose("exec", "-T", "postgres", "sh", "-eu", "-c", script, "sh", service, sql))


def sample_id(group, number):
    return f"c0ffee39-{group:04d}-4000-8000-{number:012d}"


def check_links():
    hubs = {r["id"] for r in rows("hub", "hub.p_hubs")}
    companies = {r["company_id"]: r for r in rows("company", "company.p_companies")}
    products = {r["product_id"]: r for r in rows("company", "company.p_products")}
    stocks = {r["product_id"]: r for r in rows("hub", "hub.p_stocks") if r["id"].startswith("c0ffee39-0002-")}
    orders = {r["id"]: r for r in rows("orders", "orders.p_orders")}
    deliveries = {r["delivery_id"]: r for r in rows("delivery", "delivery.p_deliveries")}
    assert len(hubs) == 17
    for number in range(1, 35):
        assert companies[sample_id(0, number)]["hub_id"] in hubs
    for number in range(1, 103):
        product = products[sample_id(1, number)]
        stock = stocks[product["product_id"]]
        assert stock["hub_id"] == companies[product["company_id"]]["hub_id"]
        assert stock["quantity"] == [100, 2, 0][(number - 1) % 3]
    for number in range(1, 32):
        order = orders[sample_id(3, number)]
        supplier, receiver = companies[order["supplier_company_id"]], companies[order["receiver_company_id"]]
        assert products[order["product_id"]]["company_id"] == supplier["company_id"]
        assert order["departure_hub_id"] == supplier["hub_id"]
        assert order["destination_hub_id"] == receiver["hub_id"]
        if order["delivery_id"]:
            delivery = deliveries[order["delivery_id"]]
            assert delivery["order_id"] == order["id"]
            for field in ["departure_hub_id", "destination_hub_id", "recipient_name"]:
                assert order[field] == delivery[field], (number, field)
            assert order["delivery_address"] == delivery["address"]
    assert all(orders[sample_id(3, n)]["recovery_action"] is None for n in range(1, 32))
    print("PASS 허브 17·업체 34·상품/재고 102·주문 31·배송 26의 연결과 초기 수량")


def check_api(base):
    master = token(base, "devmaster", "MASTER")
    owner = token(base, "devseed", "USER")
    other = token(base, "devseed2", "USER")
    first = sample_id(0, 1)
    companies = request(base, "/api/companies?size=100", master)
    assert companies["total_elements"] >= 34
    assert len(request(base, f"/api/companies/{first}/products?size=2", master)["content"]) == 2
    assert len(request(base, f"/api/companies/{first}/products?size=2&page=1", master)["content"]) == 1
    assert request(base, "/api/companies?name=__no_sample_matches__", master)["content"] == []
    request(base, f"/api/companies/{first}/products/{sample_id(1, 4)}", master, expected=404)
    for number, quantity in [(1, 100), (2, 2), (3, 0)]:
        path = f"/api/hubs/00000000-0000-0000-0000-000000000001/products/{sample_id(1, number)}/stocks"
        assert request(base, path, master)["quantity"] == quantity
    request(base, "/api/hubs", expected=401)
    request(base, "/api/orders", expected=401)
    assert request(base, "/api/orders?size=100", master)["total_elements"] == 30
    assert request(base, "/api/orders?size=100", owner)["total_elements"] == 15
    assert request(base, "/api/orders?size=100", other)["total_elements"] == 15
    for status, count in [("CONFIRMED", 20), ("CANCELLED", 5), ("FAILED", 5)]:
        assert request(base, f"/api/orders?status={status}", master)["total_elements"] == count
    request(base, f"/api/orders/{sample_id(3, 1)}", other, expected=403)
    request(base, f"/api/orders/{sample_id(3, 31)}", master, expected=404)
    assert request(base, "/api/orders?size=2&page=1", master)["page"] == 1
    assert request(base, "/api/orders?page=100", master)["content"] == []
    for status in ["HUB_WAITING", "HUB_MOVING", "DESTINATION_HUB_ARRIVED", "COMPANY_MOVING", "DELIVERED"]:
        assert request(base, f"/api/deliveries?status={status}", master)["total_elements"] == 4
    assert request(base, "/api/deliveries?size=100", master)["total_elements"] == 20
    assert request(base, "/api/deliveries?size=2&page=1", master)["page"] == 1
    assert request(base, "/api/deliveries?recipient_name=__no_sample_matches__", master)["content"] == []
    request(base, "/api/orders?size=0", master, expected=400)
    print("PASS 상품 페이지·재고 충분/소량/품절·주문 상태/소유권·배송 5상태·삭제 제외·빈 검색·입력 오류")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-url", default="http://127.0.0.1:8080")
    parser.add_argument("--token", action="store_true", help="로컬 개발 계정의 실제 로그인 JWT 출력")
    parser.add_argument("--username", help="기본값: MASTER는 devmaster, USER는 devseed")
    parser.add_argument("--role", choices=["USER", "MASTER"], default="MASTER")
    args = parser.parse_args()
    if args.token:
        username = args.username or ("devmaster" if args.role == "MASTER" else "devseed")
        print(token(args.gateway_url.rstrip("/"), username, args.role))
        return
    check_links()
    check_api(args.gateway_url.rstrip("/"))


if __name__ == "__main__":
    main()
