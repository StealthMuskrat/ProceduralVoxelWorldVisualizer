# Complete-worker gates for viewport payloads and streaming sources

Development implementation on main; **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, JUnit, native probe, schema/Python checker, independent consumer, packaging, real update request or GitHub workflow ran. The observed baseline remains **922 tests / 120 suites**. Native/Windows 11 and final release acceptance remain pending; no A01–A36 gate advances.

## Prepared CPU data stays private until normal worker return

Viewport failure recovery already observed terminal fixed/volume and streaming-source failures before consuming their mailboxes. When the worker was still running, that observation returned no completion but the frame continued to take a prepared payload anyway. A display, volume upload or streaming scene could therefore adopt the payload before profiling finalization or another late worker failure had finished.

Frames now leave the fixed/volume mailbox untouched while its job handle remains pending. Streaming-source adoption likewise leaves its owned mailbox untouched while its compiler handle remains pending. Terminal observation clears a normally completed handle and permits the existing mailbox/epoch/source/budget/upload checks to continue. Exceptional completion retains the existing failure path: drop the unaccepted preview or replace/dispose the unaccepted streaming source with failure feedback while keeping the accepted display and explicit Regenerate recovery.

The gate covers fixed noise images, slices, meshes and worlds through their shared adoption point, camera-relative volume windows, and noise/world streaming-source compilation. A prepared Volume cannot enter incremental GPU staging until its CPU worker has completed normally. Incremental upload and final resident publication retain their separate existing budget/epoch checks. The accepted old scene may continue ordinary drawing/chunk work while its replacement source is private.

These production paths submit through EditorState's managed executor and install the returned Future after submit returns successfully; owner drawing does not run inside that submission. No second confirmation or automatic retry is introduced. Queue rejection and synchronous submission/snapshot failure keep their existing recovery policy. This scoped change does not gate every renderer/upload operation or chunk-worker path.

## Terminal observation keeps its original owner

Fixed/volume job observation captures the exact Future and preview epoch before inspecting terminal state. It checks that same pair and viewport closure before clearing the handle or processing a fault. Streaming-source observation captures the Future and owned-mailbox version, retaining the requested source key and current mailbox checks. It checks those owners after terminal observation and again after describing a failure before replacing the private source. An obsolete observation cannot clear or publish failure against a newer handle/version/epoch.

Immutable payloads, request cancellation, source cleanup, renderer fallback, Regenerate, accepted image/mesh/scene ownership and native-close cleanup retain their existing policies. Project/history, private Properties/raw seed input, preferences, camera controls, registry/model/API/schema/project/package formats and deterministic field/voxel rules are unchanged. Engine/model/storage remain independent of editor/native dependencies.

## Stronger deferred native definitions

PreviewWorkerRecoveryProbe previously stopped drawing while waiting for the executor to capture each FINISH fault. Its six existing FINISH scenarios now hold the worker after payload preparation and before terminal return, while drawing twelve production frames. IMAGE, MESH, WORLD, VOLUME_WINDOW, NOISE_SOURCE and WORLD_SOURCE must retain both the private CPU payload and its worker handle during those frames. Accepted preview epoch and source-scene identity must remain unchanged; the volume case must retain its resident texture without beginning replacement upload. Releasing the worker then exposes the late FINISH failure to the existing retained-display/no-automatic-retry/explicit-Regenerate assertions.

Start and finish latches release in finally blocks, including failure before the first worker boundary. The post-preparation gate uses the same bounded wait/interruption behavior as the existing start gate. Native closure clears the unused finish-gate reference alongside existing probe references. This is fixture instrumentation, not a production synchronization barrier or new UI action.

PreviewMailbox adds a synchronized pending query so the fixture proves a complete payload remains private, rather than mistaking an empty mailbox for successful retention. Three existing PreviewMailboxTest definitions now check pending ownership across publication/take, advance and close. No new JUnit definition or native case is added: the existing fourteen native cases, receipt shape, checker expectations, complete saved before/after models and independent constant-field/voxel replay dimensions remain unchanged. No definition or probe ran; no artifact was produced or inspected through execution.

Source review, git whitespace/content checks and repository metadata review are the available checks. Owner snapshot capture/comparison, upload/source-installation/cleanup costs, remaining worker and required-feature audit, measured native/Windows 11 and final release acceptance remain outstanding. This follow-up does not certify repository-wide completion.
