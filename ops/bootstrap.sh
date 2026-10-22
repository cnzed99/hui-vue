#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
sudo -n apt-get update
sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless maven mysql-server redis-server curl ca-certificates xz-utils unzip jq python3-venv
if [ ! -f /swapfile-print ]; then
  sudo -n fallocate -l 2G /swapfile-print
  sudo -n chmod 600 /swapfile-print
  sudo -n mkswap /swapfile-print
  sudo -n swapon /swapfile-print
  printf '/swapfile-print none swap sw 0 0\n' | sudo -n tee -a /etc/fstab >/dev/null
fi
cd /home/ubuntu/print-system
mkdir -p runtime/node
node_version=$(curl -fsS --connect-timeout 10 --max-time 30 https://nodejs.org/dist/index.json | jq -r '[.[] | select(.version | startswith("v22."))][0].version')
[[ "$node_version" =~ ^v22\.[0-9]+\.[0-9]+$ ]]
cd runtime/node
curl -fSs --connect-timeout 10 --max-time 180 -O "https://nodejs.org/dist/$node_version/node-$node_version-linux-x64.tar.xz"
curl -fSs --connect-timeout 10 --max-time 30 -O "https://nodejs.org/dist/$node_version/SHASUMS256.txt"
grep " node-$node_version-linux-x64.tar.xz$" SHASUMS256.txt | sha256sum -c -
tar -xJf "node-$node_version-linux-x64.tar.xz"
ln -sfn "node-$node_version-linux-x64" current
export PATH="$PWD/current/bin:$PATH"
npm install --global --prefix /home/ubuntu/print-system/runtime/npm pnpm@10
printf '\nBOOTSTRAP_VERSIONS\n'
java -version
javac -version
mvn -version
node --version
/home/ubuntu/print-system/runtime/npm/bin/pnpm --version
mysql --version
redis-server --version
free -m
printf '\nBOOTSTRAP_OK\n'
