#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system/src
for spec in 'backend https://github.com/YunaiV/ruoyi-vue-pro.git 3adaf319f668e38f786dfa679ac627dcc9c25c86' 'frontend https://github.com/yudaocode/yudao-ui-admin-vue3.git 050bac1eccfbe7393a1568d614151cc99501b949'; do
  read -r name url commit <<< "$spec"
  if [ ! -d "$name/.git" ]; then
    mkdir "$name"
    git -C "$name" init -q
    git -C "$name" remote add origin "$url"
  fi
  git -C "$name" fetch --depth=1 origin "$commit"
  git -C "$name" checkout --detach "$commit"
  git -C "$name" rev-parse HEAD
done
printf 'SOURCE_FETCH_OK\n'
