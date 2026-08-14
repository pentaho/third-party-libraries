#!/usr/bin/env bash

set -euo pipefail

base_sha=${1:?base commit SHA is required}
head_sha=${2:?head commit SHA is required}
modules_file=${PUBLISH_MODULES_FILE:-.github/publish-modules.txt}
repository_url=${PUBLIC_RELEASE_REPO_URL:?repository URL is required}

# A push event reports an all-zero "before" SHA for a new branch, and the commit
# can be missing after a force push, so fall back to the head commit's parent.
if [[ "$base_sha" =~ ^0+$ ]] || ! git cat-file -e "${base_sha}^{commit}" 2>/dev/null; then
  base_sha=$(git rev-parse --verify --quiet "${head_sha}^") \
    || base_sha=$(git hash-object -t tree /dev/null)
fi

changed_files=$(git diff --name-only "$base_sha" "$head_sha")
modules=()

# The trailing test keeps the final entry when the modules file has no newline
# at end of file.
while IFS= read -r module || [[ -n "$module" ]]; do
  [[ -z "$module" || "$module" == \#* ]] && continue

  matched=false
  while IFS= read -r changed_file; do
    case "$changed_file" in
      "$module"/*) matched=true ;;
    esac
  done <<<"$changed_files"

  [[ "$matched" == true ]] && modules+=("$module")
done < "$modules_file"

if (( ${#modules[@]} == 0 )); then
  echo "modules=" >> "$GITHUB_OUTPUT"
  exit 0
fi

for module in "${modules[@]}"; do
  pom="$module/pom.xml"
  group_id=$(mvn --batch-mode --quiet -pl "$module" -DforceStdout help:evaluate -Dexpression=project.groupId)
  artifact_id=$(mvn --batch-mode --quiet -pl "$module" -DforceStdout help:evaluate -Dexpression=project.artifactId)
  # Resolve the interpolated version so a property-based revision such as
  # ${netty.version}-pentaho-1 yields the version that is actually released.
  version=$(mvn --batch-mode --quiet -pl "$module" -DforceStdout help:evaluate -Dexpression=project.version)

  if [[ -z "$group_id" || -z "$artifact_id" || ! "$version" =~ ^(.+)-pentaho-([0-9]+)$ ]]; then
    echo "Module '$module' must resolve groupId, artifactId, and a version ending in -pentaho-N." >&2
    exit 1
  fi

  base_version=${BASH_REMATCH[1]}
  metadata_url="${repository_url%/}/${group_id//.//}/${artifact_id}/maven-metadata.xml"
  metadata_file=$(mktemp)
  http_status=$(curl --silent --show-error --output "$metadata_file" --write-out '%{http_code}' \
    --user "$ARTIFACTORY_USERNAME:$ARTIFACTORY_PASSWORD" "$metadata_url")

  if [[ "$http_status" == "404" ]]; then
    metadata=""
  elif [[ "$http_status" == "200" ]]; then
    metadata=$(cat "$metadata_file")
  else
    rm -f "$metadata_file"
    echo "Failed to query Artifactory metadata for $group_id:$artifact_id (HTTP $http_status)" >&2
    exit 1
  fi
  rm -f "$metadata_file"

  highest_release=$(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' <<<"$metadata" \
    | grep -F "${base_version}-pentaho-" \
    | sed -n "s|^${base_version}-pentaho-\([0-9][0-9]*\)$|\1|p" \
    | sort -n \
    | tail -1 || true)

  if [[ -n "$highest_release" ]]; then
    next_counter=$((highest_release + 1))
  else
    next_counter=1
  fi

  # Only the release counter is rewritten, so any version property is preserved.
  sed -i.bak -E "s|(<revision>.*-pentaho-)[0-9]+(</revision>)|\1${next_counter}\2|" "$pom"
  rm "${pom}.bak"
  echo "Prepared $module as ${base_version}-pentaho-${next_counter}"
done

IFS=,
echo "modules=${modules[*]}" >> "$GITHUB_OUTPUT"
