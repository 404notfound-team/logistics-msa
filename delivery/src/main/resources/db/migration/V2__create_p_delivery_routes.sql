-- 배송 1건의 허브 간 이동 구간. delivery_id는 Delivery 애그리거트가 관리하며 FK 제약은 두지 않는다.
CREATE TABLE p_delivery_routes (
    delivery_route_id         uuid        PRIMARY KEY,
    delivery_id               uuid        NOT NULL,
    sequence                  integer     NOT NULL,
    departure_hub_id          uuid        NOT NULL,
    arrival_hub_id            uuid        NOT NULL,
    expected_distance_km      integer     NOT NULL,
    actual_distance_km        integer,
    expected_duration_seconds integer     NOT NULL,
    actual_duration_seconds   integer,
    status                    varchar(30) NOT NULL,
    assignee_id               uuid
);

-- 배송 상세 조회는 delivery_id로 구간을 순서대로 읽는다.
CREATE UNIQUE INDEX uk_p_delivery_routes_delivery_id_sequence ON p_delivery_routes (delivery_id, sequence);
