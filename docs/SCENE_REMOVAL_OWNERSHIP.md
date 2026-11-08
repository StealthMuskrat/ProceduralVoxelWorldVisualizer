# Retained versioned scene removals — reconstructed, unvalidated

W06-C19 addresses render-thread retirement/delivery ownership in 1.4.1/14.1.5 and stale scene publication in 15.5.3/17.1.3. The restored ChunkScene.removed() drained an unbounded position queue before GPU processing. Interruption or a fault lost the unprocessed tail; replaying a position-only removal could also delete a newer resident/pending version. This reconstruction follows [workspace recovery](WORKSPACE_RECOVERY.md), retaining the batch ID without claiming the original missing commit identity.

## Retained CPU receipts

ChunkScene now exposes Removal(epoch, position, version) and RemovalReset(epoch, version). Normal retirement captures a scene-wide sequence watermark strictly after prior mesh versions; future generated versions advance beyond it. Data-only eviction also receives a watermark, so unknown older GPU residency at a reused coordinate is covered. This changes opaque receipt numbering, not canonical fields/voxels, LOD choice or mesh arithmetic.

Private SceneRemovals retains one immutable Removal per coordinate, coalescing to the highest watermark while preserving insertion order. pendingRemovals() returns an immutable nondestructive snapshot; pendingRemovalReset()/hasPendingRemovals() expose outstanding reset/work. acknowledgeRemoval accepts only the exact retained object. Copied/equal, stale, foreign or already-consumed receipts cannot clear a newer record. Closing the scene detaches all removal state before Future/source cleanup callbacks; late offers cannot reopen it.

The per-coordinate map is bounded by configured dataChunks (27..2048 in production). A new coordinate beyond that bound, or a limit shrink below the retained count, collapses it to one RemovalReset. Later retirement while reset is pending advances that reset's identity/watermark rather than growing another queue. No individual removal list is returned during reset. The legacy removed() API destructively drains deduplicated positions only when no reset is pending; otherwise it throws IllegalStateException rather than silently lose the reset requirement.

Reset acknowledgement additionally requires invalidateUploads() after that exact reset became current. Invalidation retains immutable CPU payloads but advances entry/minimum-upload versions and clears uploaded/displayed ownership. Replacing the reset retires the invalidation acknowledgement too. This prevents a reset from being acknowledged while CPU entries still believe old GPU geometry is resident. Manual callers must still perform a successful GPU reset before invalidation/acknowledgement; the CPU API cannot certify foreign physical effects.

## GPU retirement and retry

SceneGpuRenderer.remove(Removal) returns false for closed/foreign-epoch owners and otherwise accepts absent/already-retired work. Pending, resident and drawn versions are independently gated at version <= watermark by the shared comparison policy. Newer pending/resident geometry and newer picking/LOD receipts survive an old replay. Eligible pending ownership detaches before arena release. During compaction, eligible resident retirement joins the existing deferred retiring set; newer versions are not added to that set. The original remove(ChunkPos) remains explicitly unconditional for legacy callers.

resetScene(RemovalReset) returns false for closed/foreign epochs. Accepted reset clears pending/progress, resident/retiring/compaction state, drawn versions/levels and current frame counters/mode while resetting the CPU arena. It preserves the same GPU buffers, renderer activation and epoch; existing lifetime completed-compaction accounting remains. Future uploads can reuse that allocation. It does not allocate a second arena or alter shader/vertex data layout.

Private per-view SceneRemovalPump processes retained snapshots before uploads. Only a normally returned accepted backend result and the captured current frame authority permit acknowledgement. A refusal, failure, owner replacement, closure or newer coalesced/reset identity retains applicable unacknowledged work. Previously acknowledged prefixes remain acknowledged; failed/tail work stays available. Reset processing is GPU reset, exact current reset check, CPU upload invalidation, then exact reset acknowledgement. New reset/removal state prevents continuing into uploads. Recursive removal pumping rejects before callbacks; Viewports' existing frame guard now includes removal pumping.

The pump propagates the original RuntimeException/Error without text reads, added suppression or receipt consumption. Retry is logically idempotent because typed GPU retirement cannot remove later versions. Existing UI real-work failure/fallback policy remains; retained records do not force a caller to retry a backend whose owner was retired. Arbitrary backend side effects, invalid context and ambiguous physical release remain outside this logical receipt guarantee.

## Deferred definitions

Six SceneRemovalsTest and seven SceneRemovalPumpTest definitions cover production queue/CPU scene/pump/policy with controlled backend effects:

- Exact-object acknowledgement, immutable snapshots, per-coordinate highest-watermark coalescing and foreign/equal/stale/replayed receipt rejection.
- Overflow and limit shrink collapsing to a single reset, replaced reset invalidation barriers, closed terminal ownership and explicit legacy drain behavior.
- Actual 80-position CPU travel with unexecuted workers, metadata bounded by 27, and empty retirement state before source-close callbacks.
- Shared version policy expectations plus a real 64-byte arena: old/equal slices retire while the newer offset-32 slice remains valid with independent 16/48-byte used/free accounting.
- Real type-23 retirement/reload retaining 64 exact cells and 1,024 packed bytes, callback failures before/after RuntimeException/Error effects, retained failed/tail work and idempotent retry.
- Refusal/obsolete/reentry/closure/replacement controls, same-owner coalesced/reset replacement during backend return, and successful GPU reset requiring CPU invalidation before acknowledgement without changing retained mesh/cell objects.

StreamingRenderProbe gains typed-removal cases while keeping its existing output/report contracts. Definitions check foreign/older/current versions, newer partial/resident preservation, picking versions, replay, compaction-time retirement, foreign reset refusal, same-allocation/epoch reset with pending maintenance, and exact framebuffer/version recovery. Native GPU/root mapping remains source-traced; none of these definitions was executed.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's pause. No compiler, Gradle, JUnit, native probe, consumer, checker, package, update request or workflow ran. Only source/Git whitespace/content/metadata review was performed. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Local-main publication remains pending.

Public renderer APIs gain the receipt/pending/acknowledgement and typed remove/reset methods above. Headless APIs/dependencies, mesh/voxel/shader math, UUID mappings, budgets, configured bounds and authored formats remain unchanged. Retained metadata/snapshots are bounded by dataChunks or one reset. No executor, staging buffer or extra GPU resource is added; exact heap/call/latency cost remains unmeasured.

Subsequent [reconstructed C20](NATIVE_RENDERER_SHUTDOWN.md) adds native renderer shutdown/typed retirement, also awaiting execution. Subsequent C21 reconstructs two-phase UI peer shutdown, also awaiting execution. Next recover C22–C27 timer/shadow/frame/host/copy/candidate work. Physical streaming caller-state restoration, complete parent preview/palette/profiler/display acceptance and retained physical display, other owners/callers/document/controls and W05/W06/W07/native/resource/performance/platform/release audits remain open.
