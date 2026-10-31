# Trace coordinates from Logs to Inspector

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This follows [retained trace lifetime](TRACE_DOCUMENT_LIFETIME.md), [Inspector decisions](INSPECTOR_DECISIONS.md) and [preview document retirement](PREVIEW_DOCUMENT_LIFETIME.md). No A01–A36 acceptance gate advances.

Logs now configures its shared TracePanel with an optional Trace In Inspector action. Users can request the displayed historical coordinate against the current same-project document without re-entering XYZ. A reopened same-UUID trace can supply that coordinate while its old resource links remain unavailable. Foreign-project snapshots and blocked private-edit/source decisions cannot dispatch. Inspector's contained trace keeps its existing six-action footer; the configured Logs footer adds one bounded full-width third row.

TraceCoordinateIntent freezes the exact displayed snapshot, current document token, current canonical project identity and revision. It obtains the coordinate directly from the immutable trace; it never parses, repairs or replaces separately entered Inspector XYZ. Save/failed Open preserve the intent; replacement, canonical edits and revision changes retire it. A newly clicked same-project historical snapshot creates a new current-document intent, rather than reviving old source-link authority.

TraceActionScope additionally versions canonical project identity and Inspect eligibility. Complete footer drawing and balanced native scope cleanup precede the last current-context check, Escape suppression and callback. Inspect consumes its host ticket before invoking the callback; failure cannot replay that consumed native gesture, and explicit retry gets a new presentation. Another native host retains its own authority. Footer canonical identity uses an opaque token backed by a weak project observation, so hidden footer contexts do not retain an old complete project tree.

Desktop connects only the Logs trace host to Viewports.inspectTrace. Viewports independently checks the intent, synchronizes an unobserved document replacement before admitting work, preserves the clicked immutable snapshot as the pending result, reveals/uncollapses Inspector and starts the existing replaceable background coordinate task. Successful work publishes a new current-document snapshot and normal bounded Logs entry. Existing Inspector request/document/input gates, cancellation, interruption and queue bounds remain authoritative. Separately entered XYZ and Properties drafts remain untouched. Queue/failure feedback from this route names Trace In Inspector in Logs as the retry action.

The action is read-only with respect to the project, history, preferences and camera. It does not reinterpret a retained rendered runtime index, mutate historical values or grant navigation into expired source definitions. Engine/model/storage semantics, project schemas, normal file lifecycle and existing standalone TracePanel constructors are unchanged.

## Deferred definitions

Four TraceCoordinateIntentTest definitions cover newly owned same-project historical intent after Open, canonical replacement without a revision change, authored revision changes, Save/failed Open, foreign/New rejection and signed-bound historical coordinates without parsing/repairing separate raw XYZ. Two additional TraceActionScopeTest definitions cover one-shot consumed Inspect, failure/retry presentation, another host's independence and canonical/eligibility changes. None ran.

TraceInspectProbe defines twelve production optional-footer layout receipts: current/reopened snapshots across six wide/narrow/short/global/window-font combinations, including a 6.25 font product. Synthetic font fixtures reset before preference baselines. Actual native input phases define Escape, held document/snapshot/canonical replacement, hidden host return, foreign disabling, consumed callback failure, explicit retry and successor preservation. Four callback receipts require balanced parent scopes, exact snapshot/coordinate/context and one bounded error per injected failure. This probe has native ImGui without GLFW, GL or worker delivery.

The existing deferred ViewportDocumentProbe supplies the production backend integration. Its real GL/worker fixture now requests an old SET trace coordinate in the reopened CLEAR document through Viewports.inspectTrace, requires a current EMPTY trace at `(2,0,0)`, retains separately entered `(1,0,0)` and publishes exactly one payload. It repeats that route with a completed UI callback queued across Open, requiring no late publication before Viewports draws the replacement. Direct session New retains its prior immutable trace under that low-level fixture; normal EditorState/file lifecycle still owns Logs cleanup. Independent ViewportDocumentConsumer also checks that trace coordinate against the canonical source/current worlds and keeps its existing exact field/720-cell replay.

scripts/verify_trace_inspect.py independently checks all twelve footer geometries/IDs, exact delivery coordinates, consumed failure counts and full project/preferences equality. Shared engine-only TraceConsumer replays the unchanged analytical fixture (field 22, thresholds 10/30, real SET and final voxel agreement). The extended document checker validates backend coordinate/retention receipts. These are definitions rather than pass evidence.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.TraceInspectProbe build/trace-inspect
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.TraceConsumer build/trace-inspect/trace-project.json
python scripts/verify_trace_inspect.py build/trace-inspect
```

The existing ViewportDocumentProbe/ViewportDocumentConsumer commands exercise the extended backend scenario. Future Windows wiring retains bundled Java, semicolon classpaths and exit-code guards; both artifact lists include the new footer receipts. Development branch pushes still start no workflow. No compiler, build, test, probe, checker, schema/generator or GitHub workflow ran for this batch. Physical input/DPI, full GUI/Windows 11 acceptance, arbitrary device failures and production sign-off remain pending.
