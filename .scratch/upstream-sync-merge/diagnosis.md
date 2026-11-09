# Mika upstream sync merge failure

Status: resolved

## Symptom

The `MP workspace upstream sync and release` workflow stopped in `Merge Mika upstream` because Git reported conflicts in five overlapping chat and update integration files.

## Reproduction

Before resolving the conflict, this command exited with status 1 and reported the same five paths as the workflow:

```sh
bash tools/ci/check-upstream-merge.sh 5653c03cddd47fee203f80fec0fc5177d6e7e2e9 0c172724f3f0c96f7345262588072f6e95db1138
```

The conflicting paths were `ChatCopy.kt`, `ChatScreen.kt`, `MainActivity.kt`, `UpdateDownloadControls.kt`, and `ChatCopyTest.kt`. The conflict is reproducible from the fixed commits without relying on local index or worktree state.

## Root cause

The Mika upstream update changed the same chat composer, starter examples, share-intent lifecycle, and update UI regions that MP customizes for test-case suggestions, its test-workspace entry, and its own release repository. Git's normal three-way merge could not infer how to combine those product behaviors, so the workflow correctly stopped before tests, signing, or publishing.

## Resolution

Resolved the merge by retaining MP's test-case entry and suggestion integration while incorporating upstream share-draft, composer, and update UI changes. The examples remain three distinct kinds. The shared updater uses the build's `UPDATE_RELEASE_REPOSITORY`, so MP builds continue to download from the MP release source while Mika builds retain their configured official source. Conflict protection in CI remains enabled.

The regression check in `tools/ci/check-upstream-merge.sh` uses Git's real three-way merge implementation and does not change the index or working tree. It should pass for the merged commit against the upstream revision already included in that commit.

## Verification

- `:core:test`
- `:app:testDebugUnitTest`
- `:app:assembleWorkspace`
- `apksigner verify app/build/outputs/apk/workspace/app-workspace.apk`
- `git diff --cached --check`
- No unresolved paths or conflict markers remain.
