#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:---dry-run}"
install_root=/opt/infrastructure-monitoring-agent
environment_dir=/etc/infra-agent
service_file=/etc/systemd/system/infra-agent.service

if [[ "$mode" != "--dry-run" && "$mode" != "--apply" ]]; then
  printf 'Usage: %s [--dry-run|--apply]\n' "$0" >&2
  exit 64
fi

printf 'Installation plan:\n'
printf '  agent: %s/infra_agent.py\n' "$install_root"
printf '  environment: %s/agent.env\n' "$environment_dir"
printf '  systemd unit: %s\n' "$service_file"

if [[ "$mode" == "--dry-run" ]]; then
  printf 'Dry run only. Re-run with --apply to install.\n'
  exit 0
fi

if ! id -u infra-agent >/dev/null 2>&1; then
  if getent group systemd-journal >/dev/null 2>&1; then
    sudo useradd --system --home-dir "$install_root" --shell /usr/sbin/nologin --groups systemd-journal infra-agent
  else
    sudo useradd --system --home-dir "$install_root" --shell /usr/sbin/nologin infra-agent
  fi
fi
sudo install -d -m 0755 "$install_root" "$environment_dir"
sudo install -m 0755 "$project_root/agent/infra_agent.py" "$install_root/infra_agent.py"
if [[ ! -f "$environment_dir/agent.env" ]]; then
  sudo install -m 0600 "$project_root/deploy/agent.env.example" "$environment_dir/agent.env"
fi
sudo install -m 0644 "$project_root/deploy/infra-agent.service" "$service_file"
printf 'Review deploy/infra-agent.sudoers.example before enabling ALLOW_MUTATIONS.\n'
sudo systemctl daemon-reload
sudo systemctl enable --now infra-agent.service
sudo systemctl --no-pager status infra-agent.service
