#!/bin/sh
# Print the CHANGELOG.md section for a release tag, e.g.  scripts/release-notes.sh v0.0.3
# The section starts at the line "## v0.0.3" (exact match) and ends at the next "## " line.
# Exits 1 if the section is missing or has no text, so a release can never go out without notes.
set -eu

TAG="${1:?usage: release-notes.sh vX.Y.Z [CHANGELOG.md]}"
FILE="${2:-CHANGELOG.md}"
[ -f "$FILE" ] || { echo "FAIL: $FILE not found" >&2; exit 1; }

BODY="$(tr -d '\r' < "$FILE" | awk -v head="## $TAG" '
  $0 == head { on = 1; next }
  /^## /     { on = 0 }
  on         { print }
')"

# Trim leading and trailing blank lines.
BODY="$(printf '%s\n' "$BODY" | awk 'NF { found = 1 } found { print }' | awk '{ l[NR] = $0 } END { n = NR; while (n > 0 && l[n] ~ /^[ \t]*$/) n--; for (i = 1; i <= n; i++) print l[i] }')"

[ -n "$BODY" ] || { echo "FAIL: $FILE has no '## $TAG' section with text" >&2; exit 1; }
printf '%s\n' "$BODY"
