#!/usr/bin/env bash

set -euo pipefail

# Assigns release versions to Maven modules selected by the workflow.
#
# Change detection and the publish allowlist are handled by publish-maven.yml
# before Maven setup. This script has one responsibility: query Artifactory and
# update each selected module to its next unused `-pentaho-N` release number.
#
# Arguments:
#   1. Comma-separated module paths, e.g. `pentaho-shaded-netty,other-module`
# Environment:
#   PUBLIC_RELEASE_REPO_URL: target Artifactory Maven repository
#   ARTIFACTORY_USERNAME / ARTIFACTORY_PASSWORD: metadata read credentials
module_list=${1:?comma-separated module paths are required}
repository_url=${PUBLIC_RELEASE_REPO_URL:?repository URL is required}

# -----------------------------------------------------------------------------
# 1. Read existing releases from Artifactory
# -----------------------------------------------------------------------------
# Maven metadata lists versions already published for one artifact. Returning an
# empty string for HTTP 404 means this artifact has never been published; that is
# normal for a new upstream version. Any other HTTP error must stop the workflow
# so it cannot accidentally reuse a release number.
fetch_metadata() {
  local metadata_url=$1 response http_status

  response=$(curl --silent --show-error --write-out $'\n%{http_code}' \
    --user "$ARTIFACTORY_USERNAME:$ARTIFACTORY_PASSWORD" "$metadata_url")
  http_status=${response##*$'\n'}

  case "$http_status" in
    200) printf '%s' "${response%$'\n'*}" ;;
    404) return 0 ;;
    *) echo "Failed to query Artifactory metadata (HTTP $http_status): $metadata_url" >&2; return 1 ;;
  esac
}

# Given metadata and an upstream base version such as `4.1.137.Final`, return
# the next Pentaho counter. For example, releases ending in `-pentaho-1` and
# `-pentaho-2` produce `3`; no matching release produces `1`.
next_release_counter() {
  local metadata=$1 base_version=$2 release counter highest=0

  while IFS= read -r release; do
    [[ "$release" == "${base_version}-pentaho-"* ]] || continue
    counter=${release##*-}
    [[ "$counter" =~ ^[0-9]+$ && "$counter" -gt "$highest" ]] && highest=$counter
  done < <(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' <<<"$metadata")

  echo $((highest + 1))
}

# -----------------------------------------------------------------------------
# 2. Assign a new release number to every selected module
# -----------------------------------------------------------------------------
IFS=',' read -r -a modules <<< "$module_list"

for module in "${modules[@]}"; do
  pom="$module/pom.xml"

  # Ask Maven for resolved coordinates. This matters because a POM can define
  # `<revision>${netty.version}-pentaho-1</revision>`; Maven resolves it to the
  # actual version used for deployment, e.g. `4.1.137.Final-pentaho-1`.
  mvn_evaluate=(mvn --batch-mode --quiet -pl "$module" -DforceStdout help:evaluate)
  group_id=$("${mvn_evaluate[@]}" -Dexpression=project.groupId)
  artifact_id=$("${mvn_evaluate[@]}" -Dexpression=project.artifactId)
  version=$("${mvn_evaluate[@]}" -Dexpression=project.version)

  if [[ -z "$group_id" || -z "$artifact_id" || ! "$version" =~ ^(.+)-pentaho-[0-9]+$ ]]; then
    echo "Module '$module' must resolve groupId, artifactId, and a version ending in -pentaho-N." >&2
    exit 1
  fi

  base_version=${BASH_REMATCH[1]}
  metadata_url="${repository_url%/}/${group_id//.//}/${artifact_id}/maven-metadata.xml"

  # Artifactory's metadata tells us whether this is release 1 of a new upstream
  # version or the next release of an already published one.
  metadata=$(fetch_metadata "$metadata_url")
  next_counter=$(next_release_counter "$metadata" "$base_version")

  # Update only N in the module-local revision. This preserves a property-based
  # base version, e.g. `${netty.version}-pentaho-1` becomes
  # `${netty.version}-pentaho-2`.
  sed -i.bak -E "s|(<revision>.*-pentaho-)[0-9]+(</revision>)|\1${next_counter}\2|" "$pom"
  rm "${pom}.bak"
  echo "Prepared $module as ${base_version}-pentaho-${next_counter}"
done
