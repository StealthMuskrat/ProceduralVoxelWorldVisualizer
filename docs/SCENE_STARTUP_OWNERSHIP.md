# Scene startup preparation and publication — unvalidated

W06-C15 addresses the adopted-scene startup caller in 1.4.1, 14.1.5 and 15.5.2/15.5.3. Startup previously activated/reset GPU scene state before preparing the matching World palette, evaluated its budget twice, published the budget before palette upload and did not recheck ownership between these steps. A preparation fault could therefore follow GPU mutation, and callback replacement could receive late old palette/budget writes or fallback.

Private SceneStartup now checks current ownership, prepares one descriptor, checks again, activates, checks again, uploads the palette, checks again, then publishes matching CPU metadata. The descriptor contains one evaluated budget, the immutable captured-order WorldPreviewPalette refreshed against current colors, and its defensive color copy; Noise uses its existing private source palette and null World metadata. Preparation/null-input failure occurs before GPU activation. CPU budget and World palette publish together through callback-free internal assignments only after normal activation/upload. A final guard prevents the caller continuing old startup work after replacement.

Viewports supplies the existing exact adoption ticket plus scene/epoch/renderer/key/compiler/streaming-intent predicate to every checkpoint. The exception path also checks this predicate before entering the existing guarded RuntimeException fallback, covering a callback that admits another same-epoch adoption and then throws. Errors retain their original propagation; SceneStartup neither describes nor mutates faults. Existing fallback/retirement warning and explicit retry policies retain their contracts.

This helper controls sequencing and publication authority. It neither rolls back physical GPU side effects nor restores a previous renderer/palette/framebuffer after a throwing backend operation. Existing GlViewport scene allocation, in-place palette upload, framebuffer presentation and resource budgets are unchanged. The CPU scene was already adopted under C14; C13 still owns current startup failure retirement/fixed-preview fallback. A palette write that succeeds physically and then throws remains ambiguous, and is not automatically replayed by the helper.

## Deferred definitions

Ten SceneStartupTest definitions exercise the production helper, real WorldPreviewPalette capture/refresh and the existing fallback helper with modeled GPU effects:

- Exact prepare/activate/upload/publish order, old metadata through upload, one frozen budget despite later preference change, matching prepared colors and unchanged source palette.
- RuntimeException/Error preparation faults and a malformed live palette list, without GPU mutation or CPU publication.
- Preparation/activation/upload callbacks closing or replacing the owner, stopping every later old step.
- Before/after activation/upload side effects under RuntimeException/Error faults, with no attempted metadata publication or fault text/mutation.
- Callback replacement followed by a throwing step, preserving replacement metadata and preventing stale fallback cleanup/reporting.
- Reordered/inserted World resources and later live color edits, retaining the source UUID/runtime/ghost ordering and owned prepared colors.
- Initially obsolete ownership and null prepared descriptors, without admitting GPU work.
- Ambiguous failed upload followed by a complete healthy new request, with exact final budget and colors.
- Current RuntimeException fallback versus unchanged fatal Error identity/propagation.
- Raw Noise palette startup without World metadata, preserving exact prepared words and budget.

Independent packed words require World normal colors [0xfff0b428, 0xff965a14], removal ghosts [0xff503ceb, 0xff321eeb] and untouched EMPTY/tail rows; Noise requires [0, 0xff010203, 0xffa0b0c0]. GPU effects/counters and accepted metadata are CPU models, not native texture/framebuffer evidence. Actual Viewports guards, descriptor preparation, callback-free publication and fatal catch boundaries remain source-traced. Existing adoption/fallback/compiler/palette/scene/volume/Inspector/native and mathematical definitions retain scope; no native probe or generated report is added.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git whitespace/content review adds no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, canonical math, UUID/source mappings, configured budgets, schemas, registries and formats remain unchanged. Local-main publication remains pending.

This addresses startup preparation order and stale CPU metadata/fallback authority in source. Subsequent [W06-C16 renderer ownership](SCENE_RENDERER_OWNERSHIP.md) addresses the registered GPU scene slot's allocation/reset lifetime in source. Full transactional GPU/palette/framebuffer acceptance, frame-time palette/budget/caller updates, stale/mismatched receipt disposal, full frame/profiling reentry, surrounding document/control/shadow callers, general Viewports.close/GL teardown and complete W05/W06/W07 inventories remain open. Native/physical-input/DPI, resource/performance, Windows 11 and release work remain deferred; no physical rollback/release, measured overhead or full source-audit completion is claimed.
