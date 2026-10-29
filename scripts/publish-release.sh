#!/usr/bin/env bash
# Publishes the staged release (scripts/stage-release.sh) as a GitHub Release: tag v<version> on this commit, then a
# release that is not a draft, not a prerelease, and marked "Latest" when it is the newest version.
#
# The release is created as a draft with `gh release create`, the assets are checked, and only then is it published,
# so nobody ever sees a release that is missing its files. It is safe to re-run:
#   * a published release for the tag already exists -> nothing to do, exits 0
#   * a draft from an interrupted run exists          -> it is replaced
#   * the tag exists on a different commit            -> refuses (that version number is already taken)
#
# Usage: scripts/publish-release.sh --version-name 1.0.57 --dist DIR --notes-file FILE [--generate-notes]
# Environment: GH_TOKEN (needs contents: write), GITHUB_REPOSITORY, GITHUB_SHA. Needs the gh and jq CLIs.
# --generate-notes appends GitHub's categorised notes (from .github/release.yml) after the notes file.
set -euo pipefail

version_name="" dist="" notes_file="" generate_notes=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version-name) version_name=${2:?}; shift 2 ;;
    --dist) dist=${2:?}; shift 2 ;;
    --notes-file) notes_file=${2:?}; shift 2 ;;
    --generate-notes) generate_notes=true; shift ;;
    *) echo "usage: $0 --version-name V --dist DIR --notes-file FILE [--generate-notes]" >&2; exit 2 ;;
  esac
done
[[ -n $version_name && -d $dist && -f $notes_file ]] || { echo "publish-release: --version-name, --dist (a folder) and --notes-file (a file) are required" >&2; exit 2; }
repo=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}
sha=${GITHUB_SHA:?GITHUB_SHA is not set}
tag=v$version_name

fail() { echo "publish-release: $*" >&2; exit 1; }

shopt -s nullglob
assets=("$dist"/*)
shopt -u nullglob
for required in "UnsendVault-$version_name.apk" "UnsendVault-$version_name.apk.sha256" SHA256SUMS.txt; do
  [[ -f $dist/$required ]] || fail "$dist/$required is missing"
done
# The checksums must still match the files about to be uploaded.
(cd "$dist" && sha256sum --check --quiet SHA256SUMS.txt) || fail "SHA256SUMS.txt does not match the staged files"

releases_with_tag() { # prints "<id> <draft>" for every release (draft or not) of this tag
  gh api --paginate "repos/$repo/releases?per_page=100" | jq -r --arg tag "$tag" '.[] | select(.tag_name == $tag) | "\(.id) \(.draft)"'
}

# --- 1. the tag ----------------------------------------------------------------------------------------------------
if tagged=$(gh api "repos/$repo/git/ref/tags/$tag" --jq '"\(.object.type) \(.object.sha)"' 2> /dev/null); then
  read -r object_type object_sha <<< "$tagged"
  if [[ $object_type == tag ]]; then object_sha=$(gh api "repos/$repo/git/tags/$object_sha" --jq .object.sha); fi
  [[ $object_sha == "$sha" ]] || fail "tag $tag already points at $object_sha, not at this commit ($sha); that version number is taken"
  echo "Tag $tag already points at this commit."
else
  gh api -X POST "repos/$repo/git/refs" -f "ref=refs/tags/$tag" -f "sha=$sha" --silent
  echo "Created tag $tag at $sha."
fi

# --- 2. an earlier attempt ---------------------------------------------------------------------------------------
earlier=$(releases_with_tag)
while read -r existing_id existing_draft; do
  [[ -n $existing_id ]] || continue
  if [[ $existing_draft == false ]]; then
    echo "Release $tag is already published; nothing to do."
    exit 0
  fi
  echo "Removing the unfinished draft of $tag from an earlier run."
  gh api -X DELETE "repos/$repo/releases/$existing_id" --silent
done <<< "$earlier"

# --- 3. draft with assets ----------------------------------------------------------------------------------------
create=(release create "$tag" --repo "$repo" --verify-tag --draft --title "UnsendVault $version_name" --notes-file "$notes_file")
if $generate_notes; then create+=(--generate-notes); fi
gh "${create[@]}" "${assets[@]}"

release_id=""
created=$(releases_with_tag)
while read -r id draft; do
  if [[ $draft == true ]]; then release_id=$id; fi
done <<< "$created"
[[ -n $release_id ]] || fail "the draft release for $tag was not found after creating it"

uploaded=$(gh api "repos/$repo/releases/$release_id" --jq '[.assets[] | select(.state == "uploaded") | .name] | sort | join(" ")')
# jq sorts by code point, so compare against a byte-order sort whatever the machine's locale is.
expected=$(for asset in "${assets[@]}"; do basename "$asset"; done | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')
[[ $uploaded == "$expected" ]] || fail "the draft holds [$uploaded] but [$expected] was expected"

# --- 4. publish ---------------------------------------------------------------------------------------------------
# Only claim "Latest" when this really is the newest version: a re-run of an old commit must not demote a newer
# release. The patch number is the commit count, so it orders releases across minor/major bumps too.
make_latest=true
if latest_tag=$(gh api "repos/$repo/releases/latest" --jq .tag_name 2> /dev/null) && [[ -n $latest_tag ]]; then
  latest_number=${latest_tag##*.}
  if [[ $latest_number =~ ^[0-9]+$ ]] && (( 10#$latest_number > 10#${version_name##*.} )); then
    make_latest=false
    echo "::warning::$latest_tag is newer than $tag, so $tag is published without the Latest mark."
  fi
fi
url=$(gh api -X PATCH "repos/$repo/releases/$release_id" -F draft=false -F prerelease=false -f "make_latest=$make_latest" --jq .html_url)

# --- 5. check what GitHub now says --------------------------------------------------------------------------------
state=$(gh api "repos/$repo/releases/$release_id" --jq '"\(.draft) \(.prerelease) \(.tag_name)"')
[[ $state == "false false $tag" ]] || fail "after publishing, GitHub reports '$state' (expected 'false false $tag')"

echo "Published $url"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then echo "release_url=$url" >> "$GITHUB_OUTPUT"; fi
