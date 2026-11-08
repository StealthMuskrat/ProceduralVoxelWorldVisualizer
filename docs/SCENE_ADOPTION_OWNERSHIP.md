# CPU scene adoption ownership — unvalidated

W06-C14 addresses CPU source-to-scene transfer in 1.4.1, 14.1.5 and 15.5.2/15.5.3. Viewports previously closed its old scene before constructing the replacement. A constructor/configuration failure could strand the incoming source, and a retirement callback could admit a replacement that subsequent old assignments overwrote. A throwing old-source close could also prevent renderer initialization for an otherwise valid candidate.

Private SceneAdoption now keeps the previous scene while constructing a candidate. Its factory includes project/configuration preparation and ChunkScene construction; a normal return transfers the raw source to that candidate. Construction failure settles current explicit-retry state before attempting raw-source cleanup. If construction reenters replacement/close, the helper rechecks eligibility and closes only the unpublished candidate, without also closing its already-transferred raw source. An initially obsolete opening closes the raw source without invoking the factory. Successful publication transfers ownership to the viewport before captured previous-scene retirement; the helper performs no later live-scene lookup or field clear.

Viewports guards adoption with an exact ticket installed before construction, plus captured previous scene, preview epoch, renderer, source key, compiler generation and streaming intent. Ticket identity also rejects a nested same-epoch adoption that fails while retaining the same previous scene. Mark, stream stop and renderer fallback revoke the old adoption ticket. Current construction failure leaves the previous CPU scene and renderer state in place, consumes force intent and uses STREAM_SCENE_FAILED with an explicit Regenerate message; separate STREAM_SCENE_CLEANUP_FAILED feedback cannot replace that primary outcome. Constructor Errors are rethrown unchanged after settlement/cleanup/guarded reporting; recoverable RuntimeExceptions use the new retry state.

After publication, the existing renderer startup runs only while the exact adopted scene/ticket/request remains current. An escaping previous-scene retirement fault is contained and may receive STREAM_SCENE_RETIRE_FAILED after successful renderer startup; it does not undo CPU publication or prevent startup. Existing ChunkScene.close exception handling remains unchanged, so only faults escaping that method reach this warning. Renderer startup failure instead follows the existing guarded fallback policy; fatal renderer Errors retain propagation. A warning description/delivery callback cannot cause old tick/setup work after replacement. Original Throwables are not mutated or linked by suppression. Generic checked cleanup failure is returned separately, with InterruptedException preserving the interrupt flag.

The owner settlement/publication callbacks contain only internal field assignments. Factory success means the returned candidate owns the source; factories must not transfer that source and then throw. Current ChunkScene construction allocates no jobs/native resources before returning, so raw-source disposal covers its failed constructor path. A throwing cleanup call is an attempted retirement, not proof of physical release, and is not replayed. Callbacks remain cooperative and may block.

## Deferred definitions

Eleven SceneAdoptionTest definitions exercise the production helper with real ChunkScene constructors/sources, a controlled CPU executor and a modeled UI/renderer-start owner:

- RuntimeException/Error construction faults with opposite cleanup faults, requiring retry settlement before raw cleanup, retained old-scene ownership and primary/cleanup identity without mutation.
- Successful publication before an old-source close Error, with current renderer-start intent and a healthy adopted scene despite retirement feedback.
- Constructor replacement/close callbacks returning normally, with exactly-once disposal of only the unpublished candidate/source.
- Constructor replacement/close callbacks throwing, without marking the successor failed or reading stale fault text.
- Nested same-epoch adoption success/failure, retiring the outer capability; failed inner work then retries with a fresh source and request while stale failure feedback stays suppressed.
- Old-source retirement callbacks replacing/closing the already-published scene, transferring responsibility without duplicate closure or late modeled renderer setup.
- Initially obsolete ownership, disposing raw source without construction/failure settlement and preserving a cleanup-admitted successor.
- Primary-description replacement/close after raw disposal, suppressing old primary/cleanup feedback.
- Retirement diagnostic failure before/after recording, preserving publication or its report-admitted successor.
- First-scene transfer without a previous owner and null-factory rejection, retaining distinct source ownership.
- Interrupted raw cleanup, preserving the interrupt and primary fault without closing the previous owner.

Healthy current/retry scenes must prepare and acknowledge an actual CPU upload; independent retained 4-cube snapshots require uniform types 23, 41 or 59 in all 64 cells. Old retained cells remain type 7. The renderer-start counter is a CPU model, not GPU/framebuffer evidence. Actual Viewports ticket/key/epoch/compiler/intent wiring, constructor-Error propagation, native setup and warning timing remain source-traced; existing scene/compiler/fallback/volume/Inspector/native/mathematical definitions retain scope.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git whitespace/content review adds no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, math, configured budgets, UUID/source mappings, schemas, registries and formats remain unchanged. Local-main publication remains pending.

This addresses the recorded CPU scene-adoption ownership gap in source. Subsequent [W06-C15 startup ownership](SCENE_STARTUP_OWNERSHIP.md) addresses preparation order and guarded startup metadata publication in source. Full transactional renderer/palette/display acceptance, stale/mismatched receipt disposal callers, full frame/profiling reentry, surrounding document/control/shadow callers, general Viewports.close/GL teardown, other representations and full W05/W06/W07 inventories remain open. Native/physical-input/DPI, resource/performance, Windows 11 and release validation remain deferred. No complete atomic-display transaction, physical-release, measured overhead or source-audit completion is claimed.
