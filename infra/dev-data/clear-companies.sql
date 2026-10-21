-- 샘플 상품·업체만 한 트랜잭션에서 제거한다. 일반 상품의 FK가 있으면 전체 롤백한다.
BEGIN;
LOCK TABLE company.p_companies, company.p_products IN SHARE ROW EXCLUSIVE MODE;
DELETE FROM company.p_products p
WHERE p.created_by = 'dev-seed'
  AND p.product_id IN (
      SELECT ('c0ffee39-0001-4000-8000-' || lpad(n::text, 12, '0'))::uuid FROM generate_series(1, 102) AS n
  )
  AND p.company_id IN (
      SELECT company_id FROM company.p_companies
      WHERE created_by = 'dev-seed' AND company_id IN (
          SELECT ('c0ffee39-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid FROM generate_series(1, 34) AS n
      )
  );
DELETE FROM company.p_companies
WHERE created_by = 'dev-seed' AND company_id IN (
    SELECT ('c0ffee39-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid FROM generate_series(1, 34) AS n
);
COMMIT;
