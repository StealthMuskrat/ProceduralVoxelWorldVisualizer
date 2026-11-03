# Chunk subscriber retirement before publication

Status: development implementation on main, **uncompiled/untested**. The owner's testing pause remains active. No compiler, JUnit, independent consumer, native probe, checker or GitHub workflow ran. The last observed baseline remains 922 tests / 120 suites; these additions are deferred definitions. No public method signature, serialization format, registry, generation formula or native dependency changed.

## Problem and ownership boundary

An admitted chunk request previously installed retirement as a CompletableFuture.whenComplete callback before returning its future. Dependent callbacks are not an ownership ordering mechanism: a later host callback can execute first and block, retaining the request's cancellation registration, subscriber capacity and abandoned queued work. At the 8,192-subscriber limit, that callback could not submit a successor even though its request already had a public terminal result.

RequestFuture now retires its subscriber before publishing a terminal result. WorldContext removes the pending-future entry, disposes cancellation registration, detaches the waiter from its shared generation, and removes an empty unstarted flight from the queue. Other subscribers retain their shared generation. Retirement runs once, with public completion and dependent callbacks outside ownership locks. The completed future drops its retirement closure so retaining it does not retain its WorldContext through that closure.

The registration can arrive after retirement: cancellation registration itself can race completion while request setup is still in progress. CancellationRegistrationSlot has an explicit closed state; a late installation closes itself immediately, and repeated retirement does not close an installed registration twice. Actual registration disposal neither invokes nor waits for host callbacks. Failure during disposal still detaches the waiter in finally; an internal retirement failure is exposed as an exceptional result rather than a success, with an existing requested failure retained as the cause without mutating that host-owned throwable.

## CompletableFuture behavior

The admitted future remains exposed as CompletableFuture<VoxelChunk>. Normal completion, exceptional completion, cancel, obtrudeValue and obtrudeException retire ownership first. orTimeout and completeOnTimeout invoke the same completion paths. A timed get is only a read; it does not cancel or retire anything. Mutation of copy(), dependent futures or a minimal-stage observer changes only that observer.

The JDK's completeAsync machinery publishes internally rather than invoking the public complete override. Its supplier is wrapped so retirement occurs before the supplier returns or throws. The JDK still controls executor selection, rejection, skipping suppliers for already completed futures and asynchronous exception wrapping. Null arguments and rejected executor submissions do not retire a pending request. No completion executor or extra normal-publication thread is introduced. Ordinary competing-completion and later obtrusion behavior remains; cancellation does not interrupt an already running supplier.

A caller-supplied chunk completes only that caller's future. It does not become an engine generation result, cache entry, or a healthy peer's result. Generation/cache publication remains in the existing engine path. Completed cache hits and failures rejected before admission have no admitted ownership.

This boundary releases ownership before **that future's** callbacks. It does not isolate arbitrary host continuations, promise nonblocking close, or publish every shared peer before an earlier peer's callback runs. A blocking host callback can still block its completing thread; use asynchronous continuations when that is unsuitable. Started generation remains cooperatively cancelled, and a healthy shared subscriber can keep it alive. Parent-supplier behavior and callback claim semantics from [cancellation ownership](CANCELLATION_CALLBACK_OWNERSHIP.md) remain in force.

## Deferred definitions

Ten RequestFutureTest contracts define terminal callback ordering, timeout versus timed-read behavior, competing publishers and cross-thread callback reentry, cleanup faults, invalid arguments/rejected executors, queued/default asynchronous completion, skipped suppliers, asynchronous CancellationException wrapping, and independent copied/dependent observers.

Four CancellationRegistrationSlotTest contracts define installation before/after retirement, duplicate ownership, 100 concurrent installation/close attempts, and real token-listener suppression when a future completes before registration installation.

Four ChunkSubscriberOwnershipTest contracts use actual WorldContext admission. They define all 8,192 pending subscribers with a blocked completion callback admitting a successor; nine terminal mutation routes removing a last queued subscriber before callbacks; a caller-supplied type-42 chunk isolated from an EMPTY shared result and its bounded cache; and timed reads/copied observers retaining pending ownership. Latches hold workers and callbacks at defined phases; bounded waits are failure bounds, not timing or throughput claims.

ChunkSubscriberOwnershipConsumer compiles separately against only the engine JAR. The existing CancellationOwnershipConsumer invokes it under its existing artifact directory, so no workflow change or dispatch is required. Roots 2/4 each replay the full subscriber limit, blocked callback successor/replacement admission and shared peer withdrawal while a public parent supplier holds generation. After release, a new same-position request must produce EMPTY rather than the manually supplied type-42 chunk; four retained snapshots define 144 exact cells after engine closure. Full authoring JSON remains equal. The future report is cancellation-ownership/chunk-subscribers/chunk-subscriber-retirement.json.

All definitions remain unexecuted. Broader concurrency/load, integration, native/platform/Windows 11 hardware and complete acceptance/release gates remain pending. Main remains the only branch; the workflow remains manual-only.
