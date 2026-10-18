#!/usr/bin/env python3
"""Verify readiness, isolated targets, and cleanup after real-service fault checks."""
import importlib.util
from contextlib import nullcontext
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("orders_flow", Path(__file__).with_name("check-orders-flow.py"))
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)
spec = importlib.util.spec_from_file_location("orders_recovery", Path(__file__).with_name("check-orders-recovery.py"))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def error(status, code):
    return json.dumps({"code": code}) + "\n" + str(status)


class OrdersFlowPreflightTest(unittest.TestCase):
    def test_accepted_creation_waits_for_confirmation(self):
        replies = [(200, {"data": {"status": "RECOVERY_REQUIRED"}}),
                   (200, {"data": {"status": "CONFIRMED"}})]
        with patch.object(flow, "request", side_effect=replies), patch.object(flow.time, "sleep"):
            self.assertEqual(flow.confirmed("http://unused.invalid", "/api/orders/id", "token")["status"], "CONFIRMED")

    def test_failed_creation_is_not_treated_as_processing(self):
        with patch.object(flow, "request", return_value=(200, {"data": {"status": "FAILED"}})):
            with self.assertRaisesRegex(AssertionError, "주문 확정 실패"):
                flow.confirmed("http://unused.invalid", "/api/orders/id", "token")

    def test_missing_delivery_route_stops_before_login_or_order_creation(self):
        compose = Mock(return_value=error(404, "NOT_FOUND"))
        token = Mock()
        with patch.dict(flow.DEV, {"compose": compose, "token": token}):
            with self.assertRaisesRegex(RuntimeError, "Delivery 내부 API 미준비"):
                flow.check("http://unused.invalid")
        token.assert_not_called()
        self.assertEqual(compose.call_count, 1)

    def test_missing_cancellation_also_stops_before_login_or_order_creation(self):
        compose = Mock(side_effect=[error(404, "DELIVERY_NOT_FOUND"),
                                    error(400, "INVALID_REQUEST"), error(404, "NOT_FOUND")])
        token = Mock()
        with patch.dict(flow.DEV, {"compose": compose, "token": token}):
            with self.assertRaisesRegex(RuntimeError, "/cancel"):
                flow.check("http://unused.invalid")
        token.assert_not_called()
        self.assertEqual(compose.call_count, 3)

    def test_service_authentication_failure_stops_before_writing(self):
        with patch.dict(flow.DEV, {"compose": Mock(return_value=error(401, "UNAUTHORIZED"))}):
            with self.assertRaisesRegex(RuntimeError, "HTTP 401"):
                flow.require_delivery_apis()

    def test_all_four_probes_accept_business_errors_for_nonexistent_identifiers(self):
        compose = Mock(side_effect=[error(404, "DELIVERY_NOT_FOUND"), error(400, "INVALID_REQUEST"),
                                    error(404, "DELIVERY_NOT_FOUND"), error(404, "DELIVERY_NOT_FOUND")])
        with patch.dict(flow.DEV, {"compose": compose}):
            flow.require_delivery_apis()
        self.assertEqual(compose.call_count, 4)
        self.assertIsNone(flow.expect((204, None), 204))


