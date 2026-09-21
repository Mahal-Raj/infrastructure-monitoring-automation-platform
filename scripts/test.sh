#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$project_root/scripts/build.sh"

test_classes="$project_root/build/test-classes"
mkdir -p "$test_classes"
mapfile -t tests < <(find "$project_root/src/test/java" -type f -name '*.java' | sort)
javac --release 17 --add-modules jdk.httpserver -cp "$project_root/build/classes" -d "$test_classes" "${tests[@]}"
java --add-modules jdk.httpserver -cp "$project_root/build/classes:$test_classes" com.sukhraj.infra.FunctionalTest

(
  cd "$project_root/agent"
  python3 -m unittest -v test_infra_agent.py
)
(
  cd "$project_root/tools"
  python3 -m unittest -v test_export_report.py
)

python3 -m py_compile "$project_root/agent/infra_agent.py" "$project_root/tools/export_report.py"
bash -n "$project_root"/scripts/*.sh
printf 'PASS: Java, Python, Bash, and report-export validation completed.\n'
