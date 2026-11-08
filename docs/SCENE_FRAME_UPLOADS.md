# Captured scene-frame upload acknowledgement — reconstructed, unvalidated

W06-C17 addresses render-thread receipt ownership in 1.4.1/14.1.5 and obsolete-scene publication in 15.5.3. The restored frame loop called GPU operations against a local renderer but selected and acknowledged through mutable v.scene fields. Renderer activation, profiling clocks or backend callbacks could replace the CPU scene, compiler/adoption/request or GPU activation between selection and acknowledgement. Same-object/same-epoch renderer reuse did not provide a lifetime receipt. This reconstruction follows the [workspace recovery inventory](WORKSPACE_RECOVERY.md); it does not claim the original missing commit identity.

## Captured frame authority

SceneRendererOwner now exposes an opaque activation version and current(renderer, version). A receipt is accepted only when object/version match, the owner is open, active and valid, and neither allocation nor activation is transitioning. Every activation revokes old receipts, including same-object/same-epoch reuse; disable, detach and close revoke them too. GlViewport.sceneVersion()/sceneCurrent() expose this renderer-only contract. No caller should infer numeric continuity or use an epoch as a substitute for the activation receipt.

Viewports captures the CPU scene, GlViewport, request epoch, compiler generation and adoption identity. Configure/tick use that scene and recheck the captured authority; the camera center is copied. GPU budget is captured once for activation and matching CPU budget metadata. After activation the frame additionally captures the exact GPU renderer/version. Begin-frame, removal calls, profiler admission, upload pumping, display setup and later metadata/fallback paths recheck this authority. A stale RuntimeException cannot enter fallback against a callback-admitted successor. Existing fatal Error propagation remains.

This frame gate is separate from GPU allocation/epoch ownership. It does not change SceneRendererOwner's established factory/reset/cleanup protocol. It adds no headless dependency or shader/geometry behavior.

## Upload pump

Private per-view SceneUploadPump receives one captured CPU scene, one backend adapter and one current predicate. Nested pump calls reject before backend work; the view also declines frame entry while its pump is busy. Backend calls are followed by a current check before their result can authorize the next operation. The pump uses the captured CPU scene exclusively for selection, rejection and acknowledgement.

| Operation | Acceptance requirement |
| --- | --- |
| Maintenance | Normal current return; byte count within the supplied budget |
| Existing resident | Normal current contains result for the selected CPU receipt |
| Stage | Normal current success retaining the exact selected Upload object |
| Partial advance | Current normal return; bounded byte counters; no CPU acknowledgement |
| Complete advance | Exact captured pending object, same CPU epoch, bounded counters and a normal current resident check before CPU acknowledgement |
| Rejection | Current normal stage refusal and noncompacting backend before CPU pressure is marked |

SceneGpuRenderer.pendingUpload() returns its exact pending object across partial advances, or null after completion/removal/reset/close. This permits a later frame to capture an upload that began earlier without reconstructing its provenance. A field-equal but different completion object, foreign-epoch pending/completion or success without a resident cannot acknowledge. Existing ChunkScene.uploaded eligibility/version-range rules remain authoritative, including historical in-flight receipts and rejection of retired/future/replayed versions; this change does not loosen or replace those rules.

Maintenance and vertex transfer share the existing frame budget. The pump retains at most four candidate iterations, handles a single large mesh through partial advances and validates nonnegative monotonic upload-counter deltas within the remaining budget. Healthy resident fast paths add no transfer. CPU acknowledgement occurs only after the relevant backend return/current checks; a throwing operation may already have changed GPU storage, but that physical effect alone cannot publish CPU acknowledgement. No cleanup replays against a newer renderer are introduced.

Original backend RuntimeException/Error objects propagate through the pump without text reads or added suppression. It releases its busy guard even on failure. Existing UI RuntimeException fallback and profiler behavior retain their scopes; a stale frame is ineligible for fallback. The pump owns no GPU allocation and cannot physically undo a native partial transfer.

## Deferred definitions

Twelve SceneUploadPumpTest definitions use production ChunkScene, a controlled executor and immutable exact/continuous sources, with a modeled GPU backend:

- Complete real point uploads retain all 64 type-23 cells, independently expected half-cell XYZ coordinates, packed type IDs and a 1,024-byte/64-point payload.
- Sixteen maintenance bytes plus 84 transfer bytes in a 100-byte frame, followed by an exact 940-byte pending continuation and complete acknowledgement.
- Every backend boundary before/after closure or owner replacement, same-object activation changes during completion verification and healthy resident retry.
- Equivalent/foreign/unpublished completion rejection, foreign pending epochs and stage success without the exact retained object.
- Current refusal versus compaction/obsolete refusal pressure behavior, every backend fault before/after unreadable RuntimeException/Error effects, and successor-scene preservation through a throwing old transfer.
- Recursive pump rejection, malformed counters/budgets/initially obsolete frames and a real multi-chunk surface fixture verifying the four-resident-candidate limit without transfer.

Two additional SceneRendererOwnerTest definitions check receipt revocation through same-object/same-epoch reuse, disable, detach, replacement and close, plus nonacceptance during preparation/reset and acceptance at publication. The existing twelve owner definitions retain their scope. Tests do not execute OpenGL; native getter/adapters and Viewports wiring remain source-traced. No native probe/report schema is added.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's pause. No compiler, Gradle, JUnit, native probe, consumer, checker, package, update request or workflow ran. Source/Git whitespace/content/metadata review is the available verification. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Local publication remains pending.

Public renderer APIs gain only the activation/pending receipt getters above. Headless APIs/dependencies, packed data, mathematical/shader/meshing rules, configured limits, UUID mappings and authored formats remain unchanged. Local captured references, callback adapters and guards add no executor/queue, staging buffer or GPU resource; their measured overhead remains open.

Subsequent [reconstructed C18](SCENE_UPLOAD_OBSERVATION.md) isolates optional streaming profiler admission/finalization and counter observations; [reconstructed C19](SCENE_REMOVAL_OWNERSHIP.md) replaces the UI's destructive removal drain with retained/versioned delivery. Both await execution. C20–C27 teardown/timer/shadow/frame/host/copy/candidate work, physical streaming caller-state restoration, full preview/palette/profiler acceptance and retained display, remaining caller/resource/performance/platform/release audits remain open. This batch does not close W06 or the whole 15.5.3 transaction.
