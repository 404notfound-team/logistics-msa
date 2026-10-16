-- local 프로필 전용. 실제 업무 테이블은 엔티티가 생성한다.
BEGIN;
LOCK TABLE company.p_companies IN SHARE ROW EXCLUSIVE MODE;
DO 'BEGIN
    IF EXISTS (
        SELECT 1 FROM company.p_companies
        WHERE company_id IN (SELECT (''c0ffee39-0000-4000-8000-'' || lpad(n::text, 12, ''0''))::uuid FROM generate_series(1, 34) AS n)
          AND (created_by IS NULL OR created_by NOT IN (''dev-seed''))
    ) THEN
        RAISE EXCEPTION ''Reserved development sample ID belongs to another actor in company.p_companies'';
    END IF;
END';
LOCK TABLE company.p_products IN SHARE ROW EXCLUSIVE MODE;
DO 'BEGIN
    IF EXISTS (
        SELECT 1 FROM company.p_products
        WHERE product_id IN (SELECT (''c0ffee39-0001-4000-8000-'' || lpad(n::text, 12, ''0''))::uuid FROM generate_series(1, 102) AS n)
          AND (created_by IS NULL OR created_by NOT IN (''dev-seed''))
    ) THEN
        RAISE EXCEPTION ''Reserved development sample ID belongs to another actor in company.p_products'';
    END IF;
END';

WITH regions(hub_number, region) AS (
    VALUES (1, '서울'), (2, '경기 북부'), (3, '경기 남부'), (4, '부산'),
           (5, '대구'), (6, '인천'), (7, '광주'), (8, '대전'), (9, '울산'),
           (10, '세종'), (11, '강원'), (12, '충북'), (13, '충남'),
           (14, '전북'), (15, '전남'), (16, '경북'), (17, '경남')
), samples AS (
    SELECT hub_number, region, kind, (hub_number - 1) * 2 + kind AS n
    FROM regions CROSS JOIN generate_series(1, 2) AS kind
)
INSERT INTO company.p_companies (
    company_id, name, hub_id, address, manager_user_id,
    created_at, created_by, updated_at, updated_by
)
SELECT ('c0ffee39-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       '[개발샘플] ' || region || CASE kind WHEN 1 THEN ' 식품유통' ELSE ' 생활용품' END,
       ('00000000-0000-0000-0000-' || lpad(hub_number::text, 12, '0'))::uuid,
       region || ' 가상산업로 ' || n || ' (테스트 전용 가상 주소)',
       COALESCE(NULLIF(current_setting('logistics.dev_manager_user_id', true), '')::uuid,
                'c0ffee39-0000-4000-8000-000000000099'::uuid),
       '2026-10-01 00:00:00+00'::timestamptz + n * interval '1 minute', 'dev-seed',
       '2026-10-01 00:00:00+00'::timestamptz + n * interval '1 minute', 'dev-seed'
FROM samples
ON CONFLICT (company_id) DO NOTHING;

-- 업체마다 충분·소량·품절 상품 한 개씩. 논리 삭제한 업체에는 새 상품을 추가하지 않는다.
WITH samples AS (
    SELECT n, (n - 1) / 3 + 1 AS company_number, (n - 1) % 3 AS kind
    FROM generate_series(1, 102) AS n
)
INSERT INTO company.p_products (
    product_id, company_id, name, description, price,
    created_at, created_by, updated_at, updated_by
)
SELECT ('c0ffee39-0001-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       c.company_id,
       '[개발샘플] ' || CASE kind WHEN 0 THEN '재고충분' WHEN 1 THEN '소량' ELSE '품절' END || ' 상품 ' || n,
       '연결된 허브 재고로 조회·차감 조건을 확인하는 개발용 상품', (kind + 1) * 10000,
       c.created_at + (kind + 1) * interval '1 second', 'dev-seed',
       c.created_at + (kind + 1) * interval '1 second', 'dev-seed'
FROM samples JOIN company.p_companies c
  ON c.company_id = ('c0ffee39-0000-4000-8000-' || lpad(company_number::text, 12, '0'))::uuid
WHERE c.deleted_at IS NULL
ON CONFLICT (product_id) DO NOTHING;
COMMIT;
