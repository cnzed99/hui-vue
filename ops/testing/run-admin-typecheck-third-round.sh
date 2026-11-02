#!/usr/bin/env bash
set -Eeuo pipefail
ulimit -c 0
ROOT=/home/ubuntu/print-system
ADMIN="$ROOT/runtime/worktrees/r11-admin-sharded-typecheck-20261102"
ENGINEERING="$ROOT/runtime/worktrees/r11-admin-typecheck-engineering-20261102"
[[ $# == 2 && $1 =~ ^[0-9a-f]{40}$ && $2 =~ ^r11-admin-typecheck-[a-z0-9-]+$ ]]
EXPECTED_ADMIN_COMMIT=$1
EVIDENCE="$ROOT/evidence/testing/$2"
NODE_BIN="$ROOT/runtime/node/node-v22.23.4-linux-x64/bin"
[[ -n ${TMPDIR:-} && $(realpath -e "$TMPDIR") == "$ROOT/runtime/build-tmp/"* ]]
[[ $(git -C "$ADMIN" rev-parse HEAD) == "$EXPECTED_ADMIN_COMMIT" ]]
mkdir -p "$EVIDENCE"
test ! -e "$EVIDENCE/summary.json"
set +e
sudo -n systemd-run --quiet --scope --unit=print-r11-admin-typecheck-third-round \
  -p MemoryMax=3221225472 -p MemorySwapMax=268435456 \
  /usr/sbin/runuser -u ubuntu -- /usr/bin/env \
  PATH="$NODE_BIN:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
  HOME=/home/ubuntu LANG=C.UTF-8 TMPDIR="$TMPDIR" \
  /usr/bin/python3 "$ENGINEERING/ops/testing/run-admin-typecheck-third-round.py" \
  --admin "$ADMIN" --evidence "$EVIDENCE"
scope_rc=$?
set -e
printf '%s\n' "$scope_rc" > "$EVIDENCE/scope-exit-code.txt"
if [[ ! -f "$EVIDENCE/summary.json" ]]; then
  printf 'EXECUTOR_TERMINATED_WITHOUT_SUMMARY scope_exit=%s\n' "$scope_rc" >&2
  exit 1
fi
