#!/usr/bin/env bash
set -Eeuo pipefail

worktree=/home/ubuntu/print-system/runtime/worktrees/miniapp-id-photo-server-file-20261103
dependencies=/home/ubuntu/print-system/src/miniapp/node_modules
export PATH=/home/ubuntu/print-system/runtime/node/current/bin:/home/ubuntu/print-system/runtime/npm/bin:/usr/bin:/bin

cd -- "$worktree"
test -d "$dependencies/.bin"
test ! -e node_modules
ln -s -- "$dependencies" node_modules
cleanup() {
  if [[ -L node_modules && $(readlink -- node_modules) == "$dependencies" ]]; then
    unlink -- node_modules
  fi
}
trap cleanup EXIT

npm run type-check
npm run build:h5
npm run build:mp-weixin
