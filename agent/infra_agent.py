#!/usr/bin/env python3
"""Authenticated Linux monitoring and automation agent.

The agent intentionally uses only Python's standard library so it can be
deployed to a small Linux host without a package installation step.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Mapping, Sequence
from urllib.parse import parse_qs, urlsplit

VERSION = "1.0.0"
MAX_BODY_BYTES = 32 * 1024
MAX_OUTPUT_CHARS = 64 * 1024


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def split_csv(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.split(",") if item.strip())


def parse_endpoints(value: str) -> dict[str, str]:
    endpoints: dict[str, str] = {}
    for item in split_csv(value):
        if "=" not in item:
            raise ValueError(f"Invalid endpoint entry: {item!r}; expected name=url")
        name, url = (part.strip() for part in item.split("=", 1))
        if not name or not url.startswith(("http://", "https://")):
            raise ValueError(f"Invalid monitored endpoint: {item!r}")
        endpoints[name] = url
    return endpoints


@dataclass(frozen=True)
class Settings:
    bind: str
    port: int
    node_id: str
    token: str
    monitored_services: tuple[str, ...]
    monitored_endpoints: Mapping[str, str]
    managed_services: frozenset[str]
    log_units: frozenset[str]
    allow_mutations: bool
    command_timeout_seconds: int = 5

    @classmethod
    def from_environment(cls) -> "Settings":
        hostname = socket.gethostname()
        return cls(
            bind=os.getenv("AGENT_BIND", "127.0.0.1"),
            port=int(os.getenv("AGENT_PORT", "9100")),
            node_id=os.getenv("NODE_ID", hostname),
            token=os.getenv("AGENT_TOKEN", ""),
            monitored_services=split_csv(os.getenv("MONITORED_SERVICES", "")),
            monitored_endpoints=parse_endpoints(os.getenv("MONITORED_ENDPOINTS", "")),
            managed_services=frozenset(split_csv(os.getenv("MANAGED_SERVICES", ""))),
            log_units=frozenset(split_csv(os.getenv("LOG_UNITS", ""))),
            allow_mutations=os.getenv("ALLOW_MUTATIONS", "false").lower() == "true",
            command_timeout_seconds=max(1, min(30, int(os.getenv("COMMAND_TIMEOUT_SECONDS", "5")))),
        )


@dataclass(frozen=True)
class CommandResult:
    command: tuple[str, ...]
    return_code: int
    stdout: str
    stderr: str
    duration_ms: int

    def as_dict(self) -> dict[str, Any]:
        return {
            "command": list(self.command),
            "returnCode": self.return_code,
            "stdout": self.stdout[:MAX_OUTPUT_CHARS],
            "stderr": self.stderr[:MAX_OUTPUT_CHARS],
            "durationMs": self.duration_ms,
        }


class CommandRunner:
    def __init__(self, timeout_seconds: int) -> None:
        self.timeout_seconds = timeout_seconds

    def run(self, command: Sequence[str]) -> CommandResult:
        args = tuple(str(part) for part in command)
        started = time.monotonic()
        executable = shutil.which(args[0])
        if executable is None:
            return CommandResult(args, 127, "", f"{args[0]} is not installed", 0)
        try:
            completed = subprocess.run(
                args,
                capture_output=True,
                text=True,
                timeout=self.timeout_seconds,
                check=False,
                shell=False,
            )
            return CommandResult(
                args,
                completed.returncode,
                completed.stdout[:MAX_OUTPUT_CHARS],
                completed.stderr[:MAX_OUTPUT_CHARS],
                int((time.monotonic() - started) * 1000),
            )
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout if isinstance(exc.stdout, str) else ""
            stderr = exc.stderr if isinstance(exc.stderr, str) else ""
            return CommandResult(
                args,
                124,
                stdout[:MAX_OUTPUT_CHARS],
                (stderr + f"\ncommand timed out after {self.timeout_seconds}s").strip(),
                int((time.monotonic() - started) * 1000),
            )


class MetricCollector:
    def __init__(self, settings: Settings, runner: CommandRunner) -> None:
        self.settings = settings
        self.runner = runner

    def collect(self) -> dict[str, Any]:
        disk = shutil.disk_usage(Path.home().anchor or "/")
        memory = self._memory()
        load = self._load_average()
        return {
            "nodeId": self.settings.node_id,
            "hostname": socket.gethostname(),
            "collectedAt": utc_now(),
            "platform": {
                "system": platform.system(),
                "release": platform.release(),
                "machine": platform.machine(),
                "python": platform.python_version(),
            },
            "uptimeSeconds": self._uptime_seconds(),
            "cpu": {
                "logicalCores": os.cpu_count() or 1,
                "usagePercent": self._cpu_usage_percent(),
                "load1": load[0],
                "load5": load[1],
                "load15": load[2],
            },
            "memory": memory,
            "disk": {
                "path": Path.home().anchor or "/",
                "totalBytes": disk.total,
                "usedBytes": disk.used,
                "freeBytes": disk.free,
                "usedPercent": round((disk.used / disk.total) * 100, 2) if disk.total else 0.0,
            },
            "network": self._network_totals(),
            "processCount": self._process_count(),
            "services": self.collect_services(),
        }

    def collect_services(self) -> list[dict[str, Any]]:
        checks: list[dict[str, Any]] = []
        for service in self.settings.monitored_services:
            result = self.runner.run(("systemctl", "is-active", service))
            active = result.return_code == 0 and result.stdout.strip() == "active"
            checks.append(
                {
                    "name": service,
                    "kind": "systemd",
                    "available": active,
                    "detail": result.stdout.strip() or result.stderr.strip(),
                    "durationMs": result.duration_ms,
                }
            )
        for name, url in self.settings.monitored_endpoints.items():
            started = time.monotonic()
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "infra-agent/1.0"})
                with urllib.request.urlopen(request, timeout=self.settings.command_timeout_seconds) as response:
                    status = response.status
                checks.append(
                    {
                        "name": name,
                        "kind": "http",
                        "available": 200 <= status < 400,
                        "detail": f"HTTP {status}",
                        "durationMs": int((time.monotonic() - started) * 1000),
                    }
                )
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                checks.append(
                    {
                        "name": name,
                        "kind": "http",
                        "available": False,
                        "detail": f"{type(exc).__name__}: {exc}",
                        "durationMs": int((time.monotonic() - started) * 1000),
                    }
                )
        return checks

    def _memory(self) -> dict[str, Any]:
        meminfo = Path("/proc/meminfo")
        if meminfo.exists():
            values: dict[str, int] = {}
            for line in meminfo.read_text(encoding="utf-8", errors="replace").splitlines():
                if ":" not in line:
                    continue
                name, raw = line.split(":", 1)
                parts = raw.strip().split()
                if parts and parts[0].isdigit():
                    values[name] = int(parts[0]) * 1024
            total = values.get("MemTotal", 0)
            available = values.get("MemAvailable", values.get("MemFree", 0))
        else:
            total = 0
            available = 0
        used = max(0, total - available)
        return {
            "totalBytes": total,
            "availableBytes": available,
            "usedBytes": used,
            "usedPercent": round((used / total) * 100, 2) if total else None,
        }

    def _load_average(self) -> tuple[float | None, float | None, float | None]:
        try:
            values = os.getloadavg()
            return tuple(round(value, 2) for value in values)  # type: ignore[return-value]
        except (AttributeError, OSError):
            return (None, None, None)

    def _uptime_seconds(self) -> int | None:
        uptime = Path("/proc/uptime")
        try:
            return int(float(uptime.read_text(encoding="utf-8").split()[0]))
        except (OSError, ValueError, IndexError):
            return None

    def _cpu_times(self) -> tuple[int, int] | None:
        try:
            first = Path("/proc/stat").read_text(encoding="utf-8").splitlines()[0].split()
            if not first or first[0] != "cpu":
                return None
            values = [int(value) for value in first[1:]]
            idle = values[3] + (values[4] if len(values) > 4 else 0)
            return sum(values), idle
        except (OSError, ValueError, IndexError):
            return None

    def _cpu_usage_percent(self) -> float | None:
        before = self._cpu_times()
        if before is None:
            return None
        time.sleep(0.05)
        after = self._cpu_times()
        if after is None:
            return None
        total_delta = after[0] - before[0]
        idle_delta = after[1] - before[1]
        if total_delta <= 0:
            return 0.0
        return round((1 - idle_delta / total_delta) * 100, 2)

    def _network_totals(self) -> dict[str, int | None]:
        netdev = Path("/proc/net/dev")
        received = 0
        transmitted = 0
        try:
            for line in netdev.read_text(encoding="utf-8").splitlines()[2:]:
                _, raw = line.split(":", 1)
                fields = raw.split()
                received += int(fields[0])
                transmitted += int(fields[8])
            return {"receivedBytes": received, "transmittedBytes": transmitted}
        except (OSError, ValueError, IndexError):
            return {"receivedBytes": None, "transmittedBytes": None}

    def _process_count(self) -> int | None:
        proc = Path("/proc")
        try:
            return sum(1 for path in proc.iterdir() if path.name.isdigit())
        except OSError:
            return None


REDACTION_PATTERNS = (
    re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+"),
    re.compile(r"(?i)((?:password|passwd|token|api[_-]?key|authorization)\s*[:=]\s*)[^\s,;]+"),
)


def redact(text: str) -> str:
    output = text
    for pattern in REDACTION_PATTERNS:
        output = pattern.sub(r"\1[REDACTED]", output)
    return output


class LogCollector:
    def __init__(self, settings: Settings, runner: CommandRunner) -> None:
        self.settings = settings
        self.runner = runner

    def collect(self, unit: str, lines: int) -> dict[str, Any]:
        if unit not in self.settings.log_units:
            raise ValueError(f"Log unit {unit!r} is not allowlisted")
        bounded_lines = max(1, min(200, lines))
        result = self.runner.run(
            ("journalctl", "--no-pager", "--output=short-iso", "--unit", unit, "--lines", str(bounded_lines))
        )
        return {
            "nodeId": self.settings.node_id,
            "unit": unit,
            "requestedLines": bounded_lines,
            "collectedAt": utc_now(),
            "available": result.return_code == 0,
            "output": redact(result.stdout),
            "error": redact(result.stderr),
            "durationMs": result.duration_ms,
        }


class AdminExecutor:
    ACTIONS = frozenset({"service_status", "restart_service", "disk_usage", "process_snapshot"})

    def __init__(self, settings: Settings, runner: CommandRunner) -> None:
        self.settings = settings
        self.runner = runner

    def execute(self, request: Mapping[str, Any]) -> dict[str, Any]:
        action = request.get("action")
        target = request.get("target", "")
        dry_run = request.get("dryRun", True)
        if not isinstance(action, str) or action not in self.ACTIONS:
            raise ValueError("action must be one of: " + ", ".join(sorted(self.ACTIONS)))
        if not isinstance(target, str) or not isinstance(dry_run, bool):
            raise ValueError("target must be a string and dryRun must be a boolean")

        if action in {"service_status", "restart_service"}:
            if target not in self.settings.managed_services:
                raise ValueError(f"Service {target!r} is not allowlisted")
        elif target:
            raise ValueError(f"Action {action!r} does not accept a target")

        if action == "service_status":
            result = self.runner.run(("systemctl", "status", target, "--no-pager"))
            response = self._response(action, target, False, True, result.as_dict())
        elif action == "restart_service":
            command = ("systemctl", "restart", target)
            if dry_run or not self.settings.allow_mutations:
                reason = "request is a dry run" if dry_run else "mutating actions are disabled on this agent"
                response = self._response(
                    action,
                    target,
                    True,
                    False,
                    {"command": list(command), "reason": reason},
                )
            else:
                result = self.runner.run(command)
                response = self._response(action, target, False, True, result.as_dict())
        elif action == "disk_usage":
            result = self.runner.run(("df", "-hP"))
            response = self._response(action, "", False, True, result.as_dict())
        else:
            result = self.runner.run(("ps", "-eo", "pid,comm,%cpu,%mem", "--sort=-%cpu"))
            details = result.as_dict()
            details["stdout"] = "\n".join(result.stdout.splitlines()[:21])
            response = self._response(action, "", False, True, details)

        print(json.dumps({"event": "administrative_action", **response}, separators=(",", ":")), file=sys.stderr)
        return response

    def _response(
        self,
        action: str,
        target: str,
        dry_run: bool,
        executed: bool,
        result: Mapping[str, Any],
    ) -> dict[str, Any]:
        return {
            "nodeId": self.settings.node_id,
            "action": action,
            "target": target or None,
            "dryRun": dry_run,
            "executed": executed,
            "requestedAt": utc_now(),
            "result": dict(result),
        }


class AgentRequestHandler(BaseHTTPRequestHandler):
    server_version = "InfraAgent/1.0"

    @property
    def app(self) -> "InfraAgentServer":
        return self.server  # type: ignore[return-value]

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlsplit(self.path)
        if parsed.path == "/health":
            self._json(
                HTTPStatus.OK,
                {
                    "status": "UP",
                    "nodeId": self.app.settings.node_id,
                    "version": VERSION,
                    "time": utc_now(),
                },
            )
            return
        if not self._authorized():
            return
        if parsed.path == "/metrics":
            self._json(HTTPStatus.OK, self.app.collector.collect())
        elif parsed.path == "/services":
            self._json(
                HTTPStatus.OK,
                {
                    "nodeId": self.app.settings.node_id,
                    "collectedAt": utc_now(),
                    "services": self.app.collector.collect_services(),
                },
            )
        elif parsed.path == "/logs":
            query = parse_qs(parsed.query)
            unit = query.get("unit", [""])[0]
            try:
                lines = int(query.get("lines", ["50"])[0])
                self._json(HTTPStatus.OK, self.app.logs.collect(unit, lines))
            except (ValueError, TypeError) as exc:
                self._error(HTTPStatus.BAD_REQUEST, str(exc))
        else:
            self._error(HTTPStatus.NOT_FOUND, "route not found")

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlsplit(self.path)
        if not self._authorized():
            return
        if parsed.path != "/actions":
            self._error(HTTPStatus.NOT_FOUND, "route not found")
            return
        try:
            request = self._read_json()
            self._json(HTTPStatus.OK, self.app.actions.execute(request))
        except (ValueError, json.JSONDecodeError) as exc:
            self._error(HTTPStatus.BAD_REQUEST, str(exc))

    def do_PUT(self) -> None:  # noqa: N802
        self._error(HTTPStatus.METHOD_NOT_ALLOWED, "method not allowed")

    def do_DELETE(self) -> None:  # noqa: N802
        self._error(HTTPStatus.METHOD_NOT_ALLOWED, "method not allowed")

    def log_message(self, format_string: str, *args: Any) -> None:
        print(
            json.dumps(
                {
                    "event": "http_access",
                    "remote": self.client_address[0],
                    "request": format_string % args,
                    "time": utc_now(),
                },
                separators=(",", ":"),
            ),
            file=sys.stderr,
        )

    def _authorized(self) -> bool:
        expected = self.app.settings.token
        if not expected:
            return True
        supplied = self.headers.get("Authorization", "")
        prefix = "Bearer "
        token = supplied[len(prefix) :] if supplied.startswith(prefix) else ""
        if hmac.compare_digest(token.encode(), expected.encode()):
            return True
        self._error(HTTPStatus.UNAUTHORIZED, "missing or invalid agent token")
        return False

    def _read_json(self) -> Mapping[str, Any]:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise ValueError("invalid Content-Length") from exc
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError(f"request body must contain 1..{MAX_BODY_BYTES} bytes")
        body = self.rfile.read(length)
        decoded = json.loads(body.decode("utf-8"))
        if not isinstance(decoded, dict):
            raise ValueError("request body must be a JSON object")
        return decoded

    def _error(self, status: HTTPStatus, message: str) -> None:
        self._json(status, {"error": status.phrase, "message": message, "time": utc_now()})

    def _json(self, status: HTTPStatus, payload: Mapping[str, Any]) -> None:
        encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)


class InfraAgentServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        settings: Settings,
        runner: CommandRunner | None = None,
    ) -> None:
        super().__init__(address, AgentRequestHandler)
        self.settings = settings
        command_runner = runner or CommandRunner(settings.command_timeout_seconds)
        self.collector = MetricCollector(settings, command_runner)
        self.logs = LogCollector(settings, command_runner)
        self.actions = AdminExecutor(settings, command_runner)


def create_server(settings: Settings, runner: CommandRunner | None = None) -> InfraAgentServer:
    return InfraAgentServer((settings.bind, settings.port), settings, runner)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Linux infrastructure monitoring agent")
    parser.add_argument("--check", action="store_true", help="validate configuration and exit")
    args = parser.parse_args(argv)
    try:
        settings = Settings.from_environment()
    except (ValueError, TypeError) as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 64
    if args.check:
        print(json.dumps({"status": "configuration-valid", "nodeId": settings.node_id}))
        return 0
    server = create_server(settings)
    print(
        json.dumps(
            {
                "event": "agent_started",
                "bind": settings.bind,
                "port": settings.port,
                "nodeId": settings.node_id,
                "authenticationEnabled": bool(settings.token),
                "mutationsEnabled": settings.allow_mutations,
            },
            separators=(",", ":"),
        )
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
