# Named dialog font ownership

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [runtime DPI/font composition](UI_DPI_AND_FONT_DENSITY.md) and [shared draft decisions](NAVIGATION_DECISION_DELIVERY.md). No A01–A36 acceptance gate advances.

DialogLayout already used its logical owner to choose a viewport and work-area anchor, but modal font inheritance came from whichever native window called begin. Production callers often draw their dialogs after ending Properties, Resources or Find. In that situation an unrelated caller's multiplier, or the fallback window's default, could replace the named owner's configured text size. Existing synthetic fixtures that used the same caller/owner multiplier could not distinguish those routes.

Active named dialogs now recompose their body/control font from the current font's raster size and font scale, the current IO font-global factor, the named owner's live window preference and the dialog target's runtime DPI ratio. With the production atlas this is `13 × user global × owner multiplier × target content scale / framebuffer density`. The implementation reads the actual active font rather than assuming the atlas always has a logical size of 13. It normalizes the modal's native local window scale before applying the desired size, removing any caller multiplier already inherited by ImGui. Nested child/popup wrappers then inherit that result through the existing DPI-aware normalization.

The owner preference is read on every active begin, so hidden owners and live owner-scale changes do not depend on an old font measurement. No host font snapshots, native wrappers or font pointers are retained. Caller font state is restored by ordinary popup scope unwind. Ownerless project-file dialogs keep their existing caller inheritance. Unknown logical scale owners reject at construction, before a native popup can be entered; existing production owners are registered window targets. Unknown stored preference keys remain preserved by their existing configuration contract.

Inactive dialogs still return before any native font or NextWindow changes. Work-area fitting, viewport selection, draft authority, callbacks and Apply/Discard/Cancel semantics remain separate. No new preference field, registry target, schema version, atlas rebuild or texture allocation is introduced. The older DraftPopupLayoutProbe fixture now sets its intended Properties override explicitly before measuring named guards, and restores it afterward, so its declared window scale remains part of the scenario.

## Deferred definitions

Eight DialogOwnerScaleTest definitions cover fractional dense-atlas sizes, custom active fonts, independent main/detached DPI arithmetic, raster-density invariance, invalid/overflow/underflow metrics, existing preference bounds, owner validation before JNI and absent-override/project/history immutability. None ran.

DialogOwnerScaleProbe uses production DialogLayout, UiScale, UiDpiPolicy and SettingsPageChrome without GLFW or OpenGL. Thirty receipts cover Properties, Find, Resources, Visualizers and ownerless dialogs across six wide/narrow/short/user-global/owner/caller/content-DPI/framebuffer combinations. The named owner window ends before an unrelated Logs-scaled caller begins. All named owner multipliers deliberately differ from the caller's. Several cases hide the owner while changing its preference and frame scale. Measured modal/body/child/nested-popup fonts must follow the owner, while both caller measurements remain unchanged. Ownerless fonts must follow the caller. The high-content-DPI fixture includes logical text height 162.5; this is explicitly synthetic, not an observed physical monitor setup.

Native Cancel gestures must close each dialog after reflow. Five inactive-modal cases require unchanged caller text and preserved queued geometry for a following window. Complete canonical project, private draft/raw seed input, revision, history and preferences must remain unchanged. The probe temporarily assigns declared preferences for each case and restores the complete original scale map/global value before persisted comparison.

scripts/verify_dialog_owner_scale.py independently recomputes the font equations and checks all thirty routes, native action IDs/geometry, five inactive cases and complete project/preferences equality. The existing engine-only TraceSourceConsumer replays the unchanged analytical field-22 fixture and compares complete trace JSON. These definitions have not been compiled or executed.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.DialogOwnerScaleProbe build/dialog-owner-scale
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.TraceSourceConsumer build/dialog-owner-scale
python scripts/verify_dialog_owner_scale.py build/dialog-owner-scale
```

Future Windows wiring uses the bundled runtime, semicolon classpaths and exit-code guards. Artifact lists include the new receipts. Workflow triggers remain unchanged; development-branch pushes launch no workflows. No compiler, build, test, native probe, Python checker, schema/generator or GitHub workflow ran for this batch. Title/tab metrics during a scale transition, complete style/padding scaling, every dialog/control path, physical DPI/detached/GPU behavior, full GUI/Windows 11 acceptance and production sign-off remain pending.
