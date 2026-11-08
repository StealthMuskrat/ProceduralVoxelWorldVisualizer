# World operation cancellation — unvalidated

W05-C10 covers the between-operation checkpoint requirement in specification 13.5.7, with complete immutable publication in 1.4.2–1.4.3 and 13.4.1–13.4.2. Chunk generation previously checked its request scope before each voxel, then evaluated that voxel's complete world-operation sequence without another check. Cancellation during one operation could therefore leave subsequent operations running, including a field that fails before the next voxel checkpoint.

The private WorldPlan sampling traversal now receives the generation scope and checks it before each operation, after its stage-limit check and before profiling or evaluation. This also covers transitions between enabled layers because they share the compiled operation sequence. Final chunks and stage snapshots share this path. Direct point/stage-sample/trace replay passes no request scope and retains its existing behavior.

The column-prefetch inventory now checks before each operation too, including skipped/noncached entries. Prefetch already checked at each row; the added boundary moves rejection ahead of the next operation's column-array allocation and profiler start. It does not fix a missing row checkpoint. Existing Y intersection, row/cell checks and final publication guards remain.

Cancellation before an operation starts prevents that operation's evaluation and raw profiler event. A previously completed operation keeps its raw success event; enclosing generation/stage work reports cancellation. Existing work-contribution flushing retains its enclosing-outcome policy. Parent-supplier faults still propagate through the existing stage/subscriber policies. An uncancelled domain failure keeps its original source identity. More frequent cooperative polls have no measured performance result, and host suppliers must remain cooperative. No public signature, numeric formula, stage ordering, authored format, schema, registry, archive layout or editor dependency changes.

## Deferred regression definitions

Five `WorldOperationCancellationTest` definitions use public engine, profiler and cancellation APIs. A full-stride capture observes completion of the first constant-field operation; a passive parent then requests cancellation before the second operation. That second field is `sqrt(x)` on a negative-X chunk. Per-voxel evaluation without the new boundary reaches its domain failure, while the new boundary must return cancellation. Cached prefetch checks additionally require rejection before the second raw prefetch event. No private reflection, stack matching, injected evaluator, sleep or wall-clock race selects the boundary.

The definitions cover cached 2D columns, identity-transform 2D per-voxel fallback and 3D fields. Roots 2/4/8 and same/cross-layer async routes define 36 cancellations; layer-stage paths define nine more. Three latch-held stage cases cover explicit cancellation during a normally false parent poll with once-only callback/flag behavior. Three parent-fault cases retain exact Throwable identity; uncancelled domain failures and healthy retries remain controls. Admission/staging/cache counters and prior complete snapshots must survive all cases.

`WorldOperationCancellationConsumer` joins the existing engine-JAR-only `CancellationOwnershipConsumer` command with separate expectations:

| Boundary | Deferred dimensions |
| --- | --- |
| Cooperative cancellation | 45 cases: 36 async requests and nine layer-stage requests; 15 prefetch and 30 per-voxel boundaries |
| Independent fault controls | Three exact parent faults, three uncancelled expression-source faults and three healthy retries |
| Retained complete output | 51 chunks after their contexts/engines close; 9,144 exact cells, duplicate-free occupied iteration over the same 9,144 cells, occupied statistics and exact `[0,2]` palettes |
| Caller ownership | Complete project JSON unchanged by cancellation, compilation, faults or retries |

Healthy chunks have nonnegative X and both SET/ALL operations select the second semantic voxel; expected runtime type 2 is independent of the generated result. The negative-X failing field supplies an adversarial boundary check. The replay does not certify all roots, shared-subscriber races, full region delivery, preemption inside one field/expression or performance. Existing concurrency/region/algorithm definitions remain necessary.

A future successful execution writes `cancellation-ownership/world-operations/operation-cancellation-report.json`. Existing manual-only Linux/Windows commands already compile all example sources and invoke the parent consumer. No workflow changed or dispatched; prior report dimensions remain separate and no new report exists.

## Status

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content/metadata checks add no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. The wider W05 cache, scheduler, cancellation, region and performance audits remain open. Publication remains pending on local main.
