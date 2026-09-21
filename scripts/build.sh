#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
class_dir="$project_root/build/classes"
mkdir -p "$class_dir"
mapfile -t sources < <(find "$project_root/src/main/java" -type f -name '*.java' | sort)
if ((${#sources[@]} == 0)); then
  printf 'No Java source files found.\n' >&2
  exit 1
fi
javac --release 17 --add-modules jdk.httpserver -d "$class_dir" "${sources[@]}"
printf 'Compiled controller classes to %s\n' "$class_dir"
