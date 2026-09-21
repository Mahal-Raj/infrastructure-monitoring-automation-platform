#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://127.0.0.1:8080}"
node_id="${2:-local}"
operator_token="${OPERATOR_TOKEN:-}"

require_text() {
  local content="$1"
  local expected="$2"
  if [[ "$content" != *"$expected"* ]]; then
    printf 'Expected response to contain %s\n' "$expected" >&2
    exit 1
  fi
}

health="$(curl --fail --silent "$base_url/api/v1/health")"
require_text "$health" '"status":"UP"'

collection="$(curl --fail --silent --request POST "$base_url/api/v1/nodes/$node_id/collect")"
require_text "$collection" '"collected":true'

availability="$(curl --fail --silent "$base_url/api/v1/nodes/$node_id/availability")"
require_text "$availability" '"totalSamples":'

report="$(curl --fail --silent "$base_url/api/v1/reports/operations")"
require_text "$report" '"reportType":"infrastructure-operations"'

if [[ -n "$operator_token" ]]; then
  action="$(curl --fail --silent --request POST "$base_url/api/v1/nodes/$node_id/actions" \
    --header "Authorization: Bearer $operator_token" \
    --header 'Content-Type: application/json' \
    --data '{"action":"disk_usage","dryRun":false}')"
  require_text "$action" '"action":"disk_usage"'
fi

printf 'PASS: controller API workflow validated for node %s.\n' "$node_id"
