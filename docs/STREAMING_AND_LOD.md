# Streaming and display LOD

The normal World, Threshold Voxels, Point Cloud and Isosurface paths use an incremental scene of fixed 32-cell chunks. Orbit navigation follows the focus; free-fly follows the camera. Compilation and generation run off the render thread. Nearby work is admitted first; obsolete distant work is cancelled. Source snapshots, field thresholds and selected stages define an epoch; camera movement, palette edits, bookmarks and folder changes do not rebuild that epoch.

Each viewport has explicit CPU, GPU, resident-chunk, upload-per-frame and worker budgets. Exact source chunks, display meshes, in-flight reservations and GPU occupancy are visible in the profiler. A bounded halo supplies neighboring voxel cells; surfaces own complete corner grids without a mandatory halo. Speculative preload can be reduced independently of the mandatory halo. Fixed analysis remains available, with conservative working-memory admission; volume and slices use that bounded analysis path; Isosurface supports both streaming and explicit fixed analysis.

## Meshes and seams

Voxel vertices occupy 16 bytes: unsigned 16-bit half-cell coordinates, signed normalized byte normals, normalized byte ambient occlusion, padding and an unsigned 32-bit material ID. The coordinate 256 encodes as 512 without wrapping. Opaque internal faces are culled across different types and across displayed neighboring chunks. Corner AO reads side/diagonal cells; greedy merging is restricted to uniform matching AO so interpolation cannot invent gradients. A changed neighbor-display mask invalidates the corresponding boundary mesh.

LOD0 uses exact cells. Coarser levels aggregate power-of-two groups up to the root size. A group is occupied at 50% or greater; its dominant occupied runtime type wins, with lower runtime ID breaking ties. Empty cells never win an occupied group. Aggregates are renderer-only derived data. A one-cell exact boundary shell is meshed on the common unit grid; its interface with coarse interiors is meshed on that same grid. Different neighboring LODs therefore do not introduce geometric cracks. This deliberate boundary-detail cost trades some distant triangle reduction for simple exact seam handling.

LOD uses nearest AABB distance, configured chunk-distance rings, quality and 15% hysteresis. Neighbor visibility follows completed GPU residents; a face is not hidden against an unuploaded neighbor. Old displayed chunks within the new load window are retained while that window settles, then their GPU ranges are retired; distant chunks are evicted immediately. Exact data can remain cached for revisits.

## GPU path

Each viewport uses one shared packed vertex arena with aligned, coalescing free ranges and bounded in-place compaction. Uploads are sliced across frames and a mesh becomes drawable only after its complete upload. The last valid framebuffer is retained while a new source compiles or its first mesh uploads. Camera-relative offsets prevent large world coordinates from becoming vertex floats. Frustum culling precedes submission. OpenGL 4.3 uses indirect multi-draw with instanced chunk offsets; OpenGL 3.0 uses per-draw constant offsets. Palette textures can span multiple rows and update without rebuilding geometry. AO, fog and unlit settings are shader controls.

Pressure changes effective settings in order: more distant LOD, shorter render distance, less preload/background work, rejection of new expensive previews, then visible pause requiring Resume. Stable low-pressure intervals gradually restore effective quality. Configured preferences and exact generation data are never rewritten by this controller.

## Runtime request ownership

A WorldContext shares concurrent sync/async generation for the same chunk. Each subscriber has its own cancellation/future; one cancellation does not cancel other subscribers. If every subscriber abandons a request, work stops at generation cancellation boundaries. Synchronous calls can execute queued shared work inline, avoiding worker starvation. Sharing never crosses context/seed boundaries.

Collected regions reserve a shared engine budget, including simultaneous collections and generation/cache staging. RegionResult is AutoCloseable: close releases its admission reservation and does not invalidate immutable chunk snapshots. A Cleaner is a fallback for abandoned results. The existing constructor, chunks()/summary() accessors and value equality remain; clients using record reflection/patterns must update to the owned result class. Prefer try-with-resources. Oversized region counts/collection estimates fail explicitly; world chunks extending beyond signed 32-bit block coordinates are rejected instead of wrapping.

This revision does not add streaming ray-marched volumes, coarser scalar-surface LOD, shadows or Windows hardware certification. Cross-context queued priority fairness and GPU arena compaction have separate implemented contracts. The benchmark and native/contract evidence describe observed scope rather than certifying all A01–A36 requirements.

See [GPU_ARENA_COMPACTION.md](GPU_ARENA_COMPACTION.md) for relocation bounds, draw continuity, retirement, allocator ownership and native pixel checks.

## Picking drawn World data

World picking traverses immutable stage/LOD cell inputs for the chunk versions submitted by the latest GPU render. Unshown, culled, empty and retiring allocations do not extend picking coverage, even when their procedural data is loaded as a halo. Pending mesh/LOD replacements keep their previously committed picking cells until GPU publication; retained coarse-cell bytes are included in CPU accounting. Scene upload acknowledgements reject foreign epochs and older displayed versions.

A coarse occupied proxy can cover an exact EMPTY preview cell. That coordinate opens the exact full trace with an explicit display-LOD proxy hint; the renderer does not invent an exact occupied voxel or search through the proxy into unrelated geometry. Traversal stays on the UI thread over resident immutable cells; only the selected coordinate and small drawn-version map cross to background tracing. Streaming hit/error publication requires the same CPU scene, GPU renderer and drawn versions as the click, as well as the existing request/revision/camera guards. A streamed miss reports no occupied voxel in displayed chunks without a procedural ray query.

