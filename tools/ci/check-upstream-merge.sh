#!/usr/bin/env bash
# Check the same three-way merge as the sync job without changing the index or worktree.
set -euo pipefail
mp_ref=${1:-HEAD}
upstream_ref=${2:-upstream/main}
git rev-parse --verify "$mp_ref^{commit}" >/dev/null
git rev-parse --verify "$upstream_ref^{commit}" >/dev/null
git merge-tree --write-tree "$mp_ref" "$upstream_ref"
