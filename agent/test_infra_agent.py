from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

from infra_agent import (
    AdminExecutor,
    CommandResult,
    MetricCollector,
    Settings,
    create_server,
    redact,
)


class FakeRunner:
    def __init__(self) -> None:
        self.commands: list[tuple[str, ...]] = []

    def run(self, command: tuple[str, ...]) -> CommandResult:
        args = tuple(command)
        self.commands.append(args)
        if args[:2] == ("systemctl", "is-active"):
            return CommandResult(args, 0, "active\n", "", 3)
        return CommandResult(args, 0, "ok\n", "", 4)


def settings(port: int = 0) -> Settings:
    return Settings(
        bind="127.0.0.1",
        port=port,
        node_id="test-node",
        token="test-agent-token",
        monitored_services=("nginx",),
        monitored_endpoints={},
        managed_services=frozenset({"nginx"}),
        log_units=frozenset({"nginx"}),
        allow_mutations=False,
        command_timeout_seconds=2,
    )


class AgentUnitTests(unittest.TestCase):
    def test_metrics_have_operational_fields(self) -> None:
        snapshot = MetricCollector(settings(), FakeRunner()).collect()
        self.assertEqual("test-node", snapshot["nodeId"])
        self.assertIn("cpu", snapshot)
        self.assertIn("memory", snapshot)
        self.assertIn("disk", snapshot)
        self.assertIn("network", snapshot)
        self.assertTrue(snapshot["services"][0]["available"])

    def test_redaction_removes_common_secrets(self) -> None:
        text = "token=abc123 password: swordfish Authorization=Bearer-value Bearer real-token"
        cleaned = redact(text)
        self.assertNotIn("abc123", cleaned)
        self.assertNotIn("swordfish", cleaned)
        self.assertNotIn("real-token", cleaned)
        self.assertGreaterEqual(cleaned.count("[REDACTED]"), 3)

    def test_unknown_action_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "action must be one of"):
            AdminExecutor(settings(), FakeRunner()).execute({"action": "run_shell"})

    def test_unlisted_service_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "not allowlisted"):
            AdminExecutor(settings(), FakeRunner()).execute(
                {"action": "service_status", "target": "database"}
            )

    def test_restart_defaults_to_safe_dry_run(self) -> None:
        runner = FakeRunner()
        result = AdminExecutor(settings(), runner).execute(
            {"action": "restart_service", "target": "nginx"}
        )
        self.assertTrue(result["dryRun"])
        self.assertFalse(result["executed"])
        self.assertEqual([], runner.commands)
        self.assertEqual(["sudo", "-n", "systemctl", "restart", "nginx"], result["result"]["command"])


class AgentHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = FakeRunner()
        cls.server = create_server(settings(), cls.runner)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def request(
        self,
        path: str,
        *,
        method: str = "GET",
        token: str | None = None,
        payload: dict[str, object] | None = None,
    ) -> tuple[int, dict[str, object]]:
        headers: dict[str, str] = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_health_is_available_without_token(self) -> None:
        status, payload = self.request("/health")
        self.assertEqual(200, status)
        self.assertEqual("UP", payload["status"])

    def test_metrics_require_agent_token(self) -> None:
        status, _ = self.request("/metrics")
        self.assertEqual(401, status)
        status, payload = self.request("/metrics", token="test-agent-token")
        self.assertEqual(200, status)
        self.assertEqual("test-node", payload["nodeId"])

    def test_action_endpoint_enforces_allowlist_and_dry_run(self) -> None:
        status, payload = self.request(
            "/actions",
            method="POST",
            token="test-agent-token",
            payload={"action": "restart_service", "target": "nginx", "dryRun": True},
        )
        self.assertEqual(200, status)
        self.assertFalse(payload["executed"])
        self.assertTrue(payload["dryRun"])

        status, _ = self.request(
            "/actions",
            method="POST",
            token="test-agent-token",
            payload={"action": "restart_service", "target": "database", "dryRun": True},
        )
        self.assertEqual(400, status)


if __name__ == "__main__":
    unittest.main(verbosity=2)
