# Preview document lifetime

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This follows [visualizer controls](VIEWPORT_CONTROL_PANES.md), [visualizer decisions](VIEWPORT_COMMANDS.md) and [Inspector decisions](INSPECTOR_DECISIONS.md). No A01–A36 acceptance gate advances.

DocumentSession has an opaque lifetime token: successful Open/New replaces it; Save, authored edits and Undo/Redo retain it. Viewports now observes that identity before GPU timing collection and before any viewport upload/drawing. PreviewDocumentLifetime commits an observed identity only after retirement completes. Failed retirement leaves the boundary pending for a later retry; it cannot authorize drawing stale content as current.

Retirement cancels pending Inspector work, closes its live presentation and clears its current trace pointer and source-control presentation. Entered Inspector XYZ remains private text. Immutable previously retained snapshots remain unchanged; EditorState/file lifecycle still owns clearing project Logs and Properties drafts. A direct DocumentSession fixture is not the production Open/New save/discard workflow. This supersedes the earlier InspectorFreshnessProbe expectation that a live Inspector would remain open on a foreign snapshot after New; explicit foreign historical presentations still retain their unavailable-source/Refresh guards.

Each visualizer releases gestures/free-fly capture, advances its CPU mailbox epoch, cancels fixed work, retires streaming setup/ChunkScene ownership and clears bounded row/volume caches. Generator/stage selections, source fingerprints, completed-mode markers, samplers, accepted image/slice/volume observations, fixed pick bounds, failure/recovery controllers and pending uploads cannot cross the boundary. Command/option scopes, field feedback and control scroll measurements are released. The World mode returns to Final; display/numeric/camera settings keep their existing view-only values. An initial World request is admitted with Auto off because the former document's last submission cannot suppress it. Ordinary edits in the same document retain existing manual regeneration behavior.

GlViewport.clearPreview replaces the color texture with a transparent one-pixel image before selecting an empty mesh/palette. It retires volume arenas, streaming renderer, shadows and outstanding GPU timing queries while retaining reusable device objects. Clearing actual pixels matters because a newly started streaming renderer can return early until it has data. Source retirement must not expose the former framebuffer during that interval. No new renderer/engine numerical semantics or persisted settings are introduced.

NoiseImageRows.clear retires cache leases and advances its minimum request epoch without closing the reusable cache. A canceled or interruption-ignoring old sampler cannot rebind the source or insert rows afterward, even if the replacement document has the same source key. Its previously produced immutable image remains readable. Existing bounded cache budgets retain their authority.

InspectorRequests additionally binds each production pick/coordinate request to the exact document token. Both success and failure publication check that identity independently of revision/view checks. This is necessary because Desktop can drain queued UI callbacks before Viewports observes a replacement, and direct successful reopening can retain both resource UUIDs and EditorState.revision. Existing package-level unbound overloads remain compatible; they cannot authorize a token-bound request.

## Deferred definitions

Six pure definitions cover lifetime-preserving edit/history/save, same-UUID reopen and New, failed retirement/retry with late CPU results, equal-looking token rejection and legacy-overload fencing, both Inspector request kinds before viewport synchronization, cleared same-key cache reuse, and an interruption-ignoring in-flight sampler. They have not run.

ViewportDocumentProbe defines a native GLFW/OpenGL/ImGui production Viewports scenario with real bounded editor workers. It creates constant .125/.25 sources and a red solid world, defines acceptance of the original 2D image and fixed World frame, then commits CLEAR with Auto off and requires the former frame/submission to remain. Held workers keep replacement work pending while a direct same-UUID Open at the same editor revision must retire all three views, cancel queued Inspector work and remove old World pixels. Releasing workers defines replacement EMPTY acceptance. A completed Inspector callback is also left queued across another same-UUID Open and drained before drawing, exercising the independent publication token. A subsequent direct New defines empty World recovery. This is one production GL workflow, not file-dialog automation, streamed-volume hardware coverage or physical DPI acceptance.

The receipt contains per-view epochs/selections/cache/accepted-state observations, three center pixels and submission stamps. scripts/verify_viewport_document.py independently checks those values and complete source/committed/replacement/empty JSON relationships. ViewportDocumentConsumer uses only the engine JAR: exact constant fields plus 720 signed-chunk cells over roots 2/4 across five saved projects. Preferences must remain equal. The fixture deliberately keeps a private Properties object and rejected seed text through direct session calls; normal application lifecycle remains responsible for its own save/discard resolution. The existing deferred Inspector freshness probe/checker now expects live retirement and explicitly starts a new-document coordinate request.

Future Linux invocation after builds/testing resume:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.ViewportDocumentProbe build/viewport-document
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.ViewportDocumentConsumer build/viewport-document
python scripts/verify_viewport_document.py build/viewport-document
```

The future Windows workflow uses the bundled Java runtime and semicolon classpath, retains exit-code guards and uploads the same artifacts. Development branch pushes still trigger no workflow. No build, test, probe, checker or GitHub workflow ran for this batch. Physical input/DPI, native Open/New save/discard interactions, real device recovery and full GUI/Windows 11 acceptance remain pending.