class OrdersRecoveryTest(unittest.TestCase):
    def test_default_project_is_rejected_before_any_service_call(self):
        for project in ("", "logistics-msa", "another-existing-stack"):
            ready = Mock()
            with self.subTest(project=project), patch.dict(recovery.os.environ, {"COMPOSE_PROJECT_NAME": project}):
                with patch.dict(recovery.FLOW, {"require_delivery_apis": ready}):
                    with self.assertRaisesRegex(RuntimeError, "전용 환경"):
                        recovery.check("http://unused.invalid")
            ready.assert_not_called()

    def test_other_gateway_is_rejected_before_any_service_call(self):
        ready = Mock()
        with patch.dict(recovery.os.environ, {"COMPOSE_PROJECT_NAME": "logistics-orders-recovery-test"}), \
                patch.dict(recovery.DEV, {"compose": Mock(return_value="127.0.0.1:18085")}), \
                patch.dict(recovery.FLOW, {"require_delivery_apis": ready}):
            with self.assertRaisesRegex(RuntimeError, "같은 전용 Compose"):
                recovery.check("http://127.0.0.1:8080")
        ready.assert_not_called()

    def test_local_gateway_port_must_belong_to_the_selected_project(self):
        with patch.dict(recovery.os.environ, {"COMPOSE_PROJECT_NAME": "logistics-orders-recovery-test"}), \
                patch.dict(recovery.DEV, {"compose": Mock(return_value="127.0.0.1:18085")}):
            recovery.dedicated_project("http://localhost:18085")

    def test_injection_cancels_only_its_own_session_even_when_assertions_fail(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(recovery.subprocess, "Popen", return_value=process), \
                patch.object(recovery, "sql", side_effect=["t", "t", "0"]) as database:
            with self.assertRaisesRegex(ValueError, "scenario failed"):
                with recovery.delayed_commit("delivery"):
                    raise ValueError("scenario failed")
        label = database.call_args_list[0].args[1].split("application_name='")[1].split("'")[0]
        self.assertTrue(label.startswith("orders-recovery-"))
        self.assertIn(f"application_name='{label}'", database.call_args_list[1].args[1])
        self.assertIn("pg_cancel_backend", database.call_args_list[1].args[1])
        process.communicate.assert_called_once_with(timeout=10)

    def test_injection_process_is_reaped_when_session_cancellation_fails(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(recovery.subprocess, "Popen", return_value=process), \
                patch.object(recovery, "sql", side_effect=["t", RuntimeError("database unavailable")]):
            with self.assertRaisesRegex(RuntimeError, "database unavailable"):
                with recovery.delayed_commit("hub"):
                    pass
        process.communicate.assert_called_once_with(timeout=10)

    def test_leftover_injection_session_is_not_reported_as_success(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(recovery.subprocess, "Popen", return_value=process), \
                patch.object(recovery, "sql", side_effect=["t", "t", "1"]):
            with self.assertRaisesRegex(AssertionError, "세션이 남아"):
                with recovery.delayed_commit("hub"):
                    pass

    def test_failure_after_stopping_orders_starts_it_again(self):
        replies = [(200, {"data": {"quantity": 100}}),
                   (202, {"data": {"order_id": "order-id"}})]
        rows = [[{"status": "RECOVERY_REQUIRED", "recovery_action": "CREATE"}],
                RuntimeError("commit check failed")]
        with patch.object(recovery, "request", side_effect=replies), \
                patch.object(recovery, "delayed_commit", return_value=nullcontext()), \
                patch.object(recovery, "assert_insert_waiting"), \
                patch.object(recovery, "rows_for", side_effect=rows), \
                patch.dict(recovery.DEV, {"compose": Mock()}), \
                patch("builtins.print"), \
                patch.object(recovery, "start_orders") as restart:
            with self.assertRaisesRegex(RuntimeError, "commit check failed"):
                recovery.check_case("http://unused.invalid", "owner", "master", restoration=False)
        restart.assert_called_once_with()

    def test_restart_polling_tolerates_gateway_unavailability_then_checks_same_order(self):
        replies = [(503, {"code": "SERVICE_UNAVAILABLE"}),
                   (200, {"data": {"status": "RECOVERY_REQUIRED"}}),
                   (200, {"data": {"status": "CANCELLED", "order_id": "same-order"}})]
        with patch.object(recovery, "request", side_effect=replies), patch.object(recovery.time, "sleep"):
            order = recovery.recovered("http://unused.invalid", "/api/orders/same-order", "owner", "CANCELLED")
        self.assertEqual(order["order_id"], "same-order")

    def test_failed_order_does_not_pass_as_automatic_recovery(self):
        with patch.object(recovery, "request", return_value=(200, {"data": {"status": "FAILED"}})):
            with self.assertRaisesRegex(AssertionError, "FAILED"):
                recovery.recovered("http://unused.invalid", "/api/orders/id", "owner", "CONFIRMED")


if __name__ == "__main__":
    unittest.main()
