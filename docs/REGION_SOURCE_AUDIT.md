# Region source audit — execution pending

This slice records the headless dispositions for 11.3.1–11.3.5, 11.4.1–11.4.5 and 13.5.1–13.5.9. No new application-code defect is recorded in the inspected paths and no region implementation or policy changes are introduced. W05 remains open; source disposition and an unexecuted definition do not establish runtime conformance.

## Leaf inventory

| Leaf | Inspected source disposition | Verification owner / remaining scope |
| --- | --- | --- |
| 11.3.1 | WorldContext point lookup returns EMPTY outside compiled inclusive Y bounds. | Engine/world-use definitions; full coordinate/caller coverage remains deferred. |
| 11.3.2 | WorldPlan returns an immutable uniform EMPTY root when its whole Y interval is outside the project. | Existing world-use/storage definitions; all-root/overflow boundaries remain deferred. |
| 11.3.3 | Generated per-cell sample checks project Y before world operations; intersecting chunks keep their full logical size. | New partition replay checks every cell, including clipped Y; wider operation/root combinations remain deferred. |
| 11.3.4 | StreamingTask keeps requested bounds and a separately Y-clipped optional effective region in RegionSummary. | New nonempty stream/sync/async replay; existing progress and bounds definitions. |
| 11.3.5 | Empty effective bounds yield zero scheduling/delivery and successful zero-count completion. | New 18 empty controls; existing RegionProgressTest. |
| 11.4.1 | RegionBounds validates inclusive min/max ordering; request enumeration uses independent per-axis floor division. | Existing coordinate/count definitions and new explicit boundary-table replay. |
| 11.4.2 | Each delivery is a complete VoxelChunk intersecting the effective region; X/Z edge consumption belongs to callers. | New occupied-outside-requested-X/Z controls and full retained-cell oracle. |
| 11.4.3 | COORDINATE consumes the first pending coordinate; COMPLETION chooses a completed future from bounded pending work. | New ordered/set coverage; existing scheduling/order definitions, concurrency still deferred. |
| 11.4.4 | Coordinator lazily increments coordinates with at most maxInFlightChunks pending entries, not a materialized whole-region list. Active streams/workers/queue remain bounded. | Existing near-long-capacity/cancellation/backpressure definitions; signed-long count overflow is a typed representation boundary, not a newly introduced volume budget. Full limits/stress remain deferred. |
| 11.4.5 | Collection computes a checked conservative estimate, reserves shared bytes before scheduling and rejects impossible admission. | Existing CacheAdmissionTest/RegionCompletionTest; new successful lease/empty controls; pressure measurements remain deferred. |
| 13.5.1 | Immutable RegionRequest owns bounds, policy, order, priority and validated in-flight budget; legacy/default constructors delegate to it. | Existing request/scheduler definitions and new budget/order/policy replay. |
| 13.5.2 | streamRegion registers a task and starts a separate virtual coordinator; owner cancellation/progress/private completion stay on that task. | Existing RegionCompletionTest/RegionProgressTest and new healthy/empty terminal checks. |
| 13.5.3 | ConsumerAcknowledgement observes host stages; a pending entry is removed only after acknowledgement or terminal handling. | Existing opaque-stage, held acknowledgement, cancellation and fault definitions; new completed acknowledgements do not replace them. |
| 13.5.4 | Sync and async collection share estimation, stream, summary and RegionResult lease ownership; async terminal withdrawal observes its own request future. | Existing collection failure/ownership definitions; new identical semantic expectations for both routes and idempotent close. |
| 13.5.5 | Defaults are CONTINUE/COMPLETION; per-chunk errors apply CONTINUE/FAIL_FAST without automatic side-effect retry. | Existing delivery/error/failure details suites; new healthy equivalence covers both policy values only. |
| 13.5.6 | Coordinator publishes one immutable TaskProgress partition and terminal requested/effective/failure metadata; failure details are bounded separately from long counts. | Existing RegionProgressTest and new exact terminal partitions; full failure/concurrent-reader execution remains deferred. |
| 13.5.7 | Task cancellation prevents further admission, cancels subscribers and checks generated operations/rows/cells and acknowledgement waits; cleanup precedes private completion. | Existing cancellation, operation and completion definitions; cancellation/preemption inside one host field/callback remains cooperative. |
| 13.5.8 | Each region chunk owns one subscriber; withdrawal does not retire a different subscriber's shared work. | Existing subscriber, collection withdrawal and post-poll membership definitions. |
| 13.5.9 | Generation and delivery phases are distinct; delivery faults count once, release the pending entry and apply policy without retry. | Existing acknowledgement/Error/diagnostic/failure details suites; broader host-stage implementations remain deferred. |

The inspected owners are RegionRequest/RegionBounds, WorldContext point/stream/collect/async/await/close and StreamingTask, ConsumerAcknowledgement, RegionResult/RegionSummary/TaskProgress, WorldPlan Y guards and shared request cancellation. Existing module suites retain cancellation, hostile/opaque acknowledgements, overload, reentrant slot release, failed delivery, shared peers, terminal-future mutation and concurrent progress coverage. No duplicate module suite is added. Host callbacks and worker termination remain cooperative; neither a source audit nor one healthy replay certifies all races or resource behavior.

## Independent deferred partition replay

RegionPartitionConsumer joins the existing engine-JAR-only RegionCompletionConsumer command. Roots 2/4/8, default seed 17/override −17, both orders, both failure policies, in-flight budgets 1/3 and stream/sync/async routes define **144 healthy regions**. Each requested box crosses negative/exact chunk boundaries and project Y bounds −1..2. An explicit independent coordinate table expects 36 intersecting chunks at root 2 and 24 at roots 4/8; it does not reuse engine enumeration or chunkCount as the oracle.

All routes must preserve requested bounds, report the exact clipped bounds, deliver complete roots without duplicates, honor coordinate order when requested, and publish a successful partition with matching totals. The 96 nonempty collections must retain a reservation until idempotent close. Eighteen entirely outside-Y controls must return empty effective regions and deliver/generate nothing; zero-byte collection leases remain zero. Transient in-flight/queue/staging/collection/cache ownership must balance after completion.

The field is nonnegative residue of X + 2Z + 4Y + seed modulo five; DENSITY selects runtime type 1 for residue at least two. Independent integer floor-quotient arithmetic supplies expected cells, with project-Y rejection applied separately. After all contexts/engines close, **4,032 chunk observations / 677,376 cells** must remain exact, including **483,840 Y-clipped EMPTY cells** and occupied X/Z edge cells outside requested bounds. Duplicate-free occupied iteration, statistics and EMPTY state must match that same oracle. Full authoring JSON remains exact. Both failure policies are exercised on healthy input; this does not certify their error paths.

Future successful execution writes `<RegionCompletionConsumer output>/partitions/region-partition-report.json`. Manual-only Linux/Windows commands already compile all example sources and invoke the parent consumer. No workflow changed or dispatched, earlier report dimensions stay separate, and no new report exists.

## Status

All new definitions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git review adds no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Remaining W05 API, scheduler, archive allocation/error, renderer caller and performance inventories, plus all execution/delivery work, remain open. Application signatures, generation math, cache/region policies, schemas, registries and authored/archive formats are unchanged; local-main publication remains pending.
