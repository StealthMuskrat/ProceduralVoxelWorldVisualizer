# Indexed resource transfer — unvalidated development

This batch supports specification 2.8.2, 3.3.2, 3.4.1–3.4.6 and 21.7.4, with A06–A08 coverage definitions. No compiler, JUnit, native probe, independent consumer, packaging command or workflow ran. The owner testing pause and last observed validated baseline of 922 tests / 120 suites remain unchanged.

## Dependency selection

ResourceGraph.selection previously rediscovered the entire resource catalog on every closure pass and searched the catalog for each referenced UUID. A reverse-authored dependency chain required repeated full scans. Selection now captures the catalog once, builds the structural child adjacency once and, when dependencies are enabled, indexes resource UUIDs once. A queue follows each newly included existing resource's references once. Shared dependencies and cycles terminate through the included identity set; missing references remain explicit in the payload for validation rather than becoming invented resources.

Explicitly selected folders/layers still expand their structural subtree. A folder reached only as dependency context does not include its unrelated descendants. Selecting placements includes the carrier's defaults and folder ancestry, but does not implicitly select all of the carrier's other placements or their dependencies. Carrier operations retain authored execution order and sparse overrides; absent overrides continue to inherit live defaults. With dependencies disabled, external UUID references remain external, preserving clipboard semantics.

Payload arrays retain source order independently of queue traversal order. Child and root order metadata is pruned against the payload after creating an owned copy, preserving the caller's lists and binding objects. Selection order and duplicate membership do not reorder authoring arrays. No public signature, portable version-1 envelope, project schema or generated scalar/voxel semantics changes.

## Import conflict and target lookup

ResourceImport creates per-plan indexes for existing resources, keyed separately by kind/root/UUID and kind/root/exact visible ID. UUID matching remains authoritative even if an earlier resource shares the incoming visible ID. Case-sensitive names, kind/root segregation and first-authored same-name folder matches remain unchanged. The indexes do not broaden semantic Reuse eligibility: the existing iterative dependency-definition compatibility graph remains authoritative, incompatible Reuse still blocks publication, and existing definitions are never replaced.

A second index over the complete validated candidate supplies final row target names/UUIDs. Copy identities remain stable across one Session's choice changes; skipped/dependent rows retain null target metadata. Plan ownership, cached preview publication, destination settings, stale-base rejection and single-transaction history retain their existing contracts. These indexes exist only for a single selection/plan; later operations observe fresh resources.

This removes repeated catalog scans from closure discovery, conflict matching and final target lookup. It is not a measured responsiveness claim or a linear-time claim for complete export/import: JSON reference extraction/copying, canonical comparison, name collision handling, order reconciliation, whole-candidate validation and GUI rendering retain their own costs. No benchmark ran.

## Deferred definitions

Ten ResourceDependencyClosureTest definitions cover a 4,097-resource reverse-authored disabled DAG, external clipboard references, shared diamonds and identity-named inputs, dependency-folder isolation and child order, selected-placement carrier defaults/sparse overrides/execution order, whole-layer selection, explicit cycles, unresolved UUIDs, duplicate selection/root order and caller-owned payload isolation.

Six ResourceImportIndexTest definitions cover a 2,048-row complete Reuse catalog, UUID-before-name precedence, case/kind/root matching, first-authored folder name matches, Copy/Reuse/Skip target metadata and stable Session identities with complete one-step Undo/Redo.

The existing engine-only ProjectParseConsumer command additionally defines an indexed-transfer replay. It exports a 2,049-resource reverse-authored disabled chain while excluding an unrelated 3D resource, writes/reads a version-1 portable envelope, reuses the authoritative UUID of a renamed leaf, copies the remaining 2,048 resources and checks every final target/reference in source order. Changing a root choice from Copy to Skip to Copy must retain Copy UUIDs. Apply must match preview, one Undo/Redo must restore complete snapshots, destination seed/bounds must remain intact, and public save/load must retain the complete candidate. Independent expectations require 63 exact constant .25 samples and 294 EMPTY voxel observations over signed coordinates at root sizes 2/4. Input project/package bytes and caller-owned source/payload must remain unchanged. Future output includes selection.resources.json, imported.pvproj.json and indexed-transfer.json. Existing parsing, prepared-Open and cycle replays remain intact; existing manual-only commands already invoke the expanded source.

All sixteen definitions and the replay remain uncompiled/unexecuted. Measured large-project latency/memory, broader native clipboard/import/export gestures, final validation, Windows 11 hardware and release acceptance remain pending. Main remains authoritative; no development branch was created.

The later unvalidated [folder validation batch](FOLDER_VALIDATION_GRAPH.md) corrects the indexed-transfer replay bounds field spelling to Project.minY/maxY. The replay remains uncompiled/unexecuted.
