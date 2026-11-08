# GitHub publication and original checkpoint recovery

The owner authorizes publication of completed main batches through the connected GitHub plugin. Command-line Git in this workspace has no transport credentials; that does not remove the plugin's repository write access. Use the plugin's Git object tools when needed rather than leaving completed batches only in the local workspace.

## Publication procedure

1. Read the current remote main head and require that it is the expected publication base. Preserve the testing pause and manual-only workflow.
2. Commit the completed local batch with [skip ci]. Prepare its file blobs/tree through the GitHub plugin and require the returned tree SHA to match the local committed tree exactly, including modes and deletions.
3. Create the GitHub commit on the observed remote parent. Preserve the batch message, original checkpoint SHA and raw local commit record; API-generated author/committer metadata can produce a different commit ID.
4. Update main without force, using the observed expected head. A changed remote head requires reconciliation rather than overwriting it.
5. Fetch and verify the published commit/tree. Before aligning local main, require a clean worktree, verified content equality and a preserved checkpoint ref. Verify the resulting local/remote heads and clean worktree before reporting publication complete.

The first catch-up publication replays 49 pending batches in order from 6f76999b0732f4e35e43c0e40f31517af0002ac0. Every replayed commit has the exact original file-tree SHA and an original checkpoint reference in its message. The API commit IDs differ from the local checkpoint IDs. The additional publication-policy commit records this procedure and the recovery manifest; it does not change application behavior.

## Exact original commit recovery

The manifest at docs/recovery/local-checkpoint-commits.json records the original head, base, raw commit objects encoded as base64 and equivalent published commit IDs. The replayed history contains the matching original file trees, so a full clone contains their blobs and trees. Recreate the original commit objects in order and retain them under a checkpoint ref without moving main:

```sh
git fetch origin main
python3 - <<'PY'
import base64, json, pathlib, subprocess
record = json.loads(pathlib.Path('docs/recovery/local-checkpoint-commits.json').read_text())
for entry in record['commits']:
    raw = base64.b64decode(entry['raw_base64'])
    restored = subprocess.check_output(['git', 'hash-object', '-t', 'commit', '-w', '--stdin'], input=raw).decode().strip()
    if restored != entry['sha']:
        raise RuntimeError('Original commit identity mismatch')
subprocess.run(['git', 'update-ref', 'refs/checkpoints/before-github-publication', record['head']], check=True)
PY
```

These are Git metadata recovery instructions, not application validation. No compiler, Gradle, tests, native probe, consumer, schema/packaging checker or workflow is dispatched by publication. The historical 922-test/120-suite baseline and acceptance/release gates do not advance. Application changes remain uncompiled and untested under the owner's pause.
