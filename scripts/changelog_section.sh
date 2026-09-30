#!/usr/bin/env bash
# Prints one version's section of CHANGELOG.md (without its "## " heading), for release notes.
# Usage: scripts/changelog_section.sh 1.6   (a leading "v" is accepted: v1.6)
# Exits 1 if the version has no section.
set -euo pipefail

version="${1#v}"
changelog="$(dirname "$0")/../CHANGELOG.md"

section="$(awk -v heading="## ${version}" '
  index($0, heading " ") == 1 || $0 == heading { found = 1; next }
  found && /^## / { exit }
  found { print }
' "$changelog")"

# Trim leading and trailing blank lines.
section="$(printf '%s\n' "$section" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"

if [[ -z "$section" ]]; then
  echo "No section for ${version} in CHANGELOG.md" >&2
  exit 1
fi
printf '%s\n' "$section"
