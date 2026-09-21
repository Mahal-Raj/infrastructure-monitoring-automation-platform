# Security model

## Trust boundaries

- Operators authenticate to the central controller with `OPERATOR_TOKEN`.
- The controller authenticates to each node using the environment-variable name stored in inventory.
- Agent tokens are read from the environment and are never written to node CSV files or reports.
- The health endpoint is intentionally public for liveness checks. Agent metrics, logs, services, and actions require an agent token when configured.

## Command safety

The Python agent does not expose a shell endpoint. Administrative requests map to fixed command templates, use argument arrays with `shell=False`, apply timeouts, and validate service names against `MANAGED_SERVICES`.

`restart_service` is a mutating action and has three controls:

1. valid agent authentication;
2. an allowlisted target;
3. `ALLOW_MUTATIONS=true` plus `dryRun:false`.

The default is a dry-run response describing the approved command without running it. Real restarts use `sudo -n` and therefore also require a narrowly scoped sudoers rule such as [the reviewed example](../deploy/infra-agent.sudoers.example). The installer does not grant that permission automatically.

## Log safety

- Only configured journal units can be queried.
- Line counts are capped at 200.
- Collection commands have timeouts.
- Common password, token, API-key, and authorization values are redacted.
- Response and request sizes are bounded.

## Deployment guidance

- Bind agents to a private interface or loopback and place cross-host traffic behind TLS/mTLS.
- Store tokens in a secret manager or orchestrator secret, not `.env` committed to Git.
- Run the agent under a dedicated account. Grant narrowly scoped `sudoers` permissions only for explicitly required services; keep `ALLOW_MUTATIONS=false` until that rule has been reviewed.
- Send action audit events to durable centralized storage.
- Replace the in-memory metrics window with a persistent time-series backend for production retention.
