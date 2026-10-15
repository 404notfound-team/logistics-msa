CREATE TABLE p_deliveries (
    delivery_id         uuid         PRIMARY KEY,
    order_id            uuid         NOT NULL,
    status              varchar(30)  NOT NULL,
    departure_hub_id    uuid         NOT NULL,
    destination_hub_id  uuid         NOT NULL,
    address             varchar(255) NOT NULL,
    recipient_name      varchar(100) NOT NULL,
    delivery_manager_id uuid,
    created_at          timestamptz  NOT NULL,
    created_by          varchar(100) NOT NULL,
    updated_at          timestamptz  NOT NULL,
    updated_by          varchar(100) NOT NULL,
    deleted_at          timestamptz,
    deleted_by          varchar(100)
);

-- 주문 1건에는 삭제되지 않은 배송이 1건만 있다.
CREATE UNIQUE INDEX uk_p_deliveries_order_id ON p_deliveries (order_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_p_deliveries_created_at ON p_deliveries (created_at DESC, delivery_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_p_deliveries_departure_hub_id ON p_deliveries (departure_hub_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_p_deliveries_destination_hub_id ON p_deliveries (destination_hub_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_p_deliveries_delivery_manager_id ON p_deliveries (delivery_manager_id) WHERE deleted_at IS NULL;
