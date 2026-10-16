-- local 프로필의 상태별 조회용 스냅샷. 취소 주문의 배송과 삭제 주문의 배송은 논리 삭제 상태다.
BEGIN;
LOCK TABLE delivery.p_deliveries IN SHARE ROW EXCLUSIVE MODE;
DO 'BEGIN
    IF EXISTS (
        SELECT 1 FROM delivery.p_deliveries
        WHERE delivery_id IN (SELECT (''c0ffee39-0004-4000-8000-'' || lpad(n::text, 12, ''0''))::uuid FROM generate_series(1, 31) AS n)
          AND (created_by IS NULL OR created_by NOT IN (''dev-seed''))
    ) THEN
        RAISE EXCEPTION ''Reserved development sample ID belongs to another actor in delivery.p_deliveries'';
    END IF;
END';

WITH samples AS (
    SELECT n, ((n - 1) % 17) + 1 AS departure_number,
           CASE WHEN n % 2 = 0 THEN ((n - 1) % 17) + 1 ELSE n % 17 + 1 END AS destination_number
    FROM generate_series(1, 31) AS n
    WHERE n NOT BETWEEN 26 AND 30
)
INSERT INTO delivery.p_deliveries (
    delivery_id, order_id, status, departure_hub_id, destination_hub_id,
    address, recipient_name, delivery_manager_id,
    created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
)
SELECT ('c0ffee39-0004-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('c0ffee39-0003-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       CASE WHEN n BETWEEN 21 AND 25 THEN 'HUB_WAITING'
            ELSE (ARRAY['HUB_WAITING', 'HUB_MOVING', 'DESTINATION_HUB_ARRIVED', 'COMPANY_MOVING', 'DELIVERED'])[(n - 1) % 5 + 1] END,
       ('00000000-0000-0000-0000-' || lpad(departure_number::text, 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(destination_number::text, 12, '0'))::uuid,
       '[개발샘플] 가상 배송지 ' || CASE WHEN n % 2 = 0 THEN (departure_number - 1) * 2 + 2 ELSE (departure_number * 2) % 34 + 1 END,
       '개발 수령인 ' || n,
       CASE WHEN n % 5 = 1 THEN NULL ELSE 'c0ffee39-0000-4000-8000-000000000098'::uuid END,
       '2026-10-01 00:00:00+00'::timestamptz + n * interval '1 hour', 'dev-seed',
       '2026-10-01 00:00:00+00'::timestamptz + n * interval '1 hour', 'dev-seed',
       CASE WHEN n BETWEEN 21 AND 25 OR n = 31 THEN '2026-10-03 00:00:00+00'::timestamptz ELSE NULL END,
       CASE WHEN n BETWEEN 21 AND 25 OR n = 31 THEN 'dev-seed' ELSE NULL END
FROM samples
ON CONFLICT (delivery_id) DO NOTHING;
COMMIT;
