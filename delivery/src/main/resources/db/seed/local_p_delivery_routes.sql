-- infra/dev-data/seed-delivery-routes.sh가 제공한 실제 Hub 경로 스냅샷을 사용한다.
BEGIN;
LOCK TABLE delivery.p_deliveries IN SHARE MODE;
LOCK TABLE delivery.p_delivery_routes IN SHARE ROW EXCLUSIVE MODE;
SELECT length(set_config('logistics.dev_hub_routes', :'hub_routes', true)) > 0 AS hub_snapshot_loaded;

DO 'DECLARE
    leg record;
    found_delivery boolean := false;
BEGIN
    IF EXISTS (
        SELECT 1 FROM delivery.p_delivery_routes r
        WHERE r.delivery_route_id IN (
            SELECT (''c0ffee39-0007-4000-8000-'' || lpad((n * 10 + s)::text, 12, ''0''))::uuid
            FROM generate_series(1, 31) AS n CROSS JOIN generate_series(1, 2) AS s
        ) AND (r.created_by IS NULL OR r.created_by <> ''dev-seed'')
    ) THEN
        RAISE EXCEPTION ''Reserved development route belongs to another actor'';
    END IF;

    FOR leg IN
        WITH hub_routes AS (
            SELECT * FROM jsonb_to_recordset(current_setting(''logistics.dev_hub_routes'')::jsonb)
            AS r(departure_hub_id uuid, arrival_hub_id uuid, distance_km numeric(12,3), duration_minutes bigint)
        ), samples AS (
            SELECT d.*, n,
                   CASE WHEN n % 4 = 1 AND d.departure_hub_id <> d.destination_hub_id THEN (
                       SELECT r.departure_hub_id FROM hub_routes r
                       WHERE r.departure_hub_id NOT IN (d.departure_hub_id, d.destination_hub_id)
                       ORDER BY r.departure_hub_id LIMIT 1
                   ) END AS middle_hub_id
            FROM generate_series(1, 31) AS n
            JOIN delivery.p_deliveries d
              ON d.delivery_id = (''c0ffee39-0004-4000-8000-'' || lpad(n::text, 12, ''0''))::uuid
            WHERE d.created_by = ''dev-seed''
        ), legs AS (
            SELECT (''c0ffee39-0007-4000-8000-'' || lpad((n * 10 + s.sequence)::text, 12, ''0''))::uuid AS id,
                   d.delivery_id, s.sequence, s.departure_hub_id, s.arrival_hub_id,
                   CASE WHEN n BETWEEN 21 AND 25 AND d.deleted_at IS NOT NULL THEN ''CANCELLED''
                        WHEN d.status = ''HUB_WAITING'' THEN ''WAITING''
                        WHEN d.status = ''HUB_MOVING'' AND s.sequence = CASE WHEN middle_hub_id IS NULL THEN 1 ELSE 2 END THEN ''IN_TRANSIT''
                        ELSE ''ARRIVED'' END AS route_status,
                   d.delivery_manager_id, d.created_at, d.updated_at, d.deleted_at, d.deleted_by
            FROM samples d
            CROSS JOIN LATERAL (VALUES
                (1, d.departure_hub_id, COALESCE(d.middle_hub_id, d.destination_hub_id)),
                (2, d.middle_hub_id, d.destination_hub_id)
            ) AS s(sequence, departure_hub_id, arrival_hub_id)
            WHERE s.sequence = 1 OR d.middle_hub_id IS NOT NULL
        )
        SELECT legs.*, r.distance_km, r.duration_minutes
        FROM legs LEFT JOIN hub_routes r USING (departure_hub_id, arrival_hub_id)
        ORDER BY legs.delivery_id, legs.sequence
    LOOP
        found_delivery := true;
        IF leg.distance_km IS NULL OR leg.duration_minutes IS NULL
           OR leg.distance_km < 0 OR leg.duration_minutes < 0 THEN
            RAISE EXCEPTION ''Missing Hub route or invalid estimate for a development delivery leg'';
        END IF;
        IF EXISTS (
            SELECT 1 FROM delivery.p_delivery_routes r
            WHERE r.delivery_id = leg.delivery_id AND r.sequence = leg.sequence
              AND (r.created_by IS NULL OR r.created_by <> ''dev-seed'')
        ) THEN
            RAISE EXCEPTION ''Reserved development delivery/sequence belongs to another actor'';
        END IF;
        INSERT INTO delivery.p_delivery_routes (
            delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, expected_duration_seconds, actual_distance_km, actual_duration_seconds,
            status, assignee_id, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
        ) VALUES (
            leg.id, leg.delivery_id, leg.sequence, leg.departure_hub_id, leg.arrival_hub_id,
            leg.distance_km, leg.duration_minutes * 60,
            CASE WHEN leg.route_status = ''ARRIVED'' THEN leg.distance_km END,
            CASE WHEN leg.route_status = ''ARRIVED'' THEN leg.duration_minutes * 60 END,
            leg.route_status, leg.delivery_manager_id, leg.created_at, ''dev-seed'', leg.updated_at, ''dev-seed'',
            leg.deleted_at, leg.deleted_by
        ) ON CONFLICT DO NOTHING;
    END LOOP;
    IF NOT found_delivery THEN
        RAISE EXCEPTION ''Development deliveries must be loaded before their routes'';
    END IF;
END';
COMMIT;
