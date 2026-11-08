# Cache policy source audit — execution pending

This slice reconciles the six headless cache-policy leaves in specification 13.6.1–13.6.6. No application-code defect was found in the inspected paths and no cache implementation/policy changes are introduced. The broader W05 source audit remains open. Source inspection and deferred expectations do not establish runtime conformance.

## Leaf inventory

| Leaf | Inspected source disposition | Deferred verification |
| --- | --- | --- |
| 13.6.1 NO_CACHE | WorldContext.retain returns without retaining; pending shared generation owns its subscribers and retires them before terminal publication. | Independent exact fields/chunks with zero retained entries/bytes; existing subscriber retirement and cancellation matrices. |
| 13.6.2 MEMORY_CACHE | Local limit is the engine budget and ignores cacheBytes. Global reservation/reclamation enforces shared admission; closed/failed cache publication releases its reservation. | Independent zero-local-budget retention; existing global pressure and concurrent admission cases, actual heap/performance measurement. |
| 13.6.3 BOUNDED_MEMORY_CACHE | Access-ordered LinkedHashMap, localCapacity and reclaimCache implement local LRU plus global automatic reclamation. Both reserve/publication phases recheck capacity. | Independent refresh/evict/rebuild controls; existing cross-context pressure and concurrent retain/reclaim definitions. |
| 13.6.4 USER_MANAGED | Neither local capacity nor global reclaim evicts user-managed entries implicitly. Pin requires a retained entry; evict rejects pinned entries, clear preserves pins, and close releases everything. | Independent valid-but-unretained results, pin/clear/unpin/evict; existing global pressure with protected user ownership. |
| 13.6.5 Controls/stats | Public immutable CacheStats includes hits/misses, entries/bytes/pins and eviction/rejection counts. Explicit controls use the same owned cache lock/accounting. | Independent event counts and explicit cleanup; existing impossible collection, pin, closure and concurrency definitions. |
| 13.6.6 Context-aware identity | Each WorldContext has its own position-keyed cache, immutable root/seed options and plan from one private CompiledProject snapshot. Context identity, generation source, effective seed and root are therefore fixed outside the local key. | Independent coexisting seed pairs and retained snapshots; existing snapshot mutation, worker/cancellation and cross-root definitions. |

The inspected entry points are WorldContext request/generate/retain/localCapacity/reclaimCache/cacheStats/pin/unpin/evict/clear/close, NoiseGeneratorEngine reserve/tryReserve/context registration/reclaim/metrics/close, WorldOptions and CompiledProject ownership. Reservation attempts do not hold a local cache lock while reclaiming other contexts, and reclamation runs outside the engine admission monitor. Pending reservations can temporarily exceed the sum of published context cache counters; failed publication releases them. Admission estimates are not exhaustive JVM heap measurements or ownership of caller-retained snapshots.

Existing CacheAdmissionTest already defines six module cases for distinct policies/LRU, user ownership, global automatic reclamation, protected user pressure, impossible collection/close accounting and concurrent retain/reclaim. This slice adds no duplicate module suite. Their current execution is not claimed; the last observed baseline remains 922 tests / 120 suites. The scheduling vocabulary in registries/scheduler-policy.json already matches the inspected implementation and needs no change.

## Independent deferred consumer

`CachePolicyConsumer` joins the existing engine-JAR-only Example command. Three roots (2/4/8), four policies and default-seed 17 versus override −17 define 24 policy-world cases / 12 coexisting seed pairs. The other context's warm entry must survive one context's cache controls and closure. The same compiled source is shared, while cache ownership and effective seed stay separate.

The field is the nonnegative residue of X + 2Z + 4Y + seed modulo seven; DENSITY selects runtime type 1 when that residue is at least three. Expected fields/voxels use an independent integer floor-quotient formula rather than Expression or a second generated result. Canonical residues give identical local patterns at chunk X positions separated by seven. A NO_CACHE probe supplies only the published storage estimate used to size two-entry budgets; it is never the numeric oracle.

Each world defines eight query observations: warm-up, hits, another entry, LRU refresh, a third entry, policy-specific ownership controls and rebuilding after explicit removal. MEMORY_CACHE must retain three entries despite cacheBytes zero; BOUNDED_MEMORY_CACHE must evict/rebuild B while preserving refreshed A; USER_MANAGED must return C successfully without retaining it when full, preserve its pin on clear, and permit removal after unpin. NO_CACHE keeps zero entries. Expected hit/miss, automatic eviction and retention-rejection counts are explicit.

Deferred totals are 162 raw-bit field observations, 138 successful generations, 12 automatic LRU evictions, six retention rejections and **198 chunk observations / 38,544 exact cells** after all contexts/engines close. Observations include repeated references on cache hits; they are not a claim of 198 distinct chunks. Occupied iteration must be duplicate-free and match the independent per-cell expectation, occupied statistics and EMPTY state. Caller project JSON remains exact, and context/engine cache, staging, collection and in-flight ownership must balance after cleanup.

Future successful execution writes `<Example output>/cache-policy/cache-policy-report.json`. Existing manual-only Linux/Windows and standalone commands already compile all example sources and invoke Example. No workflow change or dispatch is needed; earlier report dimensions remain separate and no new report exists.

## Remaining scope and status

The six headless cache leaves now have an inspected source disposition and linked verification definitions. Source coverage here does not close W05's remaining public API, regions, scheduling, archive allocation/error or renderer caller inventories. All-root/policy/worker races, pressure behavior, real allocation/throughput, Windows 11 and release verification remain pending.

New definitions are **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content/metadata checks add no runtime evidence. No A01–A36 or platform/release gate advances. Public signatures, formulas, cache implementation, schemas, registries, authored formats and archive layouts remain unchanged; local-main publication remains pending.
