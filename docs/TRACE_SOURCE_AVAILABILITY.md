# Trace source availability

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [trace lifetime](TRACE_DOCUMENT_LIFETIME.md) and [shared source navigation](TRACE_EXPLANATIONS_AND_NAVIGATION.md). No A01–A36 acceptance gate advances.

Inspector and Logs previously checked resource/control presence to enable their source buttons, then rejected changed committed definitions only when navigation was dispatched. The shared TracePanel now uses the same resource fingerprints to enable those buttons. Changed and removed definitions remain historical values; their disabled links explain how to restore the source or request a new trace. Earlier/different documents, absent source targets and unavailable private-draft controls have separate explanations in the scrolling body. Pending source decisions and blocked Properties editors also have explicit guidance. Footer sizing, copying, value paging and new-coordinate actions retain their existing owners.

TraceSnapshot.definition factors its existing committed-resource comparison into MATCH/MISSING/CHANGED outcomes. TraceSnapshot.available still checks exact document ownership, live navigation availability and an uncached committed fingerprint at dispatch. Existing pending source guards continue to invoke that independent check before applying/discarding a draft. Layout never grants cached authority to a later navigation callback.

TraceSourceCache reuses only committed-definition outcomes, keyed by resource UUID within one exact snapshot/document/canonical project/revision. The LRU holds at most 32 entries, including missing definitions. Its project observation is weak; no full old project tree is retained by the cache. Replacement or context changes clear entries, and another snapshot cannot borrow an earlier definition match. Save preserves the context. Direct DocumentSession edits that replace the canonical object are detected even without an EditorState revision increment.

Private-draft control availability is evaluated on every query through the existing DiagnosticNavigation authority. Removing/restoring an item, shared parameter or curve point in the currently selected private Noise draft therefore disables/re-enables its control link without rehashing or adopting that private draft as committed data. A committed source definition can remain MATCH while its private target is unavailable. Resource links unaffected by an authored edit remain independently available within the same document. Existing fingerprint granularity and legacy standalone TraceSnapshot compatibility are unchanged.

## Deferred definitions

Six TraceSourceCacheTest definitions cover stable repeated resource reuse, bounded positive/negative entries, changed-type isolation and Undo, live private processing removal/restoration, Save and same-UUID reopening, direct canonical replacement at equal revision, snapshot replacement and separate missing/targetless outcomes. They also define project/history/revision and historical-text invariance. None ran.

TraceSourceProbe defines twelve native production TracePanel footer receipts: available and changed-type states across six wide/narrow/short/global/window font combinations, including a synthetic 6.25 font product. Eight source phases cover current, private-control removal/restoration, committed type rename/Undo, deletion/restoration and same-UUID reopening. Held old control/type gestures must expire; disabled type links must open no draft decision. Repeated frames/font changes must reuse committed checks, while private availability changes without another committed check. Restored same-resource navigation preserves the private draft; old trace details stay exactly copyable after reopening. Fixture authoring changes intentionally exercise history and are restored before complete persisted-value comparison.

scripts/verify_trace_source.py independently checks state routes, definition-count relationships, bounded entry observations, native footer geometry/IDs and complete project/preferences equality. TraceSourceConsumer uses only the engine JAR, invokes the established analytical field-22/provenance oracle and compares full canonical trace JSON. The new native probe has ImGui without GLFW, OpenGL or worker dispatch. These are future definitions rather than observed evidence.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.TraceSourceProbe build/trace-source
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.TraceSourceConsumer build/trace-source
python scripts/verify_trace_source.py build/trace-source
```

Future Windows wiring retains bundled Java, semicolon classpaths and exit-code guards; artifact lists include the new receipts. Development-branch push triggers remain disabled. No compiler, build, test, native probe, Python checker, schema/generator or GitHub workflow ran for this batch. Physical input/DPI, all source navigation paths, full GUI/Windows 11 acceptance and production sign-off remain pending.
