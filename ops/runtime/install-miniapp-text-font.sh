#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_ROOT="/home/ubuntu/print-system"
readonly SOURCE_FONT="${PROJECT_ROOT}/runtime/browser-tools/sysroot/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
readonly SOURCE_LICENSE="${PROJECT_ROOT}/runtime/browser-tools/sysroot/usr/share/doc/fonts-wqy-zenhei/copyright"
readonly TARGET_DIR="${PROJECT_ROOT}/runtime/fonts"
readonly TARGET_FONT="${TARGET_DIR}/wqy-zenhei.ttc"
readonly TARGET_LICENSE_DIR="${TARGET_DIR}/licenses"
readonly TARGET_LICENSE="${TARGET_LICENSE_DIR}/wqy-zenhei-copyright"
readonly FONT_SHA256="79c18ebe7b811951e8311bad7103ebeae8c337ed9988ea69e8a78a66cfe029b9"
readonly LICENSE_SHA256="d183af7db6d782fe7009eeafef0cef67099ef50ef18dd81513de8004b0361cb7"

require_plain_file() {
  local path="$1"
  if [[ ! -f "$path" || -L "$path" ]]; then
    echo "required plain file is unavailable: $path" >&2
    exit 1
  fi
}

verify_sha256() {
  local expected="$1"
  local path="$2"
  local actual
  actual="$(sha256sum -- "$path" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "sha256 mismatch: $path" >&2
    exit 1
  fi
}

require_plain_file "$SOURCE_FONT"
require_plain_file "$SOURCE_LICENSE"
verify_sha256 "$FONT_SHA256" "$SOURCE_FONT"
verify_sha256 "$LICENSE_SHA256" "$SOURCE_LICENSE"

if [[ -L "$TARGET_DIR" || -L "$TARGET_LICENSE_DIR" ]]; then
  echo "font target directory must not be a symlink" >&2
  exit 1
fi
install -d -m 0755 -- "$TARGET_DIR" "$TARGET_LICENSE_DIR"
if [[ -L "$TARGET_DIR" || -L "$TARGET_LICENSE_DIR" ]]; then
  echo "font target directory must not be a symlink" >&2
  exit 1
fi
chmod 0755 -- "$TARGET_DIR" "$TARGET_LICENSE_DIR"

font_tmp="$(mktemp "${TARGET_DIR}/.wqy-zenhei.ttc.XXXXXX")"
license_tmp="$(mktemp "${TARGET_LICENSE_DIR}/.wqy-zenhei-copyright.XXXXXX")"
cleanup() {
  rm -f -- "$font_tmp" "$license_tmp"
}
trap cleanup EXIT

cp -- "$SOURCE_FONT" "$font_tmp"
cp -- "$SOURCE_LICENSE" "$license_tmp"
chmod 0444 -- "$font_tmp" "$license_tmp"
verify_sha256 "$FONT_SHA256" "$font_tmp"
verify_sha256 "$LICENSE_SHA256" "$license_tmp"

mv -fT -- "$font_tmp" "$TARGET_FONT"
mv -fT -- "$license_tmp" "$TARGET_LICENSE"
trap - EXIT

require_plain_file "$TARGET_FONT"
require_plain_file "$TARGET_LICENSE"
verify_sha256 "$FONT_SHA256" "$TARGET_FONT"
verify_sha256 "$LICENSE_SHA256" "$TARGET_LICENSE"
echo "miniapp text font installed and verified"
