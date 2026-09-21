# Operations guide

## Inventory

Node inventory uses four CSV columns:

```text
id,name,baseUrl,tokenEnv
east,East Linux Node,http://127.0.0.1:9100,AGENT_TOKEN_EAST
```

`tokenEnv` is the name of an environment variable, not the token itself.

## Controller variables

| Variable | Default | Purpose |
|---|---|---|
| `PLATFORM_BIND` | `127.0.0.1` | Controller bind address |
| `PLATFORM_PORT` | `8080` | Controller port |
| `NODE_CONFIG` | `config/nodes.local.csv` | Inventory file |
| `POLL_INTERVAL_SECONDS` | `30` | Collection interval |
| `REQUEST_TIMEOUT_SECONDS` | `5` | Agent HTTP timeout |
| `AVAILABILITY_WINDOW` | `120` | Samples retained per node |
| `OPERATOR_TOKEN` | empty | Enables protected controller routes |

## Agent variables

| Variable | Default | Purpose |
|---|---|---|
| `AGENT_BIND` | `127.0.0.1` | Agent bind address |
| `AGENT_PORT` | `9100` | Agent port |
| `AGENT_TOKEN` | empty | Protects non-health routes |
| `NODE_ID` | hostname | Stable node identifier |
| `MONITORED_SERVICES` | empty | Comma-separated systemd units |
| `MONITORED_ENDPOINTS` | empty | `name=url` pairs separated by commas |
| `MANAGED_SERVICES` | empty | Allowlist for status/restart actions |
| `LOG_UNITS` | empty | Allowlist for journal retrieval |
| `ALLOW_MUTATIONS` | `false` | Enables explicitly requested restarts |

The systemd installer creates a dedicated `infra-agent` account. Enabling a real restart also requires reviewing and installing a narrow rule based on `deploy/infra-agent.sudoers.example`; the installer deliberately does not grant restart privileges by itself.

## Incident workflow

1. Check `/api/v1/health` to confirm the controller is running.
2. Review `/api/v1/nodes` for failed samples and last-seen times.
3. Trigger `/collect` to distinguish stale evidence from a current failure.
4. Inspect the latest metrics and availability window.
5. Retrieve bounded service logs using operator authentication.
6. Run read-only actions or a restart dry run.
7. Export `/api/v1/reports/operations` for the incident handoff.

Every action should preserve the node identifier, request time, action, target, dry-run state, and result in an external audit sink for production use.
