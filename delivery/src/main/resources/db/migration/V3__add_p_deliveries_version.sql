-- 배송 수정(상태 변경·담당자 배정)의 낙관적 락 버전. 기존 행은 0부터 시작한다.
ALTER TABLE p_deliveries ADD COLUMN version bigint NOT NULL DEFAULT 0;
