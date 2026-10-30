#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/ops/runtime/frontend-safe-build
source "$BASE/common.sh"
[[ $EUID != 0 && $# == 2 ]]
MODE=$1
SCRATCH=$2
RUN_ID=${SCRATCH##*/}
[[ $RUN_ID =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{16}$ ]]
[[ $(realpath -e "$SCRATCH") == "$BASE/scratch/$RUN_ID" && ! -L $SCRATCH ]]
[[ -z ${NODE_OPTIONS+x} && -z ${VITE_SOURCEMAP+x} && -z ${VITE_COMPRESS+x} ]]
case $MODE in
  self-test-success|self-test-recovery-deadline) mkdir -- "$SCRATCH/dist"; printf '<!doctype html><title>simulated only</title>\n' > "$SCRATCH/dist/index.html"; echo 'simulated child success; no Vite'; exit 0 ;;
  self-test-fail) echo 'simulated child failure'; exit 42 ;;
  self-test-timeout) echo 'simulated child timeout'; sleep 90; exit 43 ;;
  real) ;;
  *) exit 64 ;;
esac
cd -- "$FRONTEND"
"$NODE" "$BASE/config-check.mjs"
printf 'command=node --max-old-space-size=2048 ./node_modules/vite/bin/vite.js build --mode cloud --outDir %s/dist --emptyOutDir\n' "$SCRATCH"
exec "$NODE" --max-old-space-size=2048 ./node_modules/vite/bin/vite.js build --mode cloud --outDir "$SCRATCH/dist" --emptyOutDir
