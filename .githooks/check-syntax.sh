#!/bin/sh
# Syntax check for app/ (it cannot be compiled here; CI does that). javac stops at parse errors before it
# resolves symbols, so a syntax error shows alone, while a clean parse shows only "package android.* does
# not exist". Fails on a syntax error; says nothing about wrong-but-valid code.
out=$(git rev-parse --git-dir)/syntax-check
mkdir -p "$out"
files=$(find app/src/main/java -name '*.java')
msgs=$(javac -proc:none -implicit:none -d "$out" $files 2>&1)
if printf '%s\n' "$msgs" | grep -qE 'illegal start|error: .*expected|unclosed|reached end of file|not a statement|class, interface'; then
  printf '%s\n' "$msgs" | grep -E -A2 'illegal start|error: .*expected|unclosed|reached end of file|not a statement|class, interface' | head -20 >&2
  echo "check-syntax: syntax error in app/ (see above)" >&2
  exit 1
fi
