-- ==========================================================
-- HubRoute 초기 데이터
-- 17개 허브 × 17개 허브 = 289개 P2P 경로
-- ==========================================================

WITH centers(id, latitude, longitude) AS (
    VALUES
        (1, 37.477::double precision, 127.124::double precision),
        (2, 37.659, 126.833),
        (3, 37.215, 127.489),
        (4, 35.115, 129.043),
        (5, 35.883, 128.583),
        (6, 37.447, 126.732),
        (7, 35.160, 126.852),
        (8, 36.350, 127.384),
        (9, 35.538, 129.312),
        (10, 36.480, 127.289),
        (11, 37.881, 127.729),
        (12, 36.635, 127.492),
        (13, 36.659, 126.672),
        (14, 35.824, 127.147),
        (15, 34.992, 126.484),
        (16, 36.576, 128.505),
        (17, 35.228, 128.682)
),
     distances AS (
         SELECT
             a.id AS departure_id,
             b.id AS arrival_id,

             CASE
                 WHEN a.id = b.id THEN 0::numeric
    ELSE ROUND(
    (
    6371.0 * ACOS(
    LEAST(
    1.0,
    GREATEST(
    -1.0,
    SIN(RADIANS(a.latitude))
    * SIN(RADIANS(b.latitude))
    + COS(RADIANS(a.latitude))
    * COS(RADIANS(b.latitude))
    * COS(
    RADIANS(
    b.longitude - a.longitude
    )
    )
    )
    )
    ) * 1.3
    )::numeric,
    2
    )
END AS distance_km

    FROM centers a
    CROSS JOIN centers b
)
INSERT INTO hub.p_hub_routes (
    id,
    departure_hub_id,
    arrival_hub_id,
    duration_minutes,
    distance_km,
    created_at,
    created_by,
    updated_at,
    updated_by
)
SELECT
    MD5(
            'hub-route:'
                || departure_id
                || ':'
                || arrival_id
    )::uuid,

    (
        '00000000-0000-0000-0000-'
            || LPAD(departure_id::text, 12, '0')
        )::uuid,

    (
        '00000000-0000-0000-0000-'
            || LPAD(arrival_id::text, 12, '0')
        )::uuid,

    CASE
        WHEN departure_id = arrival_id THEN 0
        ELSE CEIL(distance_km)::integer
END,

    distance_km,
    NOW(),
    'system',
    NOW(),
    'system'

FROM distances

ON CONFLICT (departure_hub_id, arrival_hub_id)
DO NOTHING;
