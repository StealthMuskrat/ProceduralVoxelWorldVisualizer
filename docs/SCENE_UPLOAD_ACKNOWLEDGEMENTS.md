# Scene upload acknowledgement versions — unvalidated

W06-C03 covers obsolete display publication and drawn-cell ownership in 14.1.6, 15.5.3 and the 14.1.5/17.1.4 render-thread handoff. ChunkScene previously accepted every acknowledgement at or above its displayed version. Invalidation cleared displayed state without retiring old acknowledgement versions; reloading a coordinate also began with displayed version zero. Old receipts could restore picking metadata, a version beyond the current candidate could block later valid commits, and an equal-version replay could replace already displayed cell inputs/accounting.

Each entry now has a private minimum eligible upload version. Its first mesh establishes that lifetime's lower bound; a fresh mesh after retirement establishes another. Invalidation advances the entry's opaque candidate version and lower bound together, clears display metadata as before and retains reusable CPU geometry/cells. A new Upload can therefore describe that same CPU payload under a fresh version without forcing an immediate resample just to retire earlier acknowledgements. Normal dirty/signature/LOD checks still decide whether a mesh must be rebuilt.

An acknowledgement must match the scene epoch, refer to an entry with a current mesh, fall between its minimum and current candidate versions, and advance the displayed version strictly. Equal/older receipts are no-ops, as are versions beyond the current candidate. Rejected receipts do not alter uploaded/displayed flags, displayed cells or CPU accounting. Existing Upload constructors and public component shapes remain available; equal-version acknowledgements no longer rebind display metadata.

The lower bound is deliberately not the newest CPU candidate on every rebuild. A previously prepared upload may still be finishing on the GPU while a newer LOD mesh is ready on the CPU. Before invalidation/retirement, that older in-flight version remains eligible and may become the displayed version; the newer candidate remains pending. A later successful commit advances display ownership normally. The scene's counter is an opaque ordering identity, not a contiguous generation count.

This is a version/lifetime guard, not validation of arbitrary payload provenance. GPU arena staging/copies, compaction, shaders, upload budgets, mesh/LOD algorithms, picking traversal, mailbox/cancellation ownership and exact generation math remain unchanged. The added private per-entry long has no measured allocation/performance result. No headless dependency, public signature, schema, registry, authored format or archive layout changes.

## Deferred definitions

Five added ChunkScenePickingTest definitions cover:

- A coarse displayed receipt replayed after invalidation, with unchanged CPU bytes and no restored pick, followed by a fresh version reusing identical CPU mesh/cells and a correct proxy pick.
- A Long.MAX_VALUE acknowledgement rejected before and after the real upload, without preventing that real exact-cell pick.
- An equal-version replay carrying different EMPTY coarse cells, immediately checked for unchanged picking/accounting, plus an ordinary idempotent replay.
- An older in-flight fine upload committing while a newer coarse candidate is ready, followed by the coarse commit and rejection of the older version.
- Travel away, retirement/reload at the same coordinate and rejection of the old entry's receipt before the current upload commits.

The independent plane occupies local X=3: exact picking must return (3,2,2), while its half-occupied 2×2×2 coarse group exposes proxy (2,2,2). These CPU scene definitions simulate acknowledgement ownership without a GL context or GPU-readback claim. Existing foreign-epoch, close, signed-edge, retained coarse bytes, actual draw-map and native streaming/compaction definitions retain their scope. The picking class now contains 13 definitions; no new native probe or engine-JAR renderer consumer is added.

## Status

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git review adds no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Execute scene/picking/LOD and real resize/recovery/upload/native scenarios after validation resumes. Wider W05/W06 caller/scheduling/resource/performance and hardware/release work remain open; local-main publication remains pending.
