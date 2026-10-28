# Attributed layer and placement work totals

Status: **implementation awaiting validation** on `codex/development-phase`. GitHub testing remains paused by owner instruction until the program is fully developed. This batch has not been compiled or tested; its regression, native and independent replay definitions are deferred.

Profiler > Capture limits now offers Layer/operation work totals. It is off by default, separate from the existing operation sampling stride. Enabling it times every visited placement boundary during an admitted chunk/stage computation, including rejected predicates and selection checks, plus that placement's column prefetch. It emits bounded per-source aggregates rather than a retained sample for every cell. The selected work series also displays its attributed total across the retained capture, with the existing per-computation mean and percentiles below it.

| Kind | Meaning of one retained value |
|---|---|
| `OPERATION_WORK` | Sum of measured column-prefetch and visited-cell placement intervals for one operation in one chunk/stage computation |
| `LAYER_WORK` | Sum of those same intervals for the operations belonging to one layer in that computation |
| `OPERATION` | Existing every-Nth visited-operation interval; unchanged sampling policy |
| `FIELD_PREFETCH` | Existing inclusive per-placement prefetch interval; retained separately |

Work intervals do not overlap across peer placements in the same computation. Layer totals derive from their operations' exact intervals; adding LAYER_WORK and OPERATION_WORK double-counts them. Existing CHUNK_GENERATION, STAGE, FIELD_PREFETCH and OPERATION intervals overlap attributed work too. These remain CPU-side wall elapsed measurements, including preemption and waits inside an interval. They exclude storage assembly/publication, inter-operation loop gaps, separate scheduling/delivery, and the ledger's recording overhead. They are not thread CPU utilization or exhaustive chunk elapsed totals.

One raw work sample is a sum, not one continuous interval. Its offset is the first measured contribution's start; its value omits gaps between contributions. Its thread/context/project/chunk metadata identifies the owning computation. Series count means the number of retained per-computation sums, not the number of visited cells. A real zero duration is legal; unvisited sources do not acquire invented zero samples. The capture selector and source-navigation actions use stable layer/placement UUIDs from the immutable compiled source, not current editor row numbers or labels.

## Stages, failures and ownership

An exact stage request shares one ledger across its single prefetch and both before/after computations. Work actually repeated in the two prefixes is counted twice; a reused identical prefix is counted once. Direct trace replay and cache hits do not create automatic work ledgers. Empty or vertically excluded computations have no attributed source intervals.

An evaluation failure or cooperative cancellation flushes measured prefix costs with that computation's FAILED/CANCELLED outcome while preserving the existing incomplete-result rejection. Each source's work outcome describes the enclosing computation, rather than asserting that the source itself caused a failure. Conversely, completed computation costs can be SUCCESS even if a later context-closure/cancellation/publication check prevents the final chunk from being retained; CHUNK_GENERATION/STAGE retain their broader outcome scope.

Scopes and ledgers remain bound to the capture that owned their task. Stop, replacement, time expiry and closure discard pending unpublished aggregates. Sample/series/duration caps remain global. A computation can begin during a capture and finish too late to publish. If a global limit is reached while aggregates publish, only the accepted complete per-source sums remain; a stopped capture need not contain every operation/layer pair. Capture state makes that truncation explicit.

## Bounded overhead and admission

Compiled source identities/labels are prepared once with the world plan. Each opted-in generation task owns one ledger, capped at maxSeries distinct operation-plus-layer entries. Entries allocate only when a source is first measured, with no new attribution object per subsequent cell. A full local ledger stops its owning capture with SERIES_LIMIT; generation continues normally and unflushed work is discarded. Labels retain the existing 256-UTF-16 bound.

Before allocating the ledger, engine admission reserves an additional conservative `256 + 256*maxSeries` bytes alongside ordinary staging, at most 262,400 bytes with the current limit. Stages reserve one ledger in addition to their existing two-snapshot allowance. Completion, failure and cancellation release the same reservation through the existing finally path. This additional targeted instrumentation can cause a request to be rejected under a tight hard memory budget; it does not bypass admission or change a completed world result.

Enabling this mode reads the clock at every visited placement and increases instrumentation cost. Operation stride continues to affect individual OPERATION samples only. When this mode is off, the ledger path is null and adds no contribution clock reads or per-cell allocations; ordinary sampled profiling retains its prior behavior. Profiling disabled entirely still creates no runtime capture/ledger state.

## API and exports

`Profiler.Options` gains boolean `worldContributions`; its four-argument constructor and defaults retain false. Record-pattern/reflection clients must account for the added component. JSON capture format remains 1: the new option is structurally optional so historical exports remain valid, and the kind enums add OPERATION_WORK/LAYER_WORK. Raw Sample/Series shapes and RFC4180 CSV columns remain unchanged. Readers that enforce the old enum/option vocabulary must be updated. Project/User Config schemas are unaffected; this is a next-capture choice, never automatically restored as active recording.

Profile filters, source navigation, nearest-rank summaries, atomic JSON/CSV export and project/draft/history isolation apply to the new kinds. No independent on-disk timing stream is created.

## Deferred verification definitions

Seven ledger contracts define exact peer/gap sums, legacy disabled clocks, prefix outcomes, capture replacement/stop/time expiry, local/global bounds and reserved/label limits. Seven engine contracts define exact fake-clock chunk/stage costs including prefetch, stride/worker invariance, failed/cancelled computation costs, chunk admission and the single stage ledger allowance. Two editor contracts define layer UUID navigation without replacing the current private draft and exact retained totals separated by context/metric kind.

WorldWorkConsumer defines an external engine-JAR workflow across two root sizes, async chunks and both stage prefixes, checking 288 canonical cell observations and thirty source sums. Independent Python recomputes every layer from its operations and validates actual JSON/CSV envelopes. WorldWorkProbe defines actual capture checkbox/Start/Stop clicks, per-kind filtering, private-draft-preserving layer navigation and explicit exports. Synthetic positive/negative capture fixtures extend schema definitions. None has run in this batch; representative profiling overhead, hardware performance and the complete GUI/Windows 11 acceptance matrix remain pending.
