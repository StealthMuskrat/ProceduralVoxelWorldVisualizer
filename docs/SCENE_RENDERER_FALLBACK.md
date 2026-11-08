# Scene renderer fallback retirement — unvalidated

W06-C13 addresses renderer-fallback retirement in 1.4.1, 14.1.5 and 15.5.2/15.5.3. rendererFallback previously closed the CPU scene and GPU resources before clearing live scene/budget/key state and selecting fixed preview. A cleanup fault could skip fallback settlement; a cleanup callback could admit a replacement that later old clears or GPU release retired. Startup/frame diagnostics also read foreign exception text without current-owner guards.

Private ScenePreviewFallback now checks eligibility, settles the fallback owner and generic feedback, and independently attempts captured GPU release followed by captured CPU-scene closure. GPU release precedes CPU/source callbacks that may admit a successor. The helper never looks up a live replacement scene to close. Primary diagnostics precede separate STREAM_RENDER_CLEANUP_FAILED diagnostics; both use bounded descriptions with current checks before/after text reads and contained delivery faults. Original Throwables are not suppressed, described during retirement or otherwise mutated. The first cleanup fault has priority over later cleanup faults without automatic replay.

Viewports captures the scene, GlViewport, preview epoch, source key and compiler generation at renderer startup/frame entry. Eligibility requires the same owners and streaming intent before changing state. A private exact fallback ticket plus settled fields/epoch/key/compiler checks guard feedback afterward, including same-epoch nested fallback. Stop, mark and scene replacement clear the old ticket. A current fallback returns fixed-preview intent; an obsolete failure or callback-admitted successor returns streaming intent to prevent the old call from starting fixed work. Startup retains its existing notification; frame failure retains STREAM_RENDER_FAILED. The existing renderer RuntimeException catch boundaries remain; original fatal renderer Errors do not newly opt into fallback. A post-tick owner check avoids proceeding with a scene replaced or closed during CPU callbacks.

GlViewport.releaseScene now captures its scene renderer and shadow map, detaches all corresponding fields/budget/wanted/shadow feedback state, then attempts both native closures independently through private RenderCleanup. A shadow close fault cannot skip the captured scene close, and a reentrant replacement is not cleared afterward. The first RuntimeException/Error is rethrown unchanged, with no suppression or text read. Standalone releaseShadows also detaches before notifying its captured map. These changes do not certify physical GPU deletion after a throwing native call or make surrounding shadow/render transactions atomic.

## Deferred definitions

Nine ScenePreviewFallbackTest definitions use the production fallback helper, real ChunkScene/Source closure, a controlled CPU executor, the real source-compiler generation gate and independent GPU/state callbacks:

- Before/after GPU release RuntimeException/Error faults with a source-close Error, requiring settled ownership before both notifications, primary-first diagnostics and separate first-cleanup feedback.
- Source-only cleanup and shared primary/cleanup Throwables, without mutation, replay or duplicate scene closure.
- Obsolete scene/epoch/renderer/key/compiler/closed owners, without cleanup or fault text reads.
- GPU cleanup reentry into successor/close, closing only the old captured CPU scene and suppressing old fixed work/diagnostics.
- Source-close reentry into successor/close after GPU release, preserving the replacement scene.
- Primary text reentry into close/same-epoch successor, without stale primary/cleanup delivery.
- Diagnostic failure before/after recording, with and without a report-admitted successor; settled state and current ownership survive.
- Cleanup text admitting a nested same-epoch fallback, whose new ticket suppresses old cleanup delivery.
- A new compiler opening during source cleanup, suppressing obsolete fallback reporting and old fixed admission.

Replacement cases drive the actual successor ChunkScene to a prepared/acknowledged upload under a bounded controlled executor. Retained uniform voxel snapshots independently require type 23 in all 64 cells; literal fixed payloads remain unchanged. These are CPU lifetime/identity definitions, not framebuffer/pixel or native allocation evidence.

Four RenderCleanupTest definitions require independent ordered attempts across before/after faults, unchanged first/shared unreadable Throwable identity with no text/suppression, nested retirement order and empty/healthy completion. Actual GlViewport native field detachment, both UI adapters and catch boundaries remain source-traced. Existing scene/mailbox/compiler/volume/Inspector/native mathematical and acceptance definitions retain scope; no new native probe or generated report is added.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git whitespace/content review adds no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, math, budgets, source UUID mappings, schemas, registries and formats remain unchanged. Local-main publication remains pending.

The recorded fallback/resource-retirement ordering gap is addressed in source. Transactional source/scene adoption and renderer/palette/display acceptance, full frame/profiling reentry, surrounding document/control/shadow callers, general Viewports.close/GL teardown, other representations and complete W05/W06/W07 inventories remain open. Native/physical-input/DPI, resource/performance, Windows 11 and release verification remain deferred; no physical-release, measured overhead or full source-audit completion is claimed.
