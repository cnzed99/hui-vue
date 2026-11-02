#!/usr/bin/env bash
set -euo pipefail
set -a
source /home/ubuntu/print-system/runtime/secrets/runtime.env
set +a
exec python3 "$(dirname -- "$0")/run.py" "$@"