The renderer Upload record now carries its immutable DisplayLod input; its four-argument constructor remains available for existing callers. Record-pattern/reflection clients must account for the additional component. This renderer-only change does not change exact chunk, archive, project or headless-engine formats.

Required visible chunks and their halo now take priority over optional preload by distance. When the chunk-entry limit is full, optional entries are reclaimed to make room for missing required data, keeping previously displayed entries until other optional entries have been considered. This prevents preload/retained halo data from starving meshing at the minimum 27-chunk limit. Hard admission still applies when the entire configured required window exceeds that limit.

## Inspector feedback after a pick

Coarse proxy feedback includes the picked integer coordinate and explains that the exact preview is empty while the trace describes the full world. In Only Changes, an empty preview cell can be an unchanged occupied full-world cell. A successful exact pick or explicit Trace Coordinate replaces that feedback. Fixed and streamed misses clear the Inspector's current trace and show coordinate-recovery instructions; previously published traces remain in the bounded Logs history. Explicit coordinate completion updates the status to its accepted coordinate.

Native coverage uses an isolated three-stage world (SET a plane at x=28, SET everywhere, CLEAR the plane), actual coarse World image clicks, the production TracePanel and Trace Coordinate button, and wheel refinement. At LOD1 the proxy coordinate x=29 is empty in the selected preview but occupied in the full world; after refinement x=28 is a genuine removal ghost with final EMPTY. The fixture also checks fixed/streamed misses and retained Logs without authored changes.

## Coordinate trace jobs

Explicit Trace Coordinate owns at most one pending editor job. Starting another coordinate request cancels/reclaims its queued predecessor; XYZ edits, Cancel Trace, Inspector closure, a new World pick and viewport closure invalidate obsolete work. Pending feedback identifies the requested coordinate and labels any retained payload as the previous result. Explicit coordinate work stays independent of camera/tab navigation; project revision changes cancel it. A full queue or evaluation failure leaves the prior trace/Logs intact and gives an explicit Trace Coordinate retry action. Successful completion clears progress/failure feedback. Cancellation requests interruption for running work; it is not hard preemption of engine evaluation.

## Entered and traced coordinates

Inspector XYZ is an editable coordinate, separate from the current immutable trace. A visible notice names the displayed trace coordinate when XYZ differs. Use Traced Coordinate restores those integers without moving the camera, regenerating the preview or publishing another trace. Restoration cancels pending coordinate work. Every XYZ edit invalidates either kind of pending Inspector request, including a World pick, so a late hit cannot overwrite typed input. Closing the Inspector likewise invalidates pending World picks as well as coordinate jobs. Previously published trace history is retained.

## One owned Inspector request

World picks now share the replaceable InspectorTask Future slot with explicit coordinate traces. Repeated picks, switching request kinds, XYZ edits, restore, Cancel Pick/Cancel Trace and Inspector closure reclaim canceled queued work rather than leaving obsolete ray evaluations in the editor queue. Camera/tab/preview and drawn-GPU-version changes cancel view-bound pick work; explicit coordinate requests remain independent of navigation. The Inspector can show pending pick feedback and Cancel Pick while retaining a clearly labeled previous trace. Full-queue and evaluation failures keep the prior result and offer an explicit retry.

Every result/miss/error first finishes only its owning slot and checks the shared revision/request/view/coverage guards; a late predecessor cannot clear a newer request. The validity closure is released on completion/cancellation/closure. Running cancellation is best-effort interruption rather than hard engine preemption. Native coverage checks five repeated picks with one queued successor, Cancel Pick cleanup, real queue-full rejection/retry, and immediate camera/tab reclamation alongside the coordinate workflow.

## Historical trace refresh

TraceSnapshot classifies an accepted result as current, an earlier revision of the current project, or another project. Inspector and shared TracePanel use the same classification. Refresh Displayed Trace re-evaluates an older same-project trace at its displayed coordinate using committed current data, leaving entered XYZ unchanged. It uses the existing owned coordinate job/retry/cancel path and does not request preview generation. Historical values remain immutable in Logs; refreshed source links use the new snapshot. A foreign-project result remains read-only and does not offer this refresh action; explicit Trace Coordinate inspects the current project. Empty/null trace presentation resets submitted row/step and control telemetry.


The fixed marching-tetrahedra analysis path now has exact shared-edge interpolation order, outward winding/normals, overflow-safe finite interpolation, row cancellation and an explicit output cap. Its working-memory admission includes the corner grid and peak mesh growth. See [bounded isosurface extraction](ISOSURFACE_EXTRACTION.md). Streaming surfaces reuse that extractor on cached canonical chunk corners. Their 1/128-block positions share the packed arena with half-block voxel/point positions; draw groups and compaction retain each scale. Surface detail remains on the full sampled grid rather than voxel majority LOD. See [streamed isosurfaces](STREAMED_ISOSURFACES.md).


The bounded volume path now uses immutable finite affine texture encoding, canonical-to-GPU transfer coefficients and owned sampling/encoding cancellation. Controls operate on accepted texture metadata without resampling, including extreme/tiny/neighboring ranges. Parallel ray slabs have explicit float-boundary handling. See [volume encoding and transfer](VOLUME_ENCODING_AND_TRANSFER.md). These are fixed-analysis contracts; they do not add a streaming volume atlas.
