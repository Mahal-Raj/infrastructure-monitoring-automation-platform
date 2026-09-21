#!/usr/bin/env python3
"""Export the controller operations report as JSON and concise Markdown."""

from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Sequence


def fetch_report(controller: str, timeout: int = 5) -> Mapping[str, Any]:
    url = controller.rstrip("/") + "/api/v1/reports/operations"
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "report-exporter/1.0"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read())
    if not isinstance(payload, dict) or payload.get("reportType") != "infrastructure-operations":
        raise ValueError("Controller returned an unexpected report document")
    return payload


def markdown(report: Mapping[str, Any]) -> str:
    lines = [
        "# Infrastructure Operations Report",
        "",
        f"Generated: `{report.get('generatedAt', 'unknown')}`",
        "",
        "| Node | State | Availability | Last success | CPU | Memory | Disk |",
        "|---|---:|---:|---|---:|---:|---:|",
    ]
    for node in report.get("nodes", []):
        status = node.get("status", {})
        availability = node.get("availability", {})
        latest = node.get("latestMetrics") or {}
        metrics = latest.get("metrics", {}) if isinstance(latest, dict) else {}
        cpu = metrics.get("cpu", {}) if isinstance(metrics, dict) else {}
        memory = metrics.get("memory", {}) if isinstance(metrics, dict) else {}
        disk = metrics.get("disk", {}) if isinstance(metrics, dict) else {}
        lines.append(
            "| {name} | {state} | {availability} | {last_success} | {cpu} | {memory} | {disk} |".format(
                name=status.get("name", status.get("id", "unknown")),
                state="online" if status.get("online") else "offline",
                availability=percentage(availability.get("availabilityPercent")),
                last_success=status.get("lastSuccessAt") or "never",
                cpu=percentage(cpu.get("usagePercent")),
                memory=percentage(memory.get("usedPercent")),
                disk=percentage(disk.get("usedPercent")),
            )
        )
    lines.extend(["", "## Follow-up", ""])
    offline = [node for node in report.get("nodes", []) if not node.get("status", {}).get("online")]
    if offline:
        for node in offline:
            status = node.get("status", {})
            lines.append(f"- Investigate `{status.get('id', 'unknown')}`: {status.get('latestError') or 'latest collection failed'}. ")
    else:
        lines.append("- All registered nodes completed their latest collection successfully.")
    return "\n".join(lines) + "\n"


def percentage(value: object) -> str:
    return "n/a" if value is None else f"{float(value):.2f}%"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--controller", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, default=Path("out/operations-report"))
    args = parser.parse_args(argv)
    try:
        report = fetch_report(args.controller)
    except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
        parser.error(f"could not fetch report: {exc}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    json_path = args.output.with_suffix(".json")
    markdown_path = args.output.with_suffix(".md")
    json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown(report), encoding="utf-8")
    print(f"JSON report: {json_path.resolve()}")
    print(f"Markdown report: {markdown_path.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
