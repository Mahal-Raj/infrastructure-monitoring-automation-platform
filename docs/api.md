# REST API

The controller defaults to `http://127.0.0.1:8080`. JSON responses include an ISO-8601 UTC timestamp where the event time matters.

## Public operational endpoints

### Controller health

```http
GET /api/v1/health
```

### Node inventory

```http
GET /api/v1/nodes
```

### Immediate collection

```http
POST /api/v1/nodes/east/collect
```

### Latest metrics

```http
GET /api/v1/nodes/east/metrics/latest
```

### Availability

```http
GET /api/v1/nodes/east/availability
```

### Operations report

```http
GET /api/v1/reports/operations
```

## Operator endpoints

Operator routes require `Authorization: Bearer <OPERATOR_TOKEN>`.

### Logs

```http
GET /api/v1/nodes/east/logs?unit=nginx&lines=50
Authorization: Bearer demo-operator-token
```

`unit` must be in the agent's `LOG_UNITS` allowlist. `lines` is clamped to `1..200`.

### Administrative action

```http
POST /api/v1/nodes/east/actions
Authorization: Bearer demo-operator-token
Content-Type: application/json

{"action":"service_status","target":"nginx","dryRun":false}
```

Supported actions:

- `service_status` for configured services;
- `restart_service` for configured services, dry-run by default;
- `disk_usage` with no target;
- `process_snapshot` with no target.

The controller proxies the validated JSON body to the selected agent. The agent performs the final action/target authorization.

## Status codes

| Code | Meaning |
|---|---|
| `200` | Request completed |
| `400` | Invalid path, query, or JSON request |
| `401` | Missing or invalid token |
| `404` | Node or route not found |
| `405` | Method not allowed |
| `502` | Agent could not complete the request |
| `503` | Protected routes are disabled because no operator token is configured |
