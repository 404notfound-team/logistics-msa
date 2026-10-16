-- 배송 담당자 컬럼 이름을 배송 경로(p_delivery_routes.assignee_id)와 맞춘다.
ALTER TABLE p_deliveries RENAME COLUMN delivery_manager_id TO assignee_id;
ALTER INDEX idx_p_deliveries_delivery_manager_id RENAME TO idx_p_deliveries_assignee_id;
