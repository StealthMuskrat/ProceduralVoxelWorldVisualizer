# Guarded source completion after Apply

Status: **uncompiled and untested** on `codex/development-phase` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [trace source availability](TRACE_SOURCE_AVAILABILITY.md) and [shared source navigation](TRACE_EXPLANATIONS_AND_NAVIGATION.md). No A01–A36 acceptance gate advances.

A trace can link to an operation nested inside the currently edited Layer. Applying that Layer draft may change the operation that the old trace describes. Previously the pending guard checked the source before Apply, then followed the old link immediately after committing the draft. GuardedNavigation now checks source eligibility again after a successful Apply and before selecting the destination. If the edit changed the source, it remains committed once, the refreshed Layer draft remains selected and the status explains that a new source link is needed. One Undo restores the applied edit. An unchanged destination still navigates after the same single transaction.

SearchNavigation consumes the old pending intent before invoking the post-Apply check. It records the completed navigation version, exact document token, canonical project identity/revision, selection, private draft identity and JSON, multiselection and raw-seed input version. The destination can be selected only if that context remains current. A callback that changes the refreshed private draft or opens a successor decision cannot have that work overwritten by the older completion. Rejected requests do not advance the version; admitted requests and retirement do. Existing callers without a source predicate retain the public decide overload. Cancel does not invoke source checks, and a rejected draft Apply keeps its existing pending decision.

Source predicate failures at request or decision time are contained as bounded SOURCE_NAVIGATION_FAILED application errors. Before Apply they retain the private draft without a commit. After Apply they retain the committed edit, retire the old intent and explain that the draft was applied. Reveal failures are contained as SOURCE_REVEAL_FAILED after successful model navigation; they cannot replay the edit. A new current request may retry the reveal. Terminal guards release their retained source supplier. Ownership checks prevent error cleanup from clearing a successor request opened by the callback.

The existing shared modal still collects its actions before delivering the decision. GuardedNavigation contains runtime failures at its source/reveal boundaries so NavigationGuardDialog can close/end its popup normally. This does not change callback delivery to a post-popup mechanism, alter other dialog callers, or introduce an engine dependency on editor/native code.

## Deferred definitions

Nine GuardedNavigationCompletionTest definitions cover changed child source after a parent Apply, unchanged destination, failures at request/pre-Apply/post-Apply/reveal boundaries, healthy reveal retry, a successor opened during a request check or post-Apply check, changes to the refreshed private draft, single-commit/no-replay behavior and one-step Undo. These definitions have not run.

GuardedSourceProbe defines native production Apply/Cancel interactions without GLFW or OpenGL. Child-source and unchanged-source decisions each reflow across six wide/narrow/short/global/window font combinations, including a synthetic 6.25 font product, while action IDs remain stable within each opening. Six completion routes cover changed child, unchanged destination, pre-check failure, post-check failure, reveal failure/retry and a reentrant successor. Root ID-stack observations after each draw define popup-scope restoration, including exception routes. Successor Cancel must preserve its new private values. The probe emits complete saved projects, full engine traces, preferences, modal geometry and bounded failure receipts. Fixture reopening resets history between routes; intentional Apply edits are compared to explicit expected transformations.

scripts/verify_guarded_source.py independently checks the six routes, twelve native modal receipts, error codes/messages/counts, complete project transformations, one Undo and unchanged preferences. GuardedSourceConsumer uses only the engine JAR and repeats the established analytical field-22/provenance oracle. For every saved route it compares the full independently regenerated trace. Applying the child threshold changes the second operation from rejection at 30 to SET at 20 without changing the analytical scalar `(2 + 2 * (2 + 1) + 3) * 2 = 22` or final voxel type 1. The other authorized edits rename only the Layer.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.GuardedSourceProbe build/guarded-source
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.GuardedSourceConsumer build/guarded-source
python scripts/verify_guarded_source.py build/guarded-source
```

Future Windows wiring retains bundled Java, semicolon classpaths and exit-code guards. Artifact lists include the new receipts. Workflow triggers are unchanged; no development-branch push launches them. No compiler, build, test, native probe, Python checker, schema/generator or GitHub workflow ran for this batch. Physical input/DPI, all navigation callers, full GUI/Windows 11 acceptance and production sign-off remain pending.
