# Semantic input identities for conservative compiler sharing

Status: **implemented, awaiting validation** on `codex/development-phase`. The owner has paused GitHub testing until the program is fully developed. This implementation and its deferred regression/engine/reference definitions have not been compiled or executed. No throughput, memory reduction or hardware performance improvement is certified.

The existing processing common-subexpression pass now recognizes equivalent ambient bindings and projected Noise Generator Reference bindings even when separate authored bindings created different evaluator closures. Successful shared processing outputs also carry the same canonical result-slot identity into later pure items, allowing reuse through a diamond of repeated operations.

Typed literal identities continue to use every component's raw double bits. Ambient identities use the exact ambient name. Reference identities include target resource UUID, parent dimension, projection and raw slice bits; different resources, projections and positive/negative zero slices remain distinct. Processing-result identities use the canonical shared slot. Other evaluators retain their original identity. Keys are compared only inside one resource's common-operation map, never globally between resources, contexts or fields.

## Runtime boundaries

Identities are prepared during compilation. They add no per-cell keys, maps or allocations. Runtime reuse still uses the existing primitive value arrays and Context epoch stamps. A result is stamped only after all of its components have completed with finite values. When an alias executes first, its own instruction/error boundary runs and its complete value is copied to the shared slot. A failed or partially computed value is not published as a shared success.

Each sample epoch, worker thread and separately executable sampled prefix owns its existing scratch/stamps. A prefix evaluated at a neighbor cannot satisfy a parent's current-point value. Failure attribution stays with the first actually executing occurrence or the innermost failing dependency. A successful equivalent value can suppress later duplicate work; it cannot redirect a failure to an earlier unexecuted item. Trace PROCESSING observations name the occurrences that actually executed, so later successful aliases do not emit duplicate observations.

The existing exclusions remain: Expression programs, sampled-field processing, and implicit coordinate/hash/shape operations do not become common items through this change. Equivalent expression syntax is not compared across items. Operand roles/order and authored parameters remain exact key parts; there is no arithmetic reassociation, speculative branch evaluation or invalid-boundary suppression. Different unused projection/slice metadata is conservatively distinguished even when a particular same-dimensional reference would ignore it.

`NoiseCompiler.Statistics` keeps its public shape. `sharedItems` may increase for the newly recognized authored aliases, including unused enabled items, while work/depth remain conservative bounds and scratch-slot allocation is unchanged. Project format and schemas, generated numerical values, public sampler contracts and engine/editor dependency direction are unchanged. Processing registry version 6 adds descriptive `compilerSharing` metadata; the model-generated contract and its future verifier expectations are updated together.

## Deferred verification definitions

`SemanticSharingTest` defines thirteen contracts covering ambient/transitive diamond sharing, first-executed trace identity in both operand orders, distinct ambient names and ordered division, raw literal signed zero, equal/different projected references, separate resource seed domains, raw slice signed zero, selected failure UUID and failed-epoch cleanup, lazy branch pruning, alias-first typed vector success, later-vector-component failure without partial publication, neighbor-prefix isolation, and concurrent epochs plus expression exclusion/immutable compiled snapshots.

`fixtures/semantic-sharing.json` contains a 2D diamond `2*(x+2)^2`, a duplicated 2D-to-3D XZ/slice-3 reference giving `2*x+8*z+14`, and the referenced 3D field `x+2*y+4*z`. No startup resource is added. `SemanticSharingConsumer` defines a public engine-JAR replay with 746 exact raw-bit observations: 578 direct points, 40 strided grid writes with untouched padding and 128 concurrent observations. It checks sharing counts 2/1 and trace occurrence UUIDs against the authored plans.

`verify_semantic_sharing.py` defines independent Python arithmetic/raw-bit, count, schema, trace and registry checks. The fixture is included in the future schema family. Future Linux/Windows engine-consumer steps reference these definitions without altering development-branch trigger exclusions. Nothing has been executed during this batch.

Broader optimizer equivalence classes, exhaustive thread/host/reentrancy stress, source-vector/platform regression replay, representative performance measurements, full GUI/hardware acceptance and final binary reproducibility remain pending. Earlier validation evidence stays scoped to its recorded source.
