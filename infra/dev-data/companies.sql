-- 개발 전용. scripts/dev-data.sh가 제한된 company_app 계정으로 실행한다.
-- 실제 업무 테이블은 Company 엔티티가 생성하며 이 SQL은 DDL을 수행하지 않는다.
DO $$
BEGIN
    IF to_regclass('company.p_companies') IS NULL THEN
        RAISE EXCEPTION 'company.p_companies is missing; start company with the local profile first';
    END IF;
END $$;

-- 적재와 제거가 동시에 실행되거나 예약 ID에 일반 업체가 끼어드는 것을 방지한다.
LOCK TABLE company.p_companies IN SHARE ROW EXCLUSIVE MODE;

\if :apply
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM company.p_companies
        WHERE company_id IN (
            SELECT ('c0ffee39-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid
            FROM generate_series(1, 34) AS n
        )
        AND created_by IS DISTINCT FROM 'dev-seed'
    ) THEN
        RAISE EXCEPTION 'A sample company ID belongs to another actor; no data was changed';
    END IF;
END $$;

WITH regions(hub_number, region) AS (
    VALUES
        (1, '서울'), (2, '경기 북부'), (3, '경기 남부'), (4, '부산'),
        (5, '대구'), (6, '인천'), (7, '광주'), (8, '대전'), (9, '울산'),
        (10, '세종'), (11, '강원'), (12, '충북'), (13, '충남'),
        (14, '전북'), (15, '전남'), (16, '경북'), (17, '경남')
), samples AS (
    SELECT hub_number, region, kind,
           (hub_number - 1) * 2 + kind AS sample_number
    FROM regions CROSS JOIN generate_series(1, 2) AS kind
)
INSERT INTO company.p_companies (
    company_id, name, hub_id, address, manager_user_id,
    created_at, created_by, updated_at, updated_by
)
SELECT
    ('c0ffee39-0000-4000-8000-' || lpad(sample_number::text, 12, '0'))::uuid,
    '[개발샘플] ' || region || CASE kind WHEN 1 THEN ' 식품유통' ELSE ' 생활용품' END,
    ('00000000-0000-0000-0000-' || lpad(hub_number::text, 12, '0'))::uuid,
    region || ' 가상산업로 ' || sample_number || ' (테스트 전용 가상 주소)',
    :'manager_user_id',
    CURRENT_TIMESTAMP, 'dev-seed', CURRENT_TIMESTAMP, 'dev-seed'
FROM samples
ON CONFLICT (company_id) DO NOTHING;
\else
DELETE FROM company.p_companies
WHERE created_by = 'dev-seed'
  AND company_id IN (
      SELECT ('c0ffee39-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid
      FROM generate_series(1, 34) AS n
  );
\endif

SELECT count(*) AS sample_companies
FROM company.p_companies
WHERE created_by = 'dev-seed'
  AND company_id IN (
      SELECT ('c0ffee39-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid
      FROM generate_series(1, 34) AS n
  );
