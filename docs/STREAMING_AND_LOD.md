# Streaming and display LOD

The normal World, Threshold Voxels and Point Cloud paths use an incremental scene of fixed 32-cell chunks. Orbit navigation follows the focus; free-fly follows the camera. Compilation and generation run off the render thread. Nearby work is admitted first; obsolete distant work is cancelled. Source snapshots, field thresholds and selected stages define an epoch; camera movement, palette edits, bookmarks and folder changes do not rebuild that epoch.

Each viewport has explicit CPU, GPU, resident-chunk, upload-per-frame and worker budgets. Exact source chunks, display meshes, in-flight reservations and GPU occupancy are visible in the profiler. A bounded halo supplies neighboring cells. Speculative preload can be reduced independently of the mandatory halo. Fixed analysis remains available, with conservative working-memory admission; volume, slices and marching tetrahedra use that bounded analysis path in this revision.

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

This revision does not add streaming ray-marched volumes/isosurfaces, shadows or Windows hardware certification. Cross-context queued priority fairness and GPU arena compaction have separate implemented contracts. The benchmark and native/contract evidence describe observed scope rather than certifying all A01–A36 requirements.

See [GPU_ARENA_COMPACTION.md](GPU_ARENA_COMPACTION.md) for relocation bounds, draw continuity, retirement, allocator ownership and native pixel checks.
