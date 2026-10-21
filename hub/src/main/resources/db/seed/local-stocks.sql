-- 상품 ID·소속 허브는 infra/dev-data/README.md의 연결 계약을 따른다.
BEGIN;
LOCK TABLE hub.p_stocks IN SHARE ROW EXCLUSIVE MODE;
DO 'BEGIN
    IF EXISTS (
        SELECT 1 FROM hub.p_stocks
        WHERE id IN (SELECT (''c0ffee39-0002-4000-8000-'' || lpad(n::text, 12, ''0''))::uuid FROM generate_series(1, 102) AS n)
          AND (created_by IS NULL OR created_by NOT IN (''dev-seed''))
    ) THEN
        RAISE EXCEPTION ''Reserved development sample ID belongs to another actor in hub.p_stocks'';
    END IF;
END';

INSERT INTO hub.p_stocks (id, hub_id, product_id, quantity, created_at, created_by, updated_at, updated_by)
SELECT ('c0ffee39-0002-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('00000000-0000-0000-0000-' || lpad(((n - 1) / 6 + 1)::text, 12, '0'))::uuid,
       ('c0ffee39-0001-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       CASE (n - 1) % 3 WHEN 0 THEN 100 WHEN 1 THEN 2 ELSE 0 END,
       CURRENT_TIMESTAMP, 'dev-seed', CURRENT_TIMESTAMP, 'dev-seed'
FROM generate_series(1, 102) AS n
ON CONFLICT DO NOTHING;
COMMIT;
