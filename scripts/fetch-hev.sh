#!/bin/sh
# Fetch hev-socks5-tunnel at the pinned tag and verify every pinned commit.
# Result: app/src/main/jni/hev-socks5-tunnel (git-ignored, built by ndk-build).
set -eu

TAG=2.19.3
HEV_COMMIT=c5584feca8f6df70ee53eb2879e034b849a3d15a
CORE_COMMIT=dadbb54bf7509f6dea3c1b30e45198efa400679c
TASK_COMMIT=d0365c370aadd39129924b563b80751cb63332d7
LWIP_COMMIT=495671e59d736ef59f6cfa7dc9d35b7359921062
YAML_COMMIT=94441ec8777eb319936735a58b376963d09c73d4

DEST="$(dirname "$0")/../app/src/main/jni/hev-socks5-tunnel"
rm -rf "$DEST"
git clone --quiet --branch "$TAG" https://github.com/heiher/hev-socks5-tunnel "$DEST"
cd "$DEST"
git submodule update --init --quiet

check() {
    actual=$(git -C "$1" rev-parse HEAD)
    if [ "$actual" != "$2" ]; then
        echo "pin mismatch in $1: got $actual, want $2" >&2
        exit 1
    fi
}
check . "$HEV_COMMIT"
check src/core "$CORE_COMMIT"
check third-part/hev-task-system "$TASK_COMMIT"
check third-part/lwip "$LWIP_COMMIT"
check third-part/yaml "$YAML_COMMIT"
echo "hev $TAG pinned commits verified"
