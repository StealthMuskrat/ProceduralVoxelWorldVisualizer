# Column prefetch and effective Y bounds

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, checker, packaging operation or workflow ran. The last observed baseline remains **922 tests / 120 suites**. No A01–A36, Windows 11 or release gate advances.

## Preserve the existing Y rejection before sampling

WorldPlan.sample rejects a placement outside the intersection of project and resolved placement Y bounds before evaluating its primary field, coordinate transforms or drivers. The 2D column prefetch path previously evaluated every cacheable placement across the chunk's X/Z columns without this guard. A negative-X `sqrt(x)` field or an overflowing sampling transform could therefore fail a whole chunk or stage pair even though every voxel rejected that placement by Y. Trace/per-voxel paths already skipped it. Earlier valid writes could not be published because this unnecessary prefetch failed first.

The prefetch path now evaluates an operation only when the inclusive intersection of all three intervals is nonempty:

| Interval | Endpoints |
| --- | --- |
| Logical chunk | `chunkY * rootSize` through that origin plus `rootSize - 1` |
| Project | compiled project Min Y through Max Y |
| Placement | resolved layer defaults and explicit placement minY/maxY overrides |

Chunk endpoint arithmetic uses long. The maximum lower endpoint must be less than or equal to the minimum upper endpoint. Testing chunk overlap with each interval separately is insufficient: a chunk may span both a project singleton at Y=0 and a placement singleton at Y=1 while their combined intersection remains empty.

An excluded operation retains a null column-cache row; the existing per-cell Y guard prevents access/evaluation. Earlier committed voxel state is preserved. A single eligible Y cell, including contact at either chunk endpoint, still permits prefetch. Numeric/domain failures in an eligible column retain their existing EvaluationFailure identity, cancellation/failure cleanup and complete-output publication requirements. Stage Before/After uses the same guarded prefetch path.

The existing 2D representative-Y convention, field evaluation order and arithmetic remain unchanged. Noncacheable/Y-dependent placements already use the per-cell guard. This change implements existing Y rejection; it does not move probability, predicates or field gates before sampling. Snapshot/field compilation reuse, registry/schema/archive formats and public signatures remain unchanged. Staging estimates remain conservative and unchanged.

Excluded columns now produce no FIELD_PREFETCH sample or prefetch contribution. Ordinary operation visits still perform their Y checks and retain their existing work attribution. No performance measurement is claimed. The wider world-use, engine and profiler audits remain open.

## Deferred regression definitions

Five ColumnPrefetchBoundsTest definitions cover excluded positive/negative ranges, disjoint project/placement singletons and both signed-int Y extremes; domain failures and coordinate overflow; full caller JSON preservation; explicit placement overrides and reset with an older retained snapshot; inclusive bottom/top contact retaining failures and releasing staging; prefetch attribution alongside retained rejected-operation work; and identity-transform per-voxel equivalence through asynchronous collection under both failure policies. Exact expected voxels are independently EMPTY outside project Y and solid inside it. All definitions remain uncompiled/untested.

ColumnPrefetchConsumer is a separate engine-JAR-only definition invoked from WorldCompilationConsumer, itself reached by the existing ProjectParseConsumer command. Five Y domains, roots 2/4, workers 1/3 and three sampling modes define **60 excluded-placement worlds**. Modes cover cacheable domain failure, identity-transform per-voxel fallback and cacheable coordinate overflow. NO_CACHE/bounded cache, CONTINUE/FAIL_FAST and completion/coordinate region orders are represented. The replay defines **120 traces/direct comparisons, 60 collected regions and 60 profiler captures** with only the eligible fill placement contributing prefetch samples.

Asynchronous chunk and collection references define **120 retained final-chunk observations / 4,320 exact cell expectations**; cached paths deliberately retain aliases through separate API results. Sixty Before/After pairs define **120 stage-snapshot observations / 4,320 exact cell expectations** after context/engine/result closure. Sixteen additional worlds override placement bounds to touch a chunk's inclusive bottom or top; final/stage requests define **32 eligible failure checks** with original source identities, no partial cache output and released staging. These are planned dimensions, not observed results.

The future report is `project-parse/world-compilation/column-prefetch/column-prefetch-report.json`; no report has been generated. Existing manual-only Linux/Windows commands already compile all example sources and invoke the parent consumer. Prior world-compilation, snapshot, parse and native replay dimensions remain unchanged. Source review and git whitespace/content/metadata checks are the available verification.
