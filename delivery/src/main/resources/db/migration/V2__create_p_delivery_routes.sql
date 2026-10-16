-- 배송 1건의 허브 간 이동 구간. delivery_id는 Delivery 애그리거트가 관리하며 FK 제약은 두지 않는다.
CREATE TABLE p_delivery_routes (
    delivery_route_id         uuid         PRIMARY KEY,
    delivery_id               uuid         NOT NULL,
    sequence                  integer      NOT NULL,
    departure_hub_id          uuid         NOT NULL,
    arrival_hub_id            uuid         NOT NULL,
    expected_distance_km      integer      NOT NULL,
    actual_distance_km        integer,
    expected_duration_seconds integer      NOT NULL,
    actual_duration_seconds   integer,
    status                    varchar(30)  NOT NULL,
    assignee_id               uuid,
    created_at                timestamptz  NOT NULL,
    created_by                varchar(100) NOT NULL,
    updated_at                timestamptz  NOT NULL,
    updated_by                varchar(100) NOT NULL,
    deleted_at                timestamptz,
    deleted_by                varchar(100),
    -- 같은 배송 안에서 순서는 중복될 수 없다. 엔티티(@UniqueConstraint)와 같은 제약이며, 조회 인덱스도 겸한다.
    CONSTRAINT uk_p_delivery_routes_delivery_id_sequence UNIQUE (delivery_id, sequence)
);
