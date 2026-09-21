#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="$project_root/out"
mkdir -p "$output_dir"
"$project_root/scripts/build.sh"

export AGENT_BIND=127.0.0.1
export AGENT_PORT=9100
export NODE_ID=local
export AGENT_TOKEN=demo-agent-token
export AGENT_TOKEN_LOCAL=demo-agent-token
export OPERATOR_TOKEN=demo-operator-token
export NODE_CONFIG="$project_root/config/nodes.local.csv"
export PLATFORM_BIND=127.0.0.1
export PLATFORM_PORT=8080
export POLL_INTERVAL_SECONDS=5
export MANAGED_SERVICES=nginx
export LOG_UNITS=nginx
export ALLOW_MUTATIONS=false

python3 "$project_root/agent/infra_agent.py" >"$output_dir/agent.stdout.log" 2>"$output_dir/agent.stderr.log" &
agent_pid=$!
java --add-modules jdk.httpserver -cp "$project_root/build/classes" com.sukhraj.infra.PlatformApplication >"$output_dir/controller.stdout.log" 2>"$output_dir/controller.stderr.log" &
controller_pid=$!

cleanup() {
  kill "$controller_pid" "$agent_pid" 2>/dev/null || true
  wait "$controller_pid" "$agent_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in {1..30}; do
  if curl --fail --silent http://127.0.0.1:8080/api/v1/health >/dev/null; then
    break
  fi
  sleep 0.2
done

curl --fail --silent --request POST http://127.0.0.1:8080/api/v1/nodes/local/collect
printf '\n'
curl --fail --silent http://127.0.0.1:8080/api/v1/nodes
printf '\nDemo is running at http://127.0.0.1:8080. Press Ctrl+C to stop.\n'
wait "$controller_pid"
