#!/usr/bin/env bash
# Works out the UnsendVault version and prints it as `name=value` lines, ready for `>> "$GITHUB_OUTPUT"`:
#
#   version_name=1.0.57
#   version_code=57
#
# The rules are the same as the ones in app/build.gradle.kts (keep the two in step; see docs/RELEASING.md):
#   versionName = <major>.<minor>.<N>   major and minor come from version.properties
#   versionCode = N                     N = commits reachable from HEAD, which only grows on `main`
# UNSENDVAULT_VERSION_CODE / UNSENDVAULT_VERSION_NAME override either value. Without git (a source tarball) the code is
# 1 and the name <major>.<minor>.0.
#
# --strict is for releases: a release must never get a guessed version, so it refuses to run without full git history.
# A shallow clone counts too few commits and could produce a versionCode that goes backwards, and Android refuses to
# install an update with a lower versionCode.
set -euo pipefail

strict=false
case "${1:-}" in
  --strict) strict=true ;;
  "") ;;
  *) echo "usage: $0 [--strict]" >&2; exit 2 ;;
esac

cd "$(dirname "${BASH_SOURCE[0]}")/.."
max_code=2100000000 # the largest versionCode Android app stores accept

fail() { echo "compute-version: $*" >&2; exit 1; }

prop() { # prop <key>: the last value of <key> in version.properties
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" version.properties | tail -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//'
}

[[ -f version.properties ]] || fail "version.properties is missing"
major=$(prop major)
minor=$(prop minor)
[[ $major =~ ^[0-9]+$ && $minor =~ ^[0-9]+$ ]] || fail "version.properties needs numeric major= and minor= entries (found '$major' / '$minor')"

count=""
if command -v git > /dev/null 2>&1 && git rev-parse --git-dir > /dev/null 2>&1; then
  count=$(git rev-list --count HEAD 2> /dev/null || true)
  if [[ $(git rev-parse --is-shallow-repository 2> /dev/null || echo false) == true ]]; then
    $strict && fail "this is a shallow clone, so the commit count is wrong; fetch the full history (actions/checkout: fetch-depth: 0)"
    echo "compute-version: warning: shallow clone, the commit count is too low" >&2
  fi
fi
if [[ ! $count =~ ^[1-9][0-9]*$ ]]; then
  $strict && fail "cannot count commits (git missing, not a repository, or no commits)"
  count=""
fi

code_override=${UNSENDVAULT_VERSION_CODE:-}
name_override=${UNSENDVAULT_VERSION_NAME:-}
if [[ -n $code_override || -n $name_override ]]; then
  echo "compute-version: note: UNSENDVAULT_VERSION_* is set and overrides the git count" >&2
fi

n=${code_override:-$count}
if [[ -n $n ]]; then
  if [[ ! $n =~ ^[1-9][0-9]{0,9}$ ]] || (( n > max_code )); then fail "versionCode '$n' must be an integer between 1 and $max_code"; fi
  version_code=$n
else
  version_code=1
fi

version_name=${name_override:-$major.$minor.${n:-0}}
[[ $version_name =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || fail "versionName '$version_name' must look like 1.2.3"

echo "version_name=$version_name"
echo "version_code=$version_code"
