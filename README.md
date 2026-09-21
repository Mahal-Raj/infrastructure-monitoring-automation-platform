# Infrastructure Monitoring and Automation Platform

A centralized operations platform that polls lightweight Linux agents, tracks service availability, collects bounded diagnostic evidence, runs allowlisted administrative workflows, and exports structured operational reports.

The project combines a dependency-free Java 17 controller with Python agents, Linux/Bash tooling, authenticated REST APIs, functional tests, Docker-based multi-node demos, and GitHub Actions CI.

## One-minute architecture

```text
                           Java 17 controller
                         http://localhost:8080
                        /          |           \
             metrics history   action proxy   reports
                    /               |              \
        Python agent east     Python agent west    JSON/Markdown
          Linux node A          Linux node B       operations report
        /proc + systemd       /proc + systemd
        journalctl/logs       journalctl/logs
```

The controller polls every configured node concurrently and keeps a rolling in-memory availability window. Agents collect host metrics from Linux interfaces, check HTTP and systemd services, expose bounded log retrieval, and execute only explicitly allowlisted actions. Mutating actions are disabled unless an operator enables them.

## What is implemented

- Java 17 REST controller with concurrent polling and graceful shutdown.
- Multi-node inventory loaded from CSV without storing agent tokens in source control.
- Latest CPU, memory, disk, load, network, process, uptime, and service-health snapshots.
- Rolling success-rate and last-seen availability state for every registered node.
- Python REST agent using only the standard library.
- Authenticated log collection with unit allowlists, line limits, timeouts, and secret redaction.
- Administrative workflows for service status, disk usage, process snapshots, and gated service restarts.
- JSON operations reports plus a Python exporter that renders Markdown handoff reports.
- Bash installation, snapshot, demo, and API validation utilities.
- Java and Python functional tests, shell validation, Docker images, Compose demo, and CI.

## API surface

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/v1/health` | Controller health and inventory count |
| `GET` | `/api/v1/nodes` | Node state, last-seen time, and availability |
| `POST` | `/api/v1/nodes/{id}/collect` | Trigger an immediate metrics collection |
| `GET` | `/api/v1/nodes/{id}/metrics/latest` | Retrieve the latest agent snapshot |
| `GET` | `/api/v1/nodes/{id}/availability` | Retrieve the rolling availability summary |
| `GET` | `/api/v1/nodes/{id}/logs` | Retrieve bounded, redacted service logs |
| `POST` | `/api/v1/nodes/{id}/actions` | Run an allowlisted administrative workflow |
| `GET` | `/api/v1/reports/operations` | Generate a platform-wide JSON report |

Log and action routes require the controller operator token. The controller authenticates separately to every agent. See [API examples](docs/api.md) and the [security model](docs/security.md).

## Run the multi-node demo

Requirements: Docker Desktop with Linux containers.

```bash
docker compose up --build -d
curl http://localhost:8080/api/v1/health
curl -X POST http://localhost:8080/api/v1/nodes/east/collect
curl http://localhost:8080/api/v1/nodes
curl http://localhost:8080/api/v1/reports/operations
docker compose down
```

The Compose stack starts two independent Python agents and one Java controller. The tokens in `docker-compose.yml` are demo-only values and must not be reused outside a local lab.

## Run from source

### Windows PowerShell

```powershell
.\scripts\test.ps1
.\scripts\run-local-demo.ps1
```

### Linux

```bash
bash scripts/test.sh
bash scripts/run-demo.sh
```

The local demo uses [config/nodes.local.csv](config/nodes.local.csv) and binds the agent and controller to loopback interfaces.

## Example administrative request

Read-only dry run:

```bash
curl -X POST http://localhost:8080/api/v1/nodes/east/actions \
  -H 'Authorization: Bearer demo-operator-token' \
  -H 'Content-Type: application/json' \
  -d '{"action":"restart_service","target":"nginx","dryRun":true}'
```

The agent rejects unknown actions and targets. A real restart additionally requires `ALLOW_MUTATIONS=true` on the agent and `dryRun:false` in the request.

## Verification

```bash
bash scripts/test.sh
docker compose config
docker compose build
```

The test suite exercises controller routing, node polling, authorization, metrics history, reports, agent authentication, Linux metric collection, redaction, allowlists, and dry-run behavior.

## Repository map

```text
agent/          Python Linux monitoring agent and tests
config/         Local and Docker node inventories
docs/           Architecture, REST API, operations, and security notes
scripts/        Build, test, demo, install, and validation workflows
src/main/       Java 17 controller
src/test/       Java functional tests
tools/          Operational report exporter
```

## Operational boundaries

This is a production-minded portfolio implementation, not a claim that it has operated a real production fleet. Metrics history is intentionally in memory, transport security is expected to be provided by a trusted network or TLS reverse proxy, and service restarts are disabled by default. The design favors visible controls and bounded evidence over unrestricted remote shell access.

## License

MIT
