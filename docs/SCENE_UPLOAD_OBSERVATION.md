# Streaming upload observation isolation — reconstructed, unvalidated

W06-C18 addresses optional upload instrumentation in 19.4.2/19.4.4 and captured renderer/preview ownership in 1.4.1/14.1.5/15.5.3. The restored streaming loop admitted profiler scopes/stamps inside its GPU failure boundary and finalized through sequential finally calls. Observation failure could therefore trigger renderer fallback, skip independent gauges or replace the original upload failure. Counter reads after finalizer callbacks could describe a successor activation. This reconstruction follows [workspace recovery](WORKSPACE_RECOVERY.md), preserving the original missing batch ID without claiming its commit identity.

## Work and observations

Private per-view SceneUploadObservation captures an observer, a normally returned issue stamp and optional before/after counters around the existing captured SceneUploadPump. It returns an immutable Result containing completed/current state, the original work failure and the first observation failure. RuntimeException and Error from scope creation, timing admission, optional snapshots, finalization and gauges are observation failures; they do not become work failures. No helper reads their text or mutates suppression.

The work still runs when observation admission/snapshots fail while the frame remains current. Its original RuntimeException/Error retains identity through later observation faults. A noncurrent frame stops before work or later publication; observer callbacks may change authority, and those changes cannot be undone by instrumentation. Work success/failed/cancelled timing outcomes describe the captured work, while Result.completed additionally requires the frame to remain current after observations.

| Boundary | Policy |
| --- | --- |
| Scope factory failure or null scope | Continue current work without a timing stamp |
| Begin throws or returns zero | Continue current work; invent no finalizer or gauges |
| Before/after counter failure or invalid delta | Continue/retain work; omit unavailable byte gauges |
| Normally returned nonzero stamp | Attempt its captured finalizer once, including obsolete/cancelled work |
| Finalizer/gauge throws | Retain first observation failure; attempt independent current gauge tails |
| Observer invalidates the frame | Skip later counter reads/gauges and target metadata publication |
| Work throws | Retain that primary separately; preserve existing work fallback/fatal policy |

Counters capture upload and relocation values together before any finalizer callback. Gauges use those primitive deltas and the captured observer, never a fresh renderer/capture lookup. Negative/decreasing/null snapshots reject only their optional measurement. No byte count from instrumentation drives upload admission or acknowledgement; the pump retains its own required budget/counter checks.

The observation busy guard spans scope admission, snapshots, work and finalization. Nested observation rejects before callbacks; Viewports declines recursive upload-frame entry while the observer or pump is busy. Diagnostic delivery runs after display/fallback settlement, outside the work failure boundary. It is guarded by the exact captured CPU/GPU frame authority, uses existing bounded notifications and can emit STREAM_PROFILING_FAILED without changing readiness/fallback. Text/report faults are contained. A post-report owner check prevents remaining old-frame UI work after diagnostic reentry.

## Profiler mapping and ownership

The adapter captures one actual Profiler.Scope. Existing names, units and captured outcome remain:

| Measurement | Kind | Unit | Label |
| --- | --- | --- | --- |
| Work duration | UPLOAD_CPU | NANOSECONDS | Streaming upload and arena maintenance |
| Submitted vertex bytes | UPLOAD_BYTES | BYTES | Streaming upload bytes per frame |
| Arena relocation bytes | UPLOAD_BYTES | BYTES | Arena relocation bytes per frame |

Healthy counters retain the existing per-frame values because beginFrame resets them before observation. Replacing or stopping a profiler capture cannot send old samples into a later capture: finish and gauges retain their original scope. No capture means no optional snapshots or finalization. Profiler capture limits and the existing CPU/GPU instrumentation policies outside this streaming-upload boundary are unchanged.

## Deferred definitions

Eleven SceneUploadObservationTest definitions use real CPU ChunkScene inputs, the production pump/observation wrapper, a controlled GPU/observer and the public Profiler API:

- Healthy acknowledgement of 64 exact type-23 cells/half-cell positions and 1,024 packed bytes, plus an independent 16-byte maintenance gauge.
- Every admission/snapshot/finalizer/gauge fault before/after RuntimeException/Error effects, retaining acknowledgement/readiness without fallback and attempting safe independent tails.
- Original transfer failures surviving shared/distinct observation faults; unknown failed admission does not receive an invented finalizer.
- Missing/negative/decreasing snapshot data remaining optional; callback replacement/closure at every observation boundary blocking stale work/publication/foreign reads.
- Counter/observer mutation after snapshots without resampling or redirected gauges; nested observation rejection across admission/work/finalization.
- Diagnostic faults after settlement, including text callbacks that replace ownership, without old delivery or outcome mutation.
- Public profiler label/unit/outcome/context checks with exact 1,024/16-byte series, capture replacement without cross-capture samples, and absent/zero-stamp profiling controls.

These definitions do not execute OpenGL or certify Viewports' native display/fallback wiring. The latter remains source-traced. No native probe or report schema is added. Public profiler fixtures use its normal clock only for nonnegative durations; no timing performance threshold is claimed.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's pause. No compiler, Gradle, JUnit, native probe, consumer, checker, package, update request or workflow ran. Only source/Git whitespace/content/metadata review was performed. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Local-main publication remains pending.

Public signatures, headless APIs/dependencies, packed data, scalar/geometry/shader math, UUID mappings, budgets, capture limits, labels/units, schemas and authored formats are unchanged. The wrapper adds fixed local result/counter/adapter state, with no executor, delivery queue, staging buffer or GPU resource. Its measured overhead remains open.

Subsequent [reconstructed C19](SCENE_REMOVAL_OWNERSHIP.md) adds retained/versioned removal delivery; [reconstructed C20](NATIVE_RENDERER_SHUTDOWN.md) adds native renderer shutdown/typed retirement. Both await execution. C21 also reconstructs two-phase UI peer shutdown. C22 reconstructs timer/shadow retirement. Next recover C23–C27 shadow/frame/host/copy/candidate ownership. Other instrumentation boundaries, physical streaming caller state, full preview/palette/profiler/display acceptance and retained physical display remain open. Wider caller/document/control inventories and W05/W06/W07/native/resource/performance/platform/release audits are incomplete.
