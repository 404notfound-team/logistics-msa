-- Hibernate가 만들지 못하는 부분 UNIQUE 인덱스. 삭제된 배송의 주문 ID는 다시 사용할 수 있다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_p_deliveries_order_id
    ON delivery.p_deliveries (order_id) WHERE deleted_at IS NULL;
