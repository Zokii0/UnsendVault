#!/usr/bin/env bash
# Lays out a release: copies the verified build outputs into one folder under their public names, writes the checksum
# files and the release notes.
#
#   UnsendVault-<version>.apk          the signed APK
#   UnsendVault-<version>.apk.sha256   its SHA-256 in `sha256sum` format ("<hash>  UnsendVault-<version>.apk")
#   mapping-<version>.zip              R8 mapping, to de-obfuscate stack traces (only when --mapping is given)
#   SHA256SUMS.txt                     SHA-256 of every file above
#
# Usage: scripts/stage-release.sh --version-name 1.0.57 --cert-sha256 AA:BB:.. --apk F --out DIR --notes-file F \
#            [--mapping F]
# Environment: GITHUB_REPOSITORY (links in the notes), GITHUB_SHA (defaults to HEAD).
# The notes list the commits since the previous v* tag.
set -euo pipefail

version_name="" cert_sha256="" apk="" mapping="" out="" notes_file=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version-name) version_name=${2:?}; shift 2 ;;
    --cert-sha256) cert_sha256=${2:?}; shift 2 ;;
    --apk) apk=${2:?}; shift 2 ;;
    --mapping) mapping=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    --notes-file) notes_file=${2:?}; shift 2 ;;
    *) echo "usage: $0 --version-name V --cert-sha256 FP --apk F --out DIR --notes-file F [--mapping F]" >&2; exit 2 ;;
  esac
done
for required in version_name cert_sha256 apk out notes_file; do
  [[ -n ${!required} ]] || { echo "stage-release: --${required//_/-} is required" >&2; exit 2; }
done
[[ $version_name =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "stage-release: bad version '$version_name'" >&2; exit 2; }
[[ $cert_sha256 =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]] || { echo "stage-release: --cert-sha256 must be 32 colon-separated hex bytes" >&2; exit 2; }
[[ -f $apk ]] || { echo "stage-release: $apk does not exist" >&2; exit 1; }
[[ -z $mapping || -f $mapping ]] || { echo "stage-release: $mapping does not exist" >&2; exit 1; }

# Resolve paths before changing directory, so relative arguments keep working.
abs() { if [[ $1 == /* ]]; then printf '%s\n' "$1"; else printf '%s/%s\n' "$PWD" "$1"; fi; }
apk=$(abs "$apk")
out=$(abs "$out")
notes_file=$(abs "$notes_file")
[[ -z $mapping ]] || mapping=$(abs "$mapping")

cd "$(dirname "${BASH_SOURCE[0]}")/.."
repo=${GITHUB_REPOSITORY:-riyadmondol2006/UnsendVault}
sha=${GITHUB_SHA:-$(git rev-parse HEAD)}
tag=v$version_name
apk_name=UnsendVault-$version_name.apk
cert_hex=$(tr -d ':' <<< "$cert_sha256" | tr '[:upper:]' '[:lower:]')

rm -rf "$out"
mkdir -p "$out" "$(dirname "$notes_file")"
cp "$apk" "$out/$apk_name"
(cd "$out" && sha256sum -- "$apk_name" > "$apk_name.sha256")
apk_sha256=$(cut -d' ' -f1 < "$out/$apk_name.sha256")
if [[ -n $mapping ]]; then
  # -X drops extra file attributes and -j the directories, so the zip does not leak the build machine's paths.
  (cd "$(dirname "$mapping")" && zip -q -9 -X -j "$out/mapping-$version_name.zip" "$(basename "$mapping")")
fi
(cd "$out" && files=(*) && sha256sum -- "${files[@]}" > SHA256SUMS.txt)

# --- notes -------------------------------------------------------------------------------------------------------
# The newest earlier release tag. Starting from the parent keeps a re-run of this very commit from finding its own tag.
previous_tag=$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$sha^" 2> /dev/null || true)
range=$sha
[[ -z $previous_tag ]] || range=$previous_tag..$sha
mapfile -t commits < <(git log --no-merges --pretty='format:- %s (%h)' "$range")
max_lines=60

{
  echo "## Install"
  echo
  echo "Download \`$apk_name\`, check it (below), open it on your phone and allow installing from your browser or file"
  echo "manager when Android asks. On Android 13 and later, Android may block Notification Access for an app installed"
  echo "this way until you allow it: Settings > Apps > UnsendVault > ⋮ > *Allow restricted settings*."
  echo
  echo "Your archive never leaves your phone. The only network use is an optional update check (off until you allow it)."
  echo "UnsendVault only archives what messaging apps show in notifications after you grant Notification Access; see the"
  echo "README for what it cannot capture."
  echo
  echo "## What changed"
  echo
  if [[ ${#commits[@]} -eq 0 ]]; then
    echo "No source changes since ${previous_tag:-the start of the project}."
  else
    [[ -n $previous_tag ]] || { echo "First release."; echo; }
    printf '%s\n' "${commits[@]:0:max_lines}"
    [[ ${#commits[@]} -le max_lines ]] || echo "- ...and $(( ${#commits[@]} - max_lines )) more commits"
  fi
  if [[ -n $previous_tag ]]; then
    echo
    echo "Full changelog: https://github.com/$repo/compare/$previous_tag...$tag"
  fi
  echo
  echo "## Verify your download"
  echo
  echo "| | SHA-256 |"
  echo "|---|---|"
  echo "| \`$apk_name\` | \`$apk_sha256\` |"
  echo "| Signing certificate | \`$cert_sha256\` |"
  echo "| Signing certificate, as apksigner prints it | \`$cert_hex\` |"
  echo
  echo '```sh'
  echo "sha256sum --check $apk_name.sha256"
  echo "apksigner verify --print-certs $apk_name   # certificate SHA-256 digest must match the table"
  echo '```'
  echo
  echo "Every release is signed with the same certificate; Android refuses an update signed by a different one."
  if [[ -n $mapping ]]; then
    echo
    echo "\`mapping-$version_name.zip\` de-obfuscates crash stack traces and is not needed to use the app."
  fi
} > "$notes_file"

echo "Staged $(find "$out" -type f | wc -l) files in $out and wrote $notes_file (commits since ${previous_tag:-the beginning})"
echo "apk_sha256=$apk_sha256"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then echo "apk_sha256=$apk_sha256" >> "$GITHUB_OUTPUT"; fi
