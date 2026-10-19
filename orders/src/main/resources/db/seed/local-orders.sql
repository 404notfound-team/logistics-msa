-- 조회·검색·권한 확인용 상태 스냅샷. 외부 호출이나 Slack 알림을 실행하지 않는다.
-- 일시적인 PROCESSING/CANCELLING/RECOVERY_REQUIRED는 실제 복구 테스트에서 생성한다.
BEGIN;
LOCK TABLE orders.p_orders IN SHARE ROW EXCLUSIVE MODE;
DO 'BEGIN
    IF EXISTS (
        SELECT 1 FROM orders.p_orders
        WHERE id IN (SELECT (''c0ffee39-0003-4000-8000-'' || lpad(n::text, 12, ''0''))::uuid FROM generate_series(1, 31) AS n)
          AND (created_by IS NULL OR created_by NOT IN (''devseed'', ''devseed2'', ''dev-seed'', ''dev-seed-other''))
    ) THEN
        RAISE EXCEPTION ''Reserved development sample ID belongs to another actor in orders.p_orders'';
    END IF;
END';

WITH samples AS (
    SELECT n, ((n - 1) % 17) * 2 + 1 AS supplier_number
    FROM generate_series(1, 31) AS n
), linked AS (
    SELECT *, CASE WHEN n % 2 = 0 THEN supplier_number + 1 ELSE (supplier_number + 1) % 34 + 1 END AS receiver_number
    FROM samples
)
INSERT INTO orders.p_orders (
    id, status, receiver_company_id, supplier_company_id, product_id, quantity,
    product_name, unit_price, departure_hub_id, destination_hub_id,
    delivery_address, recipient_name, request_notes, idempotency_key, delivery_id,
    last_error, recovery_phase, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
)
SELECT ('c0ffee39-0003-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       CASE WHEN n BETWEEN 21 AND 25 THEN 'CANCELLED' WHEN n BETWEEN 26 AND 30 THEN 'FAILED' ELSE 'CONFIRMED' END,
       ('c0ffee39-0000-4000-8000-' || lpad(receiver_number::text, 12, '0'))::uuid,
       ('c0ffee39-0000-4000-8000-' || lpad(supplier_number::text, 12, '0'))::uuid,
       ('c0ffee39-0001-4000-8000-' || lpad(((supplier_number - 1) * 3 + 1)::text, 12, '0'))::uuid,
       CASE WHEN n BETWEEN 26 AND 30 THEN 101 ELSE 3 END,
       '[개발샘플] 재고충분 상품 ' || ((supplier_number - 1) * 3 + 1), 10000,
       ('00000000-0000-0000-0000-' || lpad(((supplier_number - 1) / 2 + 1)::text, 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(((receiver_number - 1) / 2 + 1)::text, 12, '0'))::uuid,
       '[개발샘플] 가상 배송지 ' || receiver_number, '개발 수령인 ' || n,
       '상태별 조회·검색용 스냅샷',
       ('c0ffee39-0005-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       CASE WHEN n BETWEEN 26 AND 30 THEN NULL ELSE ('c0ffee39-0004-4000-8000-' || lpad(n::text, 12, '0'))::uuid END,
       CASE WHEN n BETWEEN 26 AND 30 THEN 'INSUFFICIENT_STOCK' ELSE NULL END, NULL,
       '2026-10-01 00:00:00+00'::timestamptz + n * interval '1 hour',
       CASE WHEN n % 2 = 0 THEN 'devseed2' ELSE 'devseed' END,
       '2026-10-01 00:00:00+00'::timestamptz + n * interval '1 hour',
       CASE WHEN n % 2 = 0 THEN 'devseed2' ELSE 'devseed' END,
       CASE WHEN n = 31 THEN '2026-10-03 00:00:00+00'::timestamptz ELSE NULL END,
       CASE WHEN n = 31 THEN 'devseed' ELSE NULL END
FROM linked
ON CONFLICT (id) DO NOTHING;
COMMIT;
