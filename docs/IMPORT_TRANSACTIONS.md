# Portable import transactions

Portable packages and project format 2 keep their existing serialized shape. The model and public headless engine provide an optional `ResourceImport.Session` for a private COPY/REUSE/SKIP draft.

A session owns snapshots of the incoming package and destination. It assigns COPY identities once, caches the current preview, and retains those identities when resolutions change. Visible collision suffixes can change when other rows change; the preview shows the resulting IDs. `Plan.candidate()` returns an owned copy, and plan row/diagnostic lists are immutable. Changing a returned candidate cannot alter the transaction.

```java
var draft = new ResourceImport.Session(document.data(), portableResources);
draft.choose(incomingUuid, ResourceImport.Choice.SKIP);
var preview = draft.preview();
if (preview.valid()) {
    var diagnostics = document.edit(draft::applyTo);
}
```

Set the document's validator to the application's complete committed-definition validator before editing. Model previews perform structural/semantic model validation; the editor still runs NoiseCompiler validation during the parent transaction. A valid preview alone does not certify an arbitrary enabled expression.

Publication checks that the entire destination still equals the preview base before assigning any resources. Changed settings, resource content, bookmarks or project identity reject a stale session. Refresh by constructing a new session from the current destination and reapplying its choices. Exact Undo back to the original base makes a session current again. Apply remains one document Undo action; failed Apply adds no history. Destination project UUID, seed, bounds and bookmarks remain authoritative.

The desktop uses this model session through an [owned import workspace](IMPORT_WORKSPACE.md). A resolution change invalidates the displayed plan, and its successor must be displayed before Apply. A changed destination now requires explicit Reload Preview with retained choices rather than an automatic rebuild. Cancel/Escape releases the workspace and preserves separate Properties input. This subsequent editor implementation and its deferred definitions are uncompiled and untested under the owner testing pause; the public model session retains the value-based contract above.

## Conflict and exclusion rules

Matching first checks authoritative UUID within the same resource kind and protected root, then visible ID. An earlier visible-ID conflict cannot shadow an exact UUID match. Folders in different protected roots cannot be reused. Reuse remains conservative about stochastic UUID-dependent seeds, operation identities and external references.

SKIP uses a reverse adjacency queue over structural children and procedural dependencies. Each excluded resource enters the queue at most once. Excluding a noise referenced by one placement removes that placement, while preserving its layer and other valid placements. A missing layer-default dependency excludes the layer and all its placements. Missing folder parents exclude descendants.

Compatible REUSE takes its dependencies from the existing destination definition. Skipping the incoming copy of such a dependency does not invalidate that existing resource. Structural SKIP of a parent still excludes its descendants, including descendants with an incompatible REUSE choice; those excluded rows do not require conflict resolution. Unknown, null and operation-level choices are rejected. Placements remain represented through their layer row.

This extension changes no noise evaluation, missing-override inheritance, file schema or registry contract. It does not certify the full interactive import acceptance matrix.

## Validation

`ResourceImportSessionTest` covers stable identities, owned snapshots, stale publication, exact-base restoration, invalid choices, UUID precedence, protected roots, selective placement exclusion, default dependency exclusion, parent exclusion, REUSE dependency boundaries, failed publication and one-entry Undo/Redo.

`ImportSessionConsumer` compiles against the headless engine JAR alone. Its explicit fixture expects every voxel occupied after importing a constant-one DENSITY SET placement, EMPTY after changing the inherited layer action to NO_CHANGE, and occupied again after Undo. It checks negative chunks at roots 2, 4 and 8 and the absence of baked placement overrides. Both platform CI jobs run it after implementation, alongside the existing clean build, native references and binary reproducibility checks.
