# Architecture

## Component flow

```mermaid
flowchart LR
    O[Operator or automation] -->|REST + operator token| C[Java 17 controller]
    C -->|scheduled GET /metrics| A1[Python agent: node east]
    C -->|scheduled GET /metrics| A2[Python agent: node west]
    C -->|bounded proxy requests| A1
    C -->|bounded proxy requests| A2
    A1 --> P1[/proc and statvfs]
    A1 --> S1[systemctl]
    A1 --> J1[journalctl]
    A2 --> P2[/proc and statvfs]
    A2 --> S2[systemctl]
    A2 --> J2[journalctl]
    C --> H[rolling availability history]
    C --> R[JSON operations report]
    R --> M[Python Markdown exporter]
```

## Controller responsibilities

The Java controller owns inventory, scheduling, concurrency, the platform API, rolling availability, and report aggregation. It never opens an unrestricted shell. Agent responses are treated as evidence and returned as nested JSON.

Each poll records whether the agent request succeeded, request latency, collection time, and the raw structured snapshot. The rolling window is bounded so long-running controller processes do not grow without limit.

## Agent responsibilities

The Python agent owns host-local observation and command execution. It reads standard Linux interfaces such as `/proc`, uses `statvfs`-backed disk information, queries explicitly configured HTTP/systemd services, and invokes `journalctl` with fixed argument arrays.

Administrative actions use a two-stage gate:

1. the action and target must be allowlisted;
2. mutating execution requires both agent configuration and an explicit non-dry-run request.

No handler passes user input through a shell.

## Failure behavior

- A node timeout affects only that node and becomes a failed availability sample.
- The latest successful snapshot remains visible with its timestamp.
- Agent errors are returned with bounded messages and do not stop scheduled polling.
- Log lines and request bodies are capped.
- The controller and agent use explicit HTTP and command timeouts.

## Deliberate tradeoffs

- In-memory history keeps the example dependency-free; a production deployment would persist time series and audit events.
- CSV inventory is easy to review; a larger installation would use service discovery or a configuration database.
- Bearer tokens demonstrate separate operator and node trust boundaries; production traffic should also use TLS and managed secrets.
