#!/usr/bin/env bash

set -euo pipefail

base_sha=${1:?base commit SHA is required}
head_sha=${2:?head commit SHA is required}
modules_file=${PUBLISH_MODULES_FILE:-.github/publish-modules.txt}
repository_url=${THIRD_PARTY_MAVEN_REPOSITORY_URL:?repository URL is required}

changed_files=$(git diff --name-only "$base_sha" "$head_sha")
modules=()

while IFS= read -r module; do
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
  revision=$(sed -n 's|^[[:space:]]*<revision>\([^<]*\)</revision>[[:space:]]*$|\1|p' "$pom")

  if [[ -z "$group_id" || -z "$artifact_id" || ! "$revision" =~ ^(.+)-pentaho-([0-9]+)$ ]]; then
    echo "Module '$module' must declare groupId, artifactId, and a local revision ending in -pentaho-N." >&2
    exit 1
  fi

  base_version=${BASH_REMATCH[1]}
  plain_repository_url=$(sed -E 's#^https://[^@/]*@#https://#' <<<"$repository_url")
  metadata_url="${plain_repository_url%/}/${group_id//.//}/${artifact_id}/maven-metadata.xml"
  http_status=$(curl --silent --show-error --output /tmp/maven-metadata.xml --write-out '%{http_code}' \
    --user "$ARTIFACTORY_USERNAME:$ARTIFACTORY_PASSWORD" "$metadata_url")

  if [[ "$http_status" == "404" ]]; then
    metadata=""
  elif [[ "$http_status" == "200" ]]; then
    metadata=$(cat /tmp/maven-metadata.xml)
  else
    echo "Failed to query Artifactory metadata for $group_id:$artifact_id (HTTP $http_status)" >&2
    exit 1
  fi
  rm -f /tmp/maven-metadata.xml

  highest_release=$(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' <<<"$metadata" \
    | grep -F "${base_version}-pentaho-" \
    | sed -n "s|^${base_version}-pentaho-\([0-9][0-9]*\)$|\1|p" \
    | sort -n \
    | tail -1 || true)

  if [[ -n "$highest_release" ]]; then
    next_revision="${base_version}-pentaho-$((highest_release + 1))"
  else
    next_revision="${base_version}-pentaho-1"
  fi

  sed -i.bak "s|<revision>${revision}</revision>|<revision>${next_revision}</revision>|" "$pom"
  rm "${pom}.bak"
  echo "Prepared $module as $next_revision"
done

IFS=,
echo "modules=${modules[*]}" >> "$GITHUB_OUTPUT"