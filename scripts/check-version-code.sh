#!/bin/sh
# Fail if versionCode in app/build.gradle is not higher than at the newest other release tag.
# Android will not install an update whose versionCode is equal or lower, and nobody notices until the phone.
# Needs full git history with tags (checkout with fetch-depth: 0).
set -eu

GRADLE="${1:-app/build.gradle}"
code_of() { sed -n 's/^[[:space:]]*versionCode[[:space:]]*\([0-9][0-9]*\).*$/\1/p' | head -n 1; }
version_of() { sed -n "s/^def appVersion = '\(.*\)'$/\1/p"; }

VERSION="$(version_of < "$GRADLE")"
CODE="$(code_of < "$GRADLE")"
[ -n "$VERSION" ] && [ -n "$CODE" ] || { echo "FAIL: appVersion or versionCode not found in $GRADLE" >&2; exit 1; }

PREV="$(git tag -l 'v[0-9]*' | grep -vx "v$VERSION" | sort -V | tail -n 1 || true)"
if [ -z "$PREV" ]; then
  echo "no earlier release tag; versionCode $CODE accepted"
  exit 0
fi

PREV_CODE="$(git show "$PREV:$GRADLE" | code_of)"
[ -n "$PREV_CODE" ] || { echo "FAIL: versionCode not found at $PREV" >&2; exit 1; }

if [ "$CODE" -le "$PREV_CODE" ]; then
  echo "FAIL: versionCode $CODE (v$VERSION) must be higher than $PREV_CODE ($PREV)" >&2
  exit 1
fi
echo "versionCode $CODE > $PREV_CODE ($PREV): ok"
