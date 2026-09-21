#!/usr/bin/env bash
set -euo pipefail

output_file="${1:-linux-host-snapshot.txt}"
target_host="${2:-}"

section() {
  printf '\n===== %s =====\n' "$1" >>"$output_file"
}

capture() {
  local executable="$1"
  shift
  section "$executable $*"
  if command -v "$executable" >/dev/null 2>&1; then
    timeout 10 "$executable" "$@" >>"$output_file" 2>&1 || printf 'Command returned a non-zero status.\n' >>"$output_file"
  else
    printf '%s is not installed.\n' "$executable" >>"$output_file"
  fi
}

: >"$output_file"
section 'collection metadata'
printf 'collected_utc=%s\nhostname=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname)" >>"$output_file"
capture uname -a
capture uptime
capture df -hP
capture free -m
capture ip -brief address
capture ip route
capture ss -tuln
capture ps -eo pid,comm,%cpu,%mem --sort=-%cpu

section 'resolver configuration'
sed -E 's/^(search|domain).*/\1 [redacted]/' /etc/resolv.conf >>"$output_file" 2>&1 || true

if [[ -n "$target_host" ]]; then
  capture getent ahosts "$target_host"
  capture ping -c 3 -W 2 "$target_host"
fi

printf 'Evidence written to %s\n' "$output_file"
