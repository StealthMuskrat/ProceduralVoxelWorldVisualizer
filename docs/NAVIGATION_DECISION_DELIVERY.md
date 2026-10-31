# Shared draft decision delivery

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [guarded source completion](GUARDED_SOURCE_COMPLETION.md) and [resource draft decisions](RESOURCE_DECISION_LAYOUT.md). No A01–A36 acceptance gate advances.

The shared NavigationGuardDialog serves resource selection, resource actions, source links, Find navigation and private-noise reference navigation. It previously delivered Apply/Discard/Cancel while its modal popup was still the current native window. A callback that revealed another editor or opened a destination popup therefore inherited the draft modal's native scope. A callback opening a successor on the same dialog could also have that opening closed or annotated by the older callback's result.

The dialog now collects all three actions and Escape, unwinds its child and ID scopes, ends the modal popup, and then delivers the chosen decision in the caller's native window. Child, ID and popup scopes use finally blocks. Explanation failures also unwind the scopes already entered. A terminal result closes the old native popup through a separate balanced scope after callback delivery. A destination popup opened by the callback is left alone.

NavigationDecisionScope owns the exact opening and native frame. Delivery is consumed before calling application code, with at most one action delivered per frame even if the dialog is drawn repeatedly. The wrapper also draws once per frame so repeated calls cannot append another body/footer to the same native modal. A successor opened during delivery is drawn in the next frame. Failed validation may retain the same opening and its feedback for another explicit action in a later frame. New openings invalidate old tickets and change action IDs. An explicit close invalidates outstanding completion authority. After the callback, the dialog changes feedback or closes only if the original opening still owns the result. A successor opened by either a terminal or retained callback keeps its own popup and blank initial feedback.

Existing session objects still decide which draft, source, selection or transaction is current and whether Apply is valid. This change does not grant a native widget transaction authority. GuardedNavigation retains its source-check/reveal error containment; ResourceEditCompletion retains its refresh failure handling. Other arbitrary callback exceptions propagate to their caller after native scope unwind. Consumption prevents a second delivery in that same frame; it does not automatically retire an application session or claim recovery for every caller.

## Deferred definitions

Six NavigationDecisionScopeTest definitions cover inactive/closed dialogs, repeated delivery in one frame, later explicit actions, foreign/stale-frame tickets, successor opening ownership, explicit close during completion and a throwing callback whose delivery was already consumed. None ran.

NavigationDeliveryProbe exercises the production shared component without GLFW or OpenGL. Twelve modal receipts cover retained and terminal openings across six wide/narrow/short/global/window font combinations, including a synthetic 6.25 font product. Ten routes cover retained/terminal results, a decision exception, an explanation exception, successors returned from terminal/retained callbacks, explicit close followed by a retained result, a destination popup opened by the callback, Escape superseding a released Apply and replacement while the predecessor's Apply is held. The caller draws the component twice per frame to define delivery-once behavior. Native root ID observations must agree inside every callback and after every draw, including failures; the probe's caller catches only its deliberately injected exceptions.

The probe keeps its canonical project, revision, history and preferences unchanged and emits full persisted values plus a real source trace. scripts/verify_navigation_delivery.py independently checks route counts, action ownership and modal geometry, complete persisted project/preferences and the analytical field-22 trace expectations. The existing engine-only TraceSourceConsumer independently replays the fixture and compares complete trace JSON. Its reuse here verifies deterministic/persistence invariants, not cache behavior.

The existing GuardedSourceProbe is extended with thirteen caller-window observations inside actual pre-Apply/post-Apply source predicates and reveal callbacks. It retains its real SearchNavigation/GuardedNavigation Apply, failure, successor, one-Undo and independent complete project/trace definitions. This supplements the direct shared-component probe; it does not certify all five integration callers.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.NavigationDeliveryProbe build/navigation-delivery
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.TraceSourceConsumer build/navigation-delivery
python scripts/verify_navigation_delivery.py build/navigation-delivery
```

Future Windows wiring uses the bundled runtime, semicolon classpaths and exit-code guards. Artifact lists include the new receipts. Workflow triggers remain unchanged; development-branch pushes launch no workflows. No compiler, build, test, native probe, Python checker, schema/generator or GitHub workflow ran for this batch. Physical input/DPI, all dialog callers, full GUI/Windows 11 acceptance and production sign-off remain pending.
