# Retained trace document lifetime

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [preview document retirement](PREVIEW_DOCUMENT_LIFETIME.md), [Inspector decisions](INSPECTOR_DECISIONS.md) and [shared trace navigation](TRACE_EXPLANATIONS_AND_NAVIGATION.md). No A01–A36 acceptance gate advances.

Production Viewports traces now retain the opaque DocumentSession token that owned their successful request. Project UUID and revision alone cannot distinguish a reopened file from its previous document lifetime. A direct Open can preserve both values; retained Logs can also outlive a file replacement. An old trace must keep its historical values without being labeled current or navigating into a successor document through matching resource UUIDs.

TraceSnapshot adds document-aware freshness through freshness(EditorState). Bound snapshots require the exact token, project UUID and revision for CURRENT. Same-project lifetime changes return PROJECT_CHANGED, allowing an explicit same-project Refresh; different project UUIDs remain OTHER_PROJECT. The context-free UUID/revision overload cannot certify a bound snapshot as current. The existing public standalone constructor retains its revision-only compatibility for source presentations without a live owner. The new binding stores one opaque identity, not an EditorState, mutable project tree, native object or worker.

Inspector and the shared Inspector/Logs TracePanel use the document-aware query. A reopened-project notice identifies the historical document and explains how to request a current trace. Bound old-document source links fail even if the target's committed resource fingerprint matches exactly. The shared footer disables those document-expired source actions without repeatedly hashing complete resources during layout; existing target-definition checks still run at source dispatch and pending draft resolution. Unchanged source links within the same document retain their existing revision/fingerprint behavior.

Successful Save and failed Open retain the token and current trace. Authored edits and history retain the token but still follow revision freshness and per-resource fingerprint checks. Successful Open/New retires live previews/Inspector through the existing lifetime coordinator; historical Logs payloads retain their immutable values under their existing bounded retention policy. Explicit Refresh creates a new bound snapshot at the displayed trace coordinate and preserves separately entered XYZ. Copying old details remains available. No engine/model/storage API, schema, generation semantics, persisted preference or file-dialog save/discard authority changes.

## Deferred definitions

Six TraceDocumentTest definitions cover exact token identity versus equal-looking tokens, conservative context-free queries, identical same-UUID/revision reopening with retained history and disabled old links, Save/failed-Open preservation, same-document unchanged/changed source links with Undo, explicit current-document rebinding without historical mutation, and legacy/foreign identity compatibility. They have not run.

TraceLifetimeProbe defines one native ImGui workflow over production InspectorPanel, TracePanel, DiagnosticsPanel and GuardedNavigation with actual engine traces from the analytical field fixture. Eight phases each visit Inspector Trace, Inspector XYZ and retained Logs, yielding twenty-four presentation receipts. They cover initial currentness, Save, failed Open, identical Open, changed Open, explicit Refresh, Save after Refresh and returning to the original historical payload. Same-UUID replacement deliberately keeps EditorState.revision unchanged. An actual source-link draft guard expires across replacement; a held native Refresh gesture cannot borrow the successor lifetime. An explicit subsequent Refresh evaluates the original displayed coordinate in the CLEAR document, preserves the separately entered `(7,1,-2)` and leaves original details copyable from Logs.

The probe's Refresh dispatcher invokes the real engine synchronously as a controlled fixture. It does not instantiate Viewports, GLFW/OpenGL or the production background Refresh task. Existing deferred InspectorFreshnessProbe now additionally requires an actual Viewports snapshot to be document-bound. Independent asynchronous cancellation/publication scenarios remain in ViewportDocumentProbe and its prior definitions. The direct-session fixture preserves its private Properties object/raw seed; normal Open/New lifecycle continues to own its explicit save/discard flow.

scripts/verify_trace_lifetime.py checks all phase/form routes, exact freshness/source eligibility, history/Refresh counts, entered XYZ, complete project transformations/preferences and analytical traces. TraceLifetimeConsumer reloads both projects using only the engine JAR, independently evaluates `(2 + 2*(2+1) + 3)*2 = 22`, compares full original/current trace JSON, checks thresholds 10/30 and verifies actual SET/CLEAR final voxels. These are future definitions, not observed results.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.TraceLifetimeProbe build/trace-lifetime
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.TraceLifetimeConsumer build/trace-lifetime
python scripts/verify_trace_lifetime.py build/trace-lifetime
```

Future Windows wiring uses the bundled runtime, semicolon classpath and exit-code guards. Artifact lists include the native receipt, original/current projects and trace JSON. Development-branch push triggers remain disabled. No compiler, build, test, native probe, Python checker, generator or GitHub workflow ran for this batch. Physical input/DPI, full GUI/Windows 11 acceptance and production sign-off remain pending.
