# Cancellation before staged snapshot finalization

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, independent consumer, checker, packaging operation or workflow ran. The observed baseline remains **922 tests / 120 suites**. No A01–A36, Windows 11 or release gate advances.

## Observe interruption after the final callback

VoxelChunk's builder checks interruption when entering generation/decoding tree nodes. A sampler or mapping callback could interrupt its thread on its final call and then return a valid value. With no later node to enter, the builder previously captured its arrays and returned a new snapshot despite withdrawal during the last callback. Another thread interrupting a held final callback had the same gap.

The shared Builder.finish now calls the existing checkpoint before capturing private arena/palette arrays into a VoxelChunk. This covers generated storage, decoded trees and staged immutable replacements. A normally completed final callback cannot hide an already-set interrupt flag; CancellationException propagates without clearing it. No new sampler/mapping call, per-cell checkpoint, mutable published array or external publication action is introduced.

Callback RuntimeExceptions/Errors and invalid-value checks that already failed before finalization retain their original priority and identity. Already published snapshots remain readable and unchanged. Retry is explicit after the caller clears interruption: raw-tree callers supply fresh/reset input, while an archive Reader already seeks for each read and preserves decoder cancellation through its existing boundary. Caller-owned input/output is not rolled back by this finalization check.

The checkpoint observes interruption before final array capture; it does not preempt a blocking callback or lock out interruption after that read. Immediate uniform creation, same-uniform no-op/full-box fast paths, point reads and metadata keep their existing behavior because they do not finalize a private staged builder. This is not a new cancellation policy for every storage read or encoder operation.

Public signatures, root/type/coordinate validation, callback traversal and numerical values, exact tree/arena/palette layouts, schemas/registries and archive versions remain unchanged. There is one additional flag check per staged finalization; performance remains unmeasured. The wider storage/engine cancellation and acceptance audits remain open.

## Deferred module definitions

Six SnapshotPublicationTest definitions cover final sampler interruption across EMPTY, uniform, patterned packed, many-type dense and sparse shapes at roots 2/4/8; final mapping interruption against independently encoded uniform/dense/palette/branch trees plus an interrupting final EMPTY input byte; latch-held final sampler/mapping callbacks interrupted by another thread; original RuntimeException/Error, invalid-value and malformed-input priority; older snapshot/palette/statistic ownership through failed generation and healthy edit retry; and existing immediate uniform/no-op/full-box/read behavior under interruption. Harness cleanup clears only its deliberately set flag. No definition has run.

## Deferred independent consumer

SnapshotPublicationConsumer is invoked by the existing engine-JAR-only ArchiveReaderRecoveryConsumer/ArchiveRecoveryConsumer command. Self-interruption and latch-held callbacks define **30 sampler cancellations and eight mapping cancellations**. Four independent wire fixtures specify uniform, legacy dense, v2 palette and eight-child branch trees, including their exact final mapping counts. Four original callback-fault and two invalid-value controls retain pre-finalization failure policy; four immediate-path controls retain the earlier fast behavior.

Successful baselines, healthy retries and independent single-cell edits define **72 retained chunks / 13,456 exact cell expectations** after the worker scope closes. Integer coordinate formulas and independent wire values supply expected cells, without compiler/generated-output oracles. Occupied iteration must visit exactly the expected nonzero cells once, with correct coordinates/types; public occupied statistics must agree. Mutation of an exposed palette copy cannot change a retained edit, and original palettes/statistics remain unchanged. Counts are deferred definitions, not runtime results.

The future report is `archive-recovery/reader-recovery/snapshot-publication/snapshot-publication-report.json`, alongside four independent input-tree binary fixtures. No report or binary fixture has been generated. Existing manual-only Linux/Windows commands already compile all example sources and invoke the parent consumer. Earlier archive/cancellation/world/native replay dimensions remain unchanged. Source review and git whitespace/content/metadata checks are the available verification.
