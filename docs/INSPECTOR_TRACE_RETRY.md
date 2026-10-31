# Exact Inspector trace retry

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [Logs-to-Inspector requests](TRACE_INSPECTOR_HANDOFF.md), [Inspector decisions](INSPECTOR_DECISIONS.md) and [document lifetime](TRACE_DOCUMENT_LIFETIME.md). No A01–A36 acceptance gate advances.

A failed coordinate request can target a different cell from both entered XYZ and the older displayed trace. Reconstructing a retry from either of those would inspect the wrong cell. Viewports now retains one InspectorTraceRetry only after a still-current coordinate failure or queue rejection. It contains the exact failed BlockPos, request identity/serial, document/project/revision and accepted input version. A weak canonical-project reference detects direct canonical replacement without retaining an old project tree. Picks without a known coordinate cannot create this recovery state.

The Inspector's existing Refresh footer slot reads Retry Trace while that candidate is current and no task is pending. A body notice names the exact attempted XYZ and says entered text stays unchanged. This works on both pages, with an older result and before any successful result exists. Without recovery, the slot retains its existing stale-trace Refresh behavior. No extra window, persisted setting or footer row is introduced.

InspectorActionContext includes the exact candidate reference and its eligibility in the complete post-draw review. Equal-looking successor attempts, disabled/pending transitions, raw edit/reset, document/revision/canonical changes, hidden return and changes during the last footer item cannot borrow a held Retry gesture. Viewports consumes the current candidate before calling its existing coordinate submission path. Valid completion still passes InspectorTask's exact-request gate and the document/revision/input publication checks before creating any result or recovery. An expired worker cannot replace a successor retry.

New work, input edits, explicit cancellation, document retirement, pick/miss replacement, successful trace publication and closure clear recovery. Source changes expire it; Save and failed Open retain it. Another failed explicit attempt receives a new candidate. Freeing queue capacity never retries automatically. Coordinate failure/queue messages now direct users to Retry Trace, superseding the earlier route-specific Logs retry wording. The entered XYZ authority, private Properties state, historical snapshots, project/history and engine semantics remain unchanged.

## Deferred definitions

Four InspectorTraceRetryTest definitions cover exact signed targets without parsing raw input, Save/failed Open and raw round trips, canonical/revision/same-UUID replacement, rejection of pick/unbound/foreign requests and project identity. Two additional InspectorActionContextTest definitions cover equal-looking retry replacement and pending/canonical eligibility changes without repairing invalid XYZ. None ran.

InspectorRetryProbe defines twelve production Inspector receipts: Coordinates and Trace across six wide/narrow/short/global/window font combinations, including a synthetic 6.25 font product. Further native phases define Escape, successor attempt replacement, pending work, direct canonical changes, same-UUID Open, raw edit/reset, a last-item retry replacement and hidden return. Two explicit callbacks evaluate failed `(7,0,3)` with no prior snapshot and with older `(2,0,3)` displayed, preserving typed `(-17,0,3)`. Its dispatcher uses the real engine synchronously as a fixture; it does not run a background task or GLFW/GL.

The existing deferred ViewportDocumentProbe now supplies a real production queue recovery scenario. It holds both editor workers, fills the bounded queue, requests the retained Logs coordinate `(2,0,0)` in the current CLEAR document and requires a nonpending retry candidate with no published result. Releasing capacity must leave the candidate waiting. Native input clicks Retry Trace, requiring one current EMPTY trace, cleared recovery and unchanged entered `(1,0,0)`. The same probe retains its later Open/queued-publication rejection and document-retirement scenarios. This is deferred integrated native/worker coverage, not a new hardware or full GUI claim.

scripts/verify_inspector_retry.py independently checks page/font containment, stable same-page ownership, exact callback serials/targets, no-result versus older-result routing and complete project/preferences equality. InspectorRetryConsumer independently distinguishes raw entered field -54/EMPTY, old displayed field 22 and attempted field 42 from `(2 + 2*(x+1) + 3)*2`. It compares the complete retry trace JSON to canonical engine replay. The extended document checker requires the real queue-recovery receipt; existing engine-only document replay continues to check its canonical trace coordinate and 720 cells.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.InspectorRetryProbe build/inspector-retry
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.InspectorRetryConsumer build/inspector-retry
python scripts/verify_inspector_retry.py build/inspector-retry
```

Future Windows wiring uses bundled Java, semicolon classpaths and exit-code guards. Artifact lists retain the receipts and source/result files. Development-branch push triggers remain disabled. No compiler, build, test, native probe, Python checker, schema/generator or GitHub workflow ran for this batch. Physical input/DPI, full GUI/Windows 11 acceptance, arbitrary device failures and production sign-off remain pending.
