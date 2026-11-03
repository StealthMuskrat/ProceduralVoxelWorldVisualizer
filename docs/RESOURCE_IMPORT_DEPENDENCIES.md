# Import dependency compatibility — unvalidated development

This main-branch implementation supports specification 3.3.2 and 3.4.2/3.4.3/3.4.6 and A06/A07/A08/A22. Compilation, JUnit, independent-client execution, checkers, native probes and workflows remain paused. The last observed validated baseline remains 922 tests / 120 suites.

## Reuse follows the referenced definitions

Previously, an incoming resource could compare equal to an existing definition when its external reference UUIDs matched, even if the package included changed definitions under those UUIDs. For example, destination leaf=2, middle→leaf and field→middle compared equal at field when the incoming leaf became 5. Choosing Reuse for field retained the destination closure and therefore produced 2, silently substituting older behavior for the incoming definition.

ResourceImport now compares the complete reachable definition graph before offering Reuse. Equal external UUIDs still require equal kinds, protected roots, parameter/binding definitions, voxel semantic IDs and referenced definitions whenever the incoming package supplies them. This includes references inside identity-like input names, membership types, layer defaults and placements. Existing local item/parameter/curve identity normalization, seed-sensitive noise identity rules and operation identity checks remain in force. Organizational parent folders remain outside procedural equality.

A package may intentionally retain an external destination UUID without supplying its definition. That reference remains compatible when the destination contains the referenced resource; an absent destination resource cannot establish equality. Project seed and Y bounds remain destination-owned settings and do not become imported resource definitions. Reuse continues to retain existing dependencies even if an identical incoming dependency is separately skipped.

The comparison uses a per-preview graph shared across all rows, caches normalized definitions and completed comparisons, and follows dependency edges iteratively. Failure propagates back through shared branches and cycles; dependency depth does not consume recursive comparison stack. Equal cyclic definitions may compare equal, but the existing complete candidate validator still rejects the invalid project. The iterative guarantee applies to this equality traversal, not every other project validator or JSON operation.

Incompatible retained/API Reuse choices produce the existing INCOMPATIBLE_REUSE diagnostic and block publication. Switching to Copy retains the session's stable copied UUIDs, rewrites the complete dependency closure, keeps collision-safe names and publishes one document transaction. No public signatures, package/project versions, schemas, stored field values or editor layouts change. Existing source/destination definitions, saved checkpoints and private drafts remain untouched during preview and failed Apply.

## Deferred definitions

Fourteen ResourceImportDependencyTest definitions cover UUID/name root matching, a changed transitive leaf, equal graphs with distinct local items, identity-named input references, unrelated changes, voxel semantic-ID/color changes, placement dependencies, intentionally external references, absent targets, kind/dimension changes, organizational folders, equal/unequal cycles, reversed row order, shared branches, a 4,097-resource chain, stable Copy publication and exact Undo/Redo. All remain uncompiled and unexecuted.

The existing engine-only ImportCompatibilityConsumer command now includes a second replay under its dependencies output directory. Destination leaf/middle/field each equal 2; the package changes only leaf to 5 while retaining the three UUIDs. Reuse of field must fail without changing the active document, lifetime, checkpoint or history. Copy must retain stable preview identities, preserve all original definitions/settings and reused stone type, rewrite both alias references and the new placement, preserve sparse overrides, save/reload exactly and Undo/Redo as one transaction.

Independent expectations define 189 exact scalar observations and 588 exact signed-position voxel observations at root sizes 2/4. Before import the NO_CHANGE world is EMPTY. After Copy the new SET placement with threshold 3 selects every sampled X/Z inside inclusive Y=-2..2; incorrectly reusing the old closure would select none. The replay retains input/saved byte equality and writes before/after projects plus dependency-reuse.json. Its existing Linux/Windows manual-only commands already compile and invoke the expanded consumer; no workflow edit or dispatch is needed. The earlier 63-field/588-cell binding-name replay remains intact.

These definitions provide no passing evidence. Full semantic/import/native input/platform/Windows 11, representative performance and final release acceptance remain pending. Main remains authoritative and no development branch was created.
